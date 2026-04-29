package com.atlas.services;

import java.util.UUID;

/**
 * Read-only shape for the sync coordinator's per-endpoint cleanup pass: a
 * soft-deleted api row that still carries a Confluence page id. The service
 * name is joined in so the cleanup logs are human-readable.
 */
public record SoftDeletedApiPage(
        UUID apiId,
        String method,
        String path,
        String confluencePageId,
        UUID serviceId,
        String serviceName) {
}
