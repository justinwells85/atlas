package com.atlas.services;

import java.util.UUID;

/**
 * One row from {@code service_dependencies}, denormalised with the names of
 * both endpoints. Used for both upstream and downstream views — the caller
 * decides which side is "this" service.
 */
public record ServiceDependencyEdge(
        UUID upstreamServiceId,
        String upstreamServiceName,
        UUID downstreamServiceId,
        String downstreamServiceName,
        String description) {
}
