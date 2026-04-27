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
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the DB → Confluence sync. Loads each service plus its
 * relationship rows, renders a Confluence storage-format page, and either
 * creates a new page (first sync) or updates an existing one (subsequent
 * syncs), tracking the page ID and last-sync timestamp on the service row.
 *
 * Per-service exceptions are caught and reported in {@link SyncResult} —
 * one bad service does not abort the rest of {@code syncAll()}.
 */
@Component
public class SyncCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SyncCoordinator.class);
    private static final int RECENT_CHANGES_LIMIT = 10;

    private final ServiceRepository serviceRepository;
    private final ServiceRelationshipsRepository relationships;
    private final ServicePageRenderer renderer;
    private final ConfluenceClient confluenceClient;
    private final String spaceKey;

    private volatile String spaceId;

    public SyncCoordinator(
            ServiceRepository serviceRepository,
            ServiceRelationshipsRepository relationships,
            ServicePageRenderer renderer,
            ConfluenceClient confluenceClient,
            @Value("${atlas.confluence.space-key}") String spaceKey) {
        this.serviceRepository = serviceRepository;
        this.relationships = relationships;
        this.renderer = renderer;
        this.confluenceClient = confluenceClient;
        this.spaceKey = spaceKey;
    }

    /** Sync every service in the DB. Per-service errors are isolated. */
    public SyncResult syncAll() {
        List<Service> services = serviceRepository.findAll();
        int successes = 0;
        List<SyncFailure> failures = new ArrayList<>();
        for (Service s : services) {
            try {
                syncOneInternal(s);
                successes++;
            } catch (Exception e) {
                log.warn("Sync failed for service {} ({}): {}", s.getName(), s.getId(), e.getMessage());
                failures.add(new SyncFailure(s.getId(), s.getName(), e.getMessage()));
            }
        }
        return new SyncResult(successes, failures.size(), failures);
    }

    /** Sync one service by ID. */
    public SyncResult syncOne(UUID serviceId) {
        Service s = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("No service with id " + serviceId));
        try {
            syncOneInternal(s);
            return new SyncResult(1, 0, List.of());
        } catch (Exception e) {
            log.warn("Sync failed for service {} ({}): {}", s.getName(), s.getId(), e.getMessage());
            return new SyncResult(0, 1, List.of(new SyncFailure(s.getId(), s.getName(), e.getMessage())));
        }
    }

    private void syncOneInternal(Service service) {
        ServicePageContext ctx = buildContext(service);
        String body = renderer.render(ctx);
        String title = service.getName();

        if (service.getConfluencePageId() == null || service.getConfluencePageId().isBlank()) {
            String pageId = confluenceClient.createPage(resolveSpaceId(), title, body);
            service.setConfluencePageId(pageId);
        } else {
            try {
                confluenceClient.updatePage(service.getConfluencePageId(), title, body);
            } catch (ConfluencePageNotFoundException e) {
                log.info("Confluence page {} for service {} no longer exists; creating fresh page.",
                        service.getConfluencePageId(), service.getName());
                String newPageId = confluenceClient.createPage(resolveSpaceId(), title, body);
                service.setConfluencePageId(newPageId);
            }
        }
        service.setLastSyncedToConfluence(OffsetDateTime.now());
        serviceRepository.save(service);
    }

    private ServicePageContext buildContext(Service service) {
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
        return new ServicePageContext(service, apiPresentations, upstream, downstream, dbs, exts, changes);
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
