package com.atlas.confluence;

import com.atlas.services.ApiConsumer;
import com.atlas.services.ApiSummary;
import com.atlas.services.ChangeEntry;
import com.atlas.services.DatabaseUsage;
import com.atlas.services.ExternalDependencyUsage;
import com.atlas.services.Service;
import com.atlas.services.ServiceDependencyEdge;
import com.atlas.services.ServiceRelationshipsRepository;
import com.atlas.services.ServiceRepository;
import com.atlas.services.SoftDeletedApiPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the DB → Confluence sync. Loads each service plus its
 * relationship rows, renders a Confluence storage-format page, and either
 * creates a new page (first sync) or updates an existing one (subsequent
 * syncs), tracking the page ID and last-sync timestamp on the service row.
 *
 * Per-service exceptions are caught and reported in {@link SyncResult} —
 * one bad service does not abort the rest of {@code syncAll()}.
 *
 * Layout (per docs/confluence-layout.md):
 *  - Each service page is parented under a single landing page titled
 *    {@code atlas.confluence.landing-page-title} (default "Atlas — Service Inventory").
 *  - Two inventory sub-pages ("Inventory: Data Stores", "Inventory:
 *    External Dependencies") and one "About Atlas" page also parent under
 *    the landing page.
 *  - Service page titles are prefixed with "Service: " so they're easy to
 *    scan in the space's sidebar.
 *  - Cross-links: each service page links to its referenced peers
 *    (upstream/downstream/consumers) using the peers' Confluence page URLs,
 *    and back-links its database / external-dep references into the
 *    inventory pages.
 *  - All well-known pages are regenerated on every sync.
 */
@Component
public class SyncCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SyncCoordinator.class);
    private static final int RECENT_CHANGES_LIMIT = 10;
    private static final String SERVICE_TITLE_PREFIX = "Service: ";
    private static final String DATA_STORE_INVENTORY_TITLE = "Inventory: Data Stores";
    private static final String EXTERNAL_DEP_INVENTORY_TITLE = "Inventory: External Dependencies";
    private static final String ABOUT_PAGE_TITLE = "About Atlas";
    private static final String ARCHITECTURE_MAP_TITLE = "Atlas — Architecture Map";

    private final ServiceRepository serviceRepository;
    private final ServiceRelationshipsRepository relationships;
    private final ServicePageRenderer renderer;
    private final ApiEndpointPageRenderer endpointRenderer;
    private final LandingPageRenderer landingRenderer;
    private final DataStoreInventoryRenderer dataStoreInventoryRenderer;
    private final ExternalDependencyInventoryRenderer externalDepInventoryRenderer;
    private final AboutPageRenderer aboutRenderer;
    private final ArchitectureMapRenderer architectureMapRenderer;
    private final ConfluenceClient confluenceClient;
    private final String spaceKey;
    private final String baseUrl;
    private final String landingPageTitle;

    private volatile String spaceId;

    public SyncCoordinator(
            ServiceRepository serviceRepository,
            ServiceRelationshipsRepository relationships,
            ServicePageRenderer renderer,
            ApiEndpointPageRenderer endpointRenderer,
            LandingPageRenderer landingRenderer,
            DataStoreInventoryRenderer dataStoreInventoryRenderer,
            ExternalDependencyInventoryRenderer externalDepInventoryRenderer,
            AboutPageRenderer aboutRenderer,
            ArchitectureMapRenderer architectureMapRenderer,
            ConfluenceClient confluenceClient,
            @Value("${atlas.confluence.space-key}") String spaceKey,
            @Value("${atlas.confluence.base-url}") String baseUrl,
            @Value("${atlas.confluence.landing-page-title:Atlas — Service Inventory}") String landingPageTitle) {
        this.serviceRepository = serviceRepository;
        this.relationships = relationships;
        this.renderer = renderer;
        this.endpointRenderer = endpointRenderer;
        this.landingRenderer = landingRenderer;
        this.dataStoreInventoryRenderer = dataStoreInventoryRenderer;
        this.externalDepInventoryRenderer = externalDepInventoryRenderer;
        this.aboutRenderer = aboutRenderer;
        this.architectureMapRenderer = architectureMapRenderer;
        this.confluenceClient = confluenceClient;
        this.spaceKey = spaceKey;
        this.baseUrl = baseUrl;
        this.landingPageTitle = landingPageTitle;
    }

    /** Sync every service in the DB. Per-service errors are isolated. */
    public SyncResult syncAll() {
        // Cleanup pass first: any service rows soft-deleted since last sync
        // need their Confluence pages removed. Independent of the active
        // service list — runs even when there are no live services.
        cleanupDeletedServices();
        cleanupDeletedApiPages();

        List<Service> services = serviceRepository.findAll();
        if (services.isEmpty()) {
            // No live services — skip the rest of the Confluence calls.
            return new SyncResult(0, 0, List.of());
        }

        WellKnownPages pages = ensureWellKnownPages(services);
        Map<UUID, String> servicePageUrls = buildServicePageUrls(services);

        int successes = 0;
        List<SyncFailure> failures = new ArrayList<>();
        for (Service s : services) {
            try {
                syncOneInternal(s, pages, servicePageUrls);
                successes++;
                if (s.getConfluencePageId() != null) {
                    servicePageUrls.put(s.getId(), pageUrlFor(s.getConfluencePageId()));
                }
            } catch (Exception e) {
                log.warn("Sync failed for service {} ({}): {}", s.getName(), s.getId(), e.getMessage());
                failures.add(new SyncFailure(s.getId(), s.getName(), e.getMessage()));
            }
        }

        refreshWellKnownPages(pages, services, servicePageUrls);
        return new SyncResult(successes, failures.size(), failures);
    }

    /**
     * Find every soft-deleted api row whose Confluence page still exists,
     * delete the page, and null out {@code apis.confluence_page_id}. Mirrors
     * {@link #cleanupDeletedServices()} at the endpoint grain (M2.5). 404 on
     * the page is treated as success — already gone. Per-api errors are
     * logged and skipped so one stuck endpoint doesn't block siblings.
     */
    private void cleanupDeletedApiPages() {
        List<SoftDeletedApiPage> orphans = relationships.findSoftDeletedApisWithConfluencePage();
        for (SoftDeletedApiPage orphan : orphans) {
            try {
                // ConfluenceClient.deletePage swallows 404 (page already gone)
                // so this is the "succeeded" path for both 204 and 404.
                confluenceClient.deletePage(orphan.confluencePageId());
                relationships.clearApiConfluencePageId(orphan.apiId());
                log.info("Cleaned up endpoint page {} for soft-deleted api {} {} on service {}",
                        orphan.confluencePageId(), orphan.method(), orphan.path(), orphan.serviceName());
            } catch (Exception e) {
                log.warn("Cleanup of endpoint page {} for {} {} on {} failed: {}",
                        orphan.confluencePageId(), orphan.method(), orphan.path(),
                        orphan.serviceName(), e.getMessage());
            }
        }
    }

    /**
     * Find every soft-deleted service that still has a Confluence page,
     * delete the page, and null out the page ID. Per-service errors are
     * logged and skipped — one stuck page does not block other cleanups.
     * Idempotent: re-running on already-cleaned rows does nothing because
     * they no longer match the {@code confluence_page_id IS NOT NULL} filter.
     */
    private void cleanupDeletedServices() {
        List<Service> orphans = serviceRepository.findSoftDeletedWithConfluencePage();
        for (Service s : orphans) {
            String pageId = s.getConfluencePageId();
            try {
                confluenceClient.deletePage(pageId);
                serviceRepository.clearConfluencePageId(s.getId());
                log.info("Cleaned up Confluence page {} for soft-deleted service {} ({})",
                        pageId, s.getName(), s.getId());
            } catch (Exception e) {
                log.warn("Cleanup of page {} for soft-deleted service {} ({}) failed: {}",
                        pageId, s.getName(), s.getId(), e.getMessage());
            }
        }
    }

    /** Sync one service by ID. */
    public SyncResult syncOne(UUID serviceId) {
        Service s = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("No service with id " + serviceId));
        try {
            List<Service> allServices = serviceRepository.findAll();
            WellKnownPages pages = ensureWellKnownPages(allServices);
            Map<UUID, String> servicePageUrls = buildServicePageUrls(allServices);
            syncOneInternal(s, pages, servicePageUrls);
            if (s.getConfluencePageId() != null) {
                servicePageUrls.put(s.getId(), pageUrlFor(s.getConfluencePageId()));
            }
            refreshWellKnownPages(pages, allServices, servicePageUrls);
            return new SyncResult(1, 0, List.of());
        } catch (Exception e) {
            log.warn("Sync failed for service {} ({}): {}", s.getName(), s.getId(), e.getMessage());
            return new SyncResult(0, 1, List.of(new SyncFailure(s.getId(), s.getName(), e.getMessage())));
        }
    }

    // ---- Internals ---------------------------------------------------------

    private void syncOneInternal(Service service, WellKnownPages pages,
                                 Map<UUID, String> servicePageUrls) {
        ServicePageContext ctx = buildContext(service, servicePageUrls, pages.inventoryUrls());
        String body = renderer.render(ctx);
        String title = SERVICE_TITLE_PREFIX + service.getName();

        if (service.getConfluencePageId() == null || service.getConfluencePageId().isBlank()) {
            String pageId = confluenceClient.createPage(pages.spaceId(), title, body, pages.landingId());
            service.setConfluencePageId(pageId);
        } else {
            try {
                confluenceClient.updatePage(service.getConfluencePageId(), title, body, pages.landingId());
            } catch (ConfluencePageNotFoundException e) {
                log.info("Confluence page {} for service {} no longer exists; creating fresh page.",
                        service.getConfluencePageId(), service.getName());
                String newPageId = confluenceClient.createPage(pages.spaceId(), title, body, pages.landingId());
                service.setConfluencePageId(newPageId);
            }
        }
        service.setLastSyncedToConfluence(OffsetDateTime.now());
        serviceRepository.save(service);

        // Per-endpoint pages: each api row gets its own Confluence page, parented
        // under the service page just persisted. Per-endpoint failures are caught
        // and logged so one bad endpoint doesn't block the others — orphan
        // pages from removed apis are deferred per the M2 scope decision in
        // docs/plans/2026-04-29-code-driven-documentation.md.
        syncEndpointPages(service, servicePageUrls);
    }

    private void syncEndpointPages(Service service, Map<UUID, String> servicePageUrls) {
        String servicePageId = service.getConfluencePageId();
        String serviceUrl = servicePageId != null ? pageUrlFor(servicePageId) : null;
        for (ApiSummary api : relationships.findApisFor(service.getId())) {
            try {
                syncOneEndpoint(service, api, servicePageId, serviceUrl);
            } catch (Exception e) {
                log.warn("Endpoint-page sync failed for {} {} on service {}: {}",
                        api.method(), api.path(), service.getName(), e.getMessage());
            }
        }
    }

    private void syncOneEndpoint(Service service, ApiSummary api,
                                 String servicePageId, String serviceUrl) {
        ApiEndpointPageContext ctx = new ApiEndpointPageContext(service, api, serviceUrl);
        String body = endpointRenderer.render(ctx);
        String title = ApiEndpointPageRenderer.pageTitle(service, api);
        String pageId = api.confluencePageId();
        if (pageId == null || pageId.isBlank()) {
            String created = confluenceClient.createPage(resolveSpaceId(), title, body, servicePageId);
            relationships.setApiConfluencePageId(api.id(), created);
        } else {
            try {
                confluenceClient.updatePage(pageId, title, body, servicePageId);
            } catch (ConfluencePageNotFoundException e) {
                log.info("Endpoint page {} for {} {} on {} no longer exists; recreating.",
                        pageId, api.method(), api.path(), service.getName());
                String fresh = confluenceClient.createPage(resolveSpaceId(), title, body, servicePageId);
                relationships.setApiConfluencePageId(api.id(), fresh);
            }
        }
    }

    private ServicePageContext buildContext(Service service, Map<UUID, String> servicePageUrls,
                                            InventoryPageUrls inventoryUrls) {
        UUID id = service.getId();
        List<ApiSummary> apis = relationships.findApisFor(id);
        List<ApiPresentation> apiPresentations = new ArrayList<>(apis.size());
        for (ApiSummary api : apis) {
            List<ApiConsumer> consumers = relationships.findApiConsumersFor(api.id());
            String endpointUrl = api.confluencePageId() == null || api.confluencePageId().isBlank()
                    ? null
                    : pageUrlFor(api.confluencePageId());
            apiPresentations.add(new ApiPresentation(api, consumers, endpointUrl));
        }
        List<ServiceDependencyEdge> upstream = relationships.findUpstreamDependenciesOf(id);
        List<ServiceDependencyEdge> downstream = relationships.findDownstreamDependenciesOf(id);
        List<DatabaseUsage> dbs = relationships.findDatabasesFor(id);
        List<ExternalDependencyUsage> exts = relationships.findExternalDependenciesFor(id);
        List<ChangeEntry> changes = relationships.findRecentChangesFor(id, RECENT_CHANGES_LIMIT);
        return new ServicePageContext(service, apiPresentations, upstream, downstream, dbs, exts, changes,
                servicePageUrls, inventoryUrls);
    }

    /**
     * Resolve the four "well-known" pages that belong in every Atlas space —
     * landing, data-store inventory, external-dep inventory, About — looking
     * each up by title and creating with a placeholder body if missing.
     * Returns enough context for service-page rendering (URLs, parent ID).
     * Bodies are refreshed in {@link #refreshWellKnownPages} after services sync.
     */
    private WellKnownPages ensureWellKnownPages(List<Service> services) {
        String resolvedSpaceId = resolveSpaceId();
        OffsetDateTime now = OffsetDateTime.now();

        String landingId = confluenceClient.findPageByTitle(resolvedSpaceId, landingPageTitle)
                .orElseGet(() -> {
                    log.info("Creating landing page '{}' in space {}", landingPageTitle, resolvedSpaceId);
                    return confluenceClient.createPage(resolvedSpaceId, landingPageTitle,
                            landingRenderer.render(services, Map.of(), now), null);
                });

        String dsInvId = confluenceClient.findPageByTitle(resolvedSpaceId, DATA_STORE_INVENTORY_TITLE)
                .orElseGet(() -> {
                    log.info("Creating data-store inventory page in space {}", resolvedSpaceId);
                    return confluenceClient.createPage(resolvedSpaceId, DATA_STORE_INVENTORY_TITLE,
                            dataStoreInventoryRenderer.render(List.of(), Map.of(), now), landingId);
                });

        String edInvId = confluenceClient.findPageByTitle(resolvedSpaceId, EXTERNAL_DEP_INVENTORY_TITLE)
                .orElseGet(() -> {
                    log.info("Creating external-dependency inventory page in space {}", resolvedSpaceId);
                    return confluenceClient.createPage(resolvedSpaceId, EXTERNAL_DEP_INVENTORY_TITLE,
                            externalDepInventoryRenderer.render(List.of(), Map.of(), now), landingId);
                });

        String aboutId = confluenceClient.findPageByTitle(resolvedSpaceId, ABOUT_PAGE_TITLE)
                .orElseGet(() -> {
                    log.info("Creating About page in space {}", resolvedSpaceId);
                    return confluenceClient.createPage(resolvedSpaceId, ABOUT_PAGE_TITLE,
                            aboutRenderer.render(now), landingId);
                });

        String archMapId = confluenceClient.findPageByTitle(resolvedSpaceId, ARCHITECTURE_MAP_TITLE)
                .orElseGet(() -> {
                    log.info("Creating Architecture Map page in space {}", resolvedSpaceId);
                    return confluenceClient.createPage(resolvedSpaceId, ARCHITECTURE_MAP_TITLE,
                            architectureMapRenderer.render(services, List.of(), now), landingId);
                });

        InventoryPageUrls inventoryUrls = new InventoryPageUrls(
                pageUrlFor(dsInvId), pageUrlFor(edInvId));
        return new WellKnownPages(resolvedSpaceId, landingId, dsInvId, edInvId, aboutId, archMapId, inventoryUrls);
    }

    /**
     * After services sync, refresh the four well-known pages with current
     * data. Each is best-effort — failures log a warning but don't fail the
     * overall sync.
     */
    private void refreshWellKnownPages(WellKnownPages pages, List<Service> services,
                                       Map<UUID, String> servicePageUrls) {
        OffsetDateTime now = OffsetDateTime.now();
        try {
            confluenceClient.updatePage(pages.landingId(), landingPageTitle,
                    landingRenderer.render(services, servicePageUrls, now), null);
        } catch (Exception e) {
            log.warn("Landing page refresh failed: {}", e.getMessage());
        }
        try {
            confluenceClient.updatePage(pages.dsInvId(), DATA_STORE_INVENTORY_TITLE,
                    dataStoreInventoryRenderer.render(
                            relationships.findAllDataStoresWithUsages(), servicePageUrls, now),
                    pages.landingId());
        } catch (Exception e) {
            log.warn("Data-store inventory refresh failed: {}", e.getMessage());
        }
        try {
            confluenceClient.updatePage(pages.edInvId(), EXTERNAL_DEP_INVENTORY_TITLE,
                    externalDepInventoryRenderer.render(
                            relationships.findAllExternalDependenciesWithUsages(), servicePageUrls, now),
                    pages.landingId());
        } catch (Exception e) {
            log.warn("External-dependency inventory refresh failed: {}", e.getMessage());
        }
        try {
            confluenceClient.updatePage(pages.aboutId(), ABOUT_PAGE_TITLE,
                    aboutRenderer.render(now), pages.landingId());
        } catch (Exception e) {
            log.warn("About page refresh failed: {}", e.getMessage());
        }
        try {
            confluenceClient.updatePage(pages.archMapId(), ARCHITECTURE_MAP_TITLE,
                    architectureMapRenderer.render(
                            services, relationships.findAllServiceDependencies(), now),
                    pages.landingId());
        } catch (Exception e) {
            log.warn("Architecture map page refresh failed: {}", e.getMessage());
        }
    }

    private Map<UUID, String> buildServicePageUrls(List<Service> services) {
        Map<UUID, String> urls = new HashMap<>();
        for (Service s : services) {
            String pageId = s.getConfluencePageId();
            if (pageId != null && !pageId.isBlank()) {
                urls.put(s.getId(), pageUrlFor(pageId));
            }
        }
        return urls;
    }

    private String pageUrlFor(String pageId) {
        // Confluence Cloud page URL pattern: {baseUrl}/spaces/{KEY}/pages/{ID}
        // baseUrl already includes /wiki (e.g., https://example.atlassian.net/wiki).
        return baseUrl + "/spaces/" + spaceKey + "/pages/" + pageId;
    }

    private String resolveSpaceId() {
        String cached = spaceId;
        if (cached == null) {
            cached = confluenceClient.getSpaceIdByKey(spaceKey);
            spaceId = cached;
        }
        return cached;
    }

    /**
     * Tuple bundle of the four well-known page IDs + the inventory URL
     * record threaded into per-service rendering. Internal to the
     * coordinator; not exposed.
     */
    private record WellKnownPages(
            String spaceId,
            String landingId,
            String dsInvId,
            String edInvId,
            String aboutId,
            String archMapId,
            InventoryPageUrls inventoryUrls) {
    }
}
