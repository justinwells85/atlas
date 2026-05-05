package com.atlas.services;

import java.util.UUID;

/**
 * Cleanup-pass payload (Phase 5.6 M2; Phase 5.8 M3 added Markdown ref):
 * one row per module page whose latest observation is a tombstone
 * ({@code presence='absent'}) and still carries a wiki ref on at least one
 * sink (Confluence page id and/or local Markdown vault path). The sync
 * coordinator iterates over both refs and dispatches deletes to the
 * matching sink, mirroring {@link SoftDeletedApiPage} for the L3 endpoint
 * pages. Either ref may be {@code null} when only one sink published the
 * page.
 */
public record SoftDeletedModulePage(
        UUID moduleObservationId,
        String modulePath,
        String confluencePageId,
        String localMarkdownPath,
        UUID serviceId,
        String serviceName) {
}
