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
 *  - Service page titles are prefixed with "Service: " so they're easy to
 *    scan in the space's sidebar.
 *  - Cross-links: each service page links to its referenced peers
 *    (upstream/downstream/consumers) using the peers' Confluence page URLs.
 *  - The landing page is regenerated on every sync with a service-index table.
 */
@Component
public class SyncCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SyncCoordinator.class);
    private static final int RECENT_CHANGES_LIMIT = 10;
    private static final String SERVICE_TITLE_PREFIX = "Service: ";
    private static final String DATA_STORE_INVENTORY_TITLE = "Inventory: Data Stores";
    private static final String EXTERNAL_DEP_INVENTORY_TITLE = "Inventory: External Dependencies";

    private final ServiceRepository serviceRepository;
    private final ServiceRelationshipsRepository relationships;
    private final ServicePageRenderer renderer;
    private final LandingPageRenderer landingRenderer;
    private final DataStoreInventoryRenderer dataStoreInventoryRenderer;
    private final ExternalDependencyInventoryRenderer externalDepInventoryRenderer;
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
        String resolvedSpaceId = resolveSpaceId();
        String landingId = ensureLandingPage(resolvedSpaceId, services);

        Map<UUID, String> servicePageUrls = buildServicePageUrls(services);

        int successes = 0;
        List<SyncFailure> failures = new ArrayList<>();
        for (Service s : services) {
            try {
                syncOneInternal(s, resolvedSpaceId, landingId, servicePageUrls);
                successes++;
                // Refresh the URL map with the (possibly newly-created) page.
                if (s.getConfluencePageId() != null) {
                    servicePageUrls.put(s.getId(), pageUrlFor(s.getConfluencePageId()));
                }
            } catch (Exception e) {
                log.warn("Sync failed for service {} ({}): {}", s.getName(), s.getId(), e.getMessage());
                failures.add(new SyncFailure(s.getId(), s.getName(), e.getMessage()));
            }
        }

        // Refresh the landing page + the two inventory pages with the
        // up-to-date service list and per-resource usage data. All three are
        // best-effort — failures here are logged but don't fail the sync.
        try {
            updateLandingPage(landingId, services, servicePageUrls);
        } catch (Exception e) {
            log.warn("Landing page refresh failed: {}", e.getMessage());
        }
        try {
            updateDataStoreInventoryPage(resolvedSpaceId, landingId, servicePageUrls);
        } catch (Exception e) {
            log.warn("Data-store inventory refresh failed: {}", e.getMessage());
        }
        try {
            updateExternalDepInventoryPage(resolvedSpaceId, landingId, servicePageUrls);
        } catch (Exception e) {
            log.warn("External-dependency inventory refresh failed: {}", e.getMessage());
        }

        return new SyncResult(successes, failures.size(), failures);
    }

    /** Sync one service by ID. */
    public SyncResult syncOne(UUID serviceId) {
        Service s = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("No service with id " + serviceId));
        try {
            String resolvedSpaceId = resolveSpaceId();
            List<Service> allServices = serviceRepository.findAll();
            String landingId = ensureLandingPage(resolvedSpaceId, allServices);
            Map<UUID, String> servicePageUrls = buildServicePageUrls(allServices);
            syncOneInternal(s, resolvedSpaceId, landingId, servicePageUrls);
            // Refresh landing + inventory pages so the service-index and
            // per-resource usage tables reflect any changes.
            if (s.getConfluencePageId() != null) {
                servicePageUrls.put(s.getId(), pageUrlFor(s.getConfluencePageId()));
            }
            try {
                updateLandingPage(landingId, allServices, servicePageUrls);
            } catch (Exception e) {
                log.warn("Landing page refresh failed: {}", e.getMessage());
            }
            try {
                updateDataStoreInventoryPage(resolvedSpaceId, landingId, servicePageUrls);
            } catch (Exception e) {
                log.warn("Data-store inventory refresh failed: {}", e.getMessage());
            }
            try {
                updateExternalDepInventoryPage(resolvedSpaceId, landingId, servicePageUrls);
            } catch (Exception e) {
                log.warn("External-dependency inventory refresh failed: {}", e.getMessage());
            }
            return new SyncResult(1, 0, List.of());
        } catch (Exception e) {
            log.warn("Sync failed for service {} ({}): {}", s.getName(), s.getId(), e.getMessage());
            return new SyncResult(0, 1, List.of(new SyncFailure(s.getId(), s.getName(), e.getMessage())));
        }
    }

    // ---- Internals ---------------------------------------------------------

    private void syncOneInternal(Service service, String resolvedSpaceId, String landingId,
                                 Map<UUID, String> servicePageUrls) {
        ServicePageContext ctx = buildContext(service, servicePageUrls);
        String body = renderer.render(ctx);
        String title = SERVICE_TITLE_PREFIX + service.getName();

        if (service.getConfluencePageId() == null || service.getConfluencePageId().isBlank()) {
            String pageId = confluenceClient.createPage(resolvedSpaceId, title, body, landingId);
            service.setConfluencePageId(pageId);
        } else {
            try {
                confluenceClient.updatePage(service.getConfluencePageId(), title, body, landingId);
            } catch (ConfluencePageNotFoundException e) {
                log.info("Confluence page {} for service {} no longer exists; creating fresh page.",
                        service.getConfluencePageId(), service.getName());
                String newPageId = confluenceClient.createPage(resolvedSpaceId, title, body, landingId);
                service.setConfluencePageId(newPageId);
            }
        }
        service.setLastSyncedToConfluence(OffsetDateTime.now());
        serviceRepository.save(service);
    }

    private ServicePageContext buildContext(Service service, Map<UUID, String> servicePageUrls) {
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
                servicePageUrls);
    }

    /**
     * Look up the landing page by title; create it if missing. Body is
     * regenerated post-sync so the service-index table reflects current
     * state — here we only ensure existence.
     */
    private String ensureLandingPage(String resolvedSpaceId, List<Service> services) {
        return confluenceClient.findPageByTitle(resolvedSpaceId, landingPageTitle)
                .orElseGet(() -> {
                    String body = landingRenderer.render(services, Map.of(), OffsetDateTime.now());
                    log.info("Creating landing page '{}' in space {}", landingPageTitle, resolvedSpaceId);
                    return confluenceClient.createPage(resolvedSpaceId, landingPageTitle, body, null);
                });
    }

    private void updateLandingPage(String landingId, List<Service> services,
                                   Map<UUID, String> servicePageUrls) {
        String body = landingRenderer.render(services, servicePageUrls, OffsetDateTime.now());
        confluenceClient.updatePage(landingId, landingPageTitle, body, null);
    }

    /**
     * Lookup-or-create the data-store inventory page (parented under the
     * landing page), then PUT a fresh body containing every data store and
     * its using services.
     */
    private void updateDataStoreInventoryPage(String resolvedSpaceId, String landingId,
                                              Map<UUID, String> servicePageUrls) {
        String body = dataStoreInventoryRenderer.render(
                relationships.findAllDataStoresWithUsages(),
                servicePageUrls,
                OffsetDateTime.now());
        String pageId = confluenceClient.findPageByTitle(resolvedSpaceId, DATA_STORE_INVENTORY_TITLE)
                .orElse(null);
        if (pageId == null) {
            log.info("Creating data-store inventory page in space {}", resolvedSpaceId);
            confluenceClient.createPage(resolvedSpaceId, DATA_STORE_INVENTORY_TITLE, body, landingId);
        } else {
            confluenceClient.updatePage(pageId, DATA_STORE_INVENTORY_TITLE, body, landingId);
        }
    }

    /**
     * Lookup-or-create the external-dependency inventory page (parented under
     * the landing page), then PUT a fresh body containing every external dep
     * and its using services.
     */
    private void updateExternalDepInventoryPage(String resolvedSpaceId, String landingId,
                                                Map<UUID, String> servicePageUrls) {
        String body = externalDepInventoryRenderer.render(
                relationships.findAllExternalDependenciesWithUsages(),
                servicePageUrls,
                OffsetDateTime.now());
        String pageId = confluenceClient.findPageByTitle(resolvedSpaceId, EXTERNAL_DEP_INVENTORY_TITLE)
                .orElse(null);
        if (pageId == null) {
            log.info("Creating external-dependency inventory page in space {}", resolvedSpaceId);
            confluenceClient.createPage(resolvedSpaceId, EXTERNAL_DEP_INVENTORY_TITLE, body, landingId);
        } else {
            confluenceClient.updatePage(pageId, EXTERNAL_DEP_INVENTORY_TITLE, body, landingId);
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
}
