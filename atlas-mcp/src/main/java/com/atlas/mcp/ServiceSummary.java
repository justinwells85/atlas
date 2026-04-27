package com.atlas.mcp;

import java.util.UUID;

/**
 * Compact projection of a service for search/list results. Full details are
 * available via the {@code get_service_details} tool.
 */
public record ServiceSummary(
        UUID id,
        String name,
        String ownerTeam,
        String status) {
}
