package com.atlas.confluence;

import com.atlas.services.ChangeEntry;
import com.atlas.services.DatabaseUsage;
import com.atlas.services.ExternalDependencyUsage;
import com.atlas.services.Service;
import com.atlas.services.ServiceDependencyEdge;

import java.util.List;

/**
 * Everything {@link ServicePageRenderer} needs to render one Confluence page,
 * pre-loaded by the sync coordinator. The renderer is pure — no I/O — so the
 * full input shape is captured here.
 */
public record ServicePageContext(
        Service service,
        List<ApiPresentation> apis,
        List<ServiceDependencyEdge> upstreamServices,
        List<ServiceDependencyEdge> downstreamServices,
        List<DatabaseUsage> databases,
        List<ExternalDependencyUsage> externalDependencies,
        List<ChangeEntry> recentChanges) {
}
