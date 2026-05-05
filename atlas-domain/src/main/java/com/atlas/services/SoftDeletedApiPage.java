package com.atlas.services;

import java.util.UUID;

/**
 * Read-only shape for the sync coordinator's per-endpoint cleanup pass: a
 * soft-deleted api row that still carries a wiki ref on at least one sink
 * (Confluence page id and/or local Markdown vault path). The coordinator
 * iterates over both refs and dispatches deletes to the matching sink.
 * Either ref may be {@code null} when only one sink published the page.
 * The service name is joined in so the cleanup logs are human-readable.
 */
public record SoftDeletedApiPage(
        UUID apiId,
        String method,
        String path,
        String confluencePageId,
        String localMarkdownPath,
        UUID serviceId,
        String serviceName) {
}
