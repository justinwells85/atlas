package com.atlas.confluence;

import com.atlas.services.ChangeEntry;
import com.atlas.services.DatabaseUsage;
import com.atlas.services.ExternalDependencyUsage;
import com.atlas.services.Service;
import com.atlas.services.ServiceDependencyEdge;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything {@link ServicePageRenderer} needs to render one Confluence page,
 * pre-loaded by the sync coordinator. The renderer is pure — no I/O — so the
 * full input shape is captured here.
 *
 * {@code serviceConfluencePageUrls} maps each known service's UUID to its
 * Confluence page URL. The renderer uses this to turn upstream/downstream and
 * API-consumer service references into hyperlinks. A service that has been
 * registered in Atlas but never synced to Confluence has a null entry; the
 * renderer falls back to plain text in that case.
 */
public record ServicePageContext(
        Service service,
        List<ApiPresentation> apis,
        List<ServiceDependencyEdge> upstreamServices,
        List<ServiceDependencyEdge> downstreamServices,
        List<DatabaseUsage> databases,
        List<ExternalDependencyUsage> externalDependencies,
        List<ChangeEntry> recentChanges,
        Map<UUID, String> serviceConfluencePageUrls,
        InventoryPageUrls inventoryPageUrls) {
}
