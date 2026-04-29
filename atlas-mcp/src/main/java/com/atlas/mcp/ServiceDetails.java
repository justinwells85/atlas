package com.atlas.mcp;

import com.atlas.services.ApiSummary;
import com.atlas.services.DatabaseUsage;
import com.atlas.services.ExternalDependencyUsage;
import com.atlas.services.ServiceDependencyEdge;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full denormalised projection of a service: every column on the {@code services}
 * row plus every joined relationship table read. Phase 3 read-only — Phase 3.5
 * will populate the relationship rows that are empty arrays here today.
 */
public record ServiceDetails(
        UUID id,
        String name,
        String description,
        String ownerTeam,
        String status,
        String language,
        String framework,
        String repoUrl,
        String deployment,
        String supportContact,
        String sla,
        String notes,
        Map<String, Object> metadata,
        String confluencePageId,
        OffsetDateTime lastSyncedToConfluence,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt,
        List<ApiSummary> apis,
        List<ServiceDependencyEdge> upstreamDependencies,
        List<ServiceDependencyEdge> downstreamDependencies,
        List<DatabaseUsage> databases,
        List<ExternalDependencyUsage> externalDependencies) {
}
