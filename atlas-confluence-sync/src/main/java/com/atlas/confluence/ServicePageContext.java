package com.atlas.confluence;

import com.atlas.services.ChangeEntry;
import com.atlas.services.DatabaseUsage;
import com.atlas.services.ExternalDependencyUsage;
import com.atlas.services.Service;
import com.atlas.services.ServiceDependencyEdge;
import com.atlas.services.ServiceMetadata;
import com.atlas.services.ServiceModule;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything {@link ServicePageRenderer} needs to render one Confluence page,
 * pre-loaded by the sync coordinator. The renderer is pure — no I/O — so the
 * full input shape is captured here.
 *
 * <p>{@code serviceConfluencePageUrls} maps each known service's UUID to its
 * Confluence page URL. The renderer uses this to turn upstream/downstream and
 * API-consumer service references into hyperlinks. A service that has been
 * registered in Atlas but never synced to Confluence has a null entry; the
 * renderer falls back to plain text in that case.
 *
 * <p>{@code serviceMetadata} carries pom-source (and eventually intake-source)
 * key/value observations for {@code language}, {@code framework},
 * {@code build_tool}, etc. The renderer prefers these over the legacy
 * {@code services.language} / {@code services.framework} entity columns when
 * present (M4.5).
 *
 * <p>The trailing fields ({@code modules}, {@code modulePageUrlsByPath},
 * {@code beansPageUrl}, {@code testsPageUrl}) feed Section 8 "Internals" —
 * the L1→L5 drill-down cross-reference block introduced in Phase 5.6 M4.
 * Each is independently nullable/empty so the section can render any subset
 * with thin notes for the missing layers.
 */
public record ServicePageContext(
        Service service,
        List<ApiPresentation> apis,
        List<ServiceDependencyEdge> upstreamServices,
        List<ServiceDependencyEdge> downstreamServices,
        List<DatabaseUsage> databases,
        List<ExternalDependencyUsage> externalDependencies,
        List<ServiceMetadata> serviceMetadata,
        List<ChangeEntry> recentChanges,
        Map<UUID, String> serviceConfluencePageUrls,
        InventoryPageUrls inventoryPageUrls,
        List<ServiceModule> modules,
        Map<String, String> modulePageUrlsByPath,
        String beansPageUrl,
        String testsPageUrl,
        String configurationPageUrl) {

    /**
     * Legacy 10-arg constructor — kept so pre-Phase-5.6-M4 call sites compile
     * unchanged. Internals fields default to empty/null, which the renderer
     * treats as "this layer hasn't been observed yet" (thin-note rendering).
     */
    public ServicePageContext(
            Service service,
            List<ApiPresentation> apis,
            List<ServiceDependencyEdge> upstreamServices,
            List<ServiceDependencyEdge> downstreamServices,
            List<DatabaseUsage> databases,
            List<ExternalDependencyUsage> externalDependencies,
            List<ServiceMetadata> serviceMetadata,
            List<ChangeEntry> recentChanges,
            Map<UUID, String> serviceConfluencePageUrls,
            InventoryPageUrls inventoryPageUrls) {
        this(service, apis, upstreamServices, downstreamServices, databases, externalDependencies,
                serviceMetadata, recentChanges, serviceConfluencePageUrls, inventoryPageUrls,
                List.of(), Map.of(), null, null, null);
    }

    /**
     * Phase 5.6 M4 14-arg constructor — kept so pre-Phase-5.9-M4 call sites
     * compile unchanged. Configuration field defaults to null (thin-note).
     */
    public ServicePageContext(
            Service service,
            List<ApiPresentation> apis,
            List<ServiceDependencyEdge> upstreamServices,
            List<ServiceDependencyEdge> downstreamServices,
            List<DatabaseUsage> databases,
            List<ExternalDependencyUsage> externalDependencies,
            List<ServiceMetadata> serviceMetadata,
            List<ChangeEntry> recentChanges,
            Map<UUID, String> serviceConfluencePageUrls,
            InventoryPageUrls inventoryPageUrls,
            List<ServiceModule> modules,
            Map<String, String> modulePageUrlsByPath,
            String beansPageUrl,
            String testsPageUrl) {
        this(service, apis, upstreamServices, downstreamServices, databases, externalDependencies,
                serviceMetadata, recentChanges, serviceConfluencePageUrls, inventoryPageUrls,
                modules, modulePageUrlsByPath, beansPageUrl, testsPageUrl, null);
    }
}
