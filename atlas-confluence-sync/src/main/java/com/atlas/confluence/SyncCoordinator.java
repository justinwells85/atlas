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

    private final ServiceRepository serviceRepository;
    private final ServiceRelationshipsRepository relationships;
    private final ServicePageRenderer renderer;
    private final LandingPageRenderer landingRenderer;
    private final DataStoreInventoryRenderer dataStoreInventoryRenderer;
    private final ExternalDependencyInventoryRenderer externalDepInventoryRenderer;
    private final AboutPageRenderer aboutRenderer;
    private final ConfluenceClient confluenceClient;
    private final String spaceKey;
    private final String baseUrl;
    private final String landingPageTitle;

    private volatile String spaceId;

    public SyncCoordinator(
            ServiceRepository serviceRepository,
            ServiceRelationshipsRepository relationships,
            ServicePageRenderer renderer,
            LandingPageRenderer landingRenderer,
            DataStoreInventoryRenderer dataStoreInventoryRenderer,
            ExternalDependencyInventoryRenderer externalDepInventoryRenderer,
            AboutPageRenderer aboutRenderer,
            ConfluenceClient confluenceClient,
            @Value("${atlas.confluence.space-key}") String spaceKey,
            @Value("${atlas.confluence.base-url}") String baseUrl,
            @Value("${atlas.confluence.landing-page-title:Atlas — Service Inventory}") String landingPageTitle) {
        this.serviceRepository = serviceRepository;
        this.relationships = relationships;
        this.renderer = renderer;
        this.landingRenderer = landingRenderer;
        this.dataStoreInventoryRenderer = dataStoreInventoryRenderer;
        this.externalDepInventoryRenderer = externalDepInventoryRenderer;
        this.aboutRenderer = aboutRenderer;
        this.confluenceClient = confluenceClient;
        this.spaceKey = spaceKey;
        this.baseUrl = baseUrl;
        this.landingPageTitle = landingPageTitle;
    }

    /** Sync every service in the DB. Per-service errors are isolated. */
    public SyncResult syncAll() {
        List<Service> services = serviceRepository.findAll();
        if (services.isEmpty()) {
            // Nothing to do — skip Confluence calls entirely. Landing page
            // maintenance only kicks in once the DB has at least one service.
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
    }

    private ServicePageContext buildContext(Service service, Map<UUID, String> servicePageUrls,
                                            InventoryPageUrls inventoryUrls) {
        UUID id = service.getId();
        List<ApiSummary> apis = relationships.findApisFor(id);
        List<ApiPresentation> apiPresentations = new ArrayList<>(apis.size());
        for (ApiSummary api : apis) {
            List<ApiConsumer> consumers = relationships.findApiConsumersFor(api.id());
            apiPresentations.add(new ApiPresentation(api, consumers));
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

        InventoryPageUrls inventoryUrls = new InventoryPageUrls(
                pageUrlFor(dsInvId), pageUrlFor(edInvId));
        return new WellKnownPages(resolvedSpaceId, landingId, dsInvId, edInvId, aboutId, inventoryUrls);
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
            InventoryPageUrls inventoryUrls) {
    }
}
