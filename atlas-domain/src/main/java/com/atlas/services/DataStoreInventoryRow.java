package com.atlas.services;

import java.util.UUID;

/**
 * One flat row joining a data store with one of its using services. The
 * inventory renderer groups rows by data store to produce the per-store
 * usage list. Service fields are nullable when a data store has no users
 * yet — the LEFT JOIN returns the data-store row with null service columns.
 */
public record DataStoreInventoryRow(
        UUID dataStoreId,
        String dataStoreName,
        String engine,
        UUID serviceId,
        String serviceName,
        Boolean isOwner,
        String description) {
}
