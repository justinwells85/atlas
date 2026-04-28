package com.atlas.services;

import java.util.UUID;

/**
 * One flat row joining an external dependency with one of its using services.
 * Service fields are nullable when an external dep has no users yet (LEFT
 * JOIN result).
 */
public record ExternalDependencyInventoryRow(
        UUID externalDependencyId,
        String externalDependencyName,
        String url,
        UUID serviceId,
        String serviceName,
        String description) {
}
