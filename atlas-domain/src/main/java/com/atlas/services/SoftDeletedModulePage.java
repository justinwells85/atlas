package com.atlas.services;

import java.util.UUID;

/**
 * Cleanup-pass payload (Phase 5.6 M2): one row per Confluence module page
 * whose latest observation is a tombstone ({@code presence='absent'}) and
 * still carries a non-null {@code confluence_page_id}. The sync coordinator
 * deletes the page and nulls the id, mirroring {@link SoftDeletedApiPage}
 * for the L3 endpoint pages.
 */
public record SoftDeletedModulePage(
        UUID moduleObservationId,
        String modulePath,
        String confluencePageId,
        UUID serviceId,
        String serviceName) {
}
