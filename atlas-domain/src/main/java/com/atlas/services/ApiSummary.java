package com.atlas.services;

import java.util.UUID;

public record ApiSummary(
        UUID id,
        String path,
        String method,
        String authMethod,
        String description,
        String source,
        String confluencePageId,
        String openapiSnapshot,
        String localMarkdownPath) {

    /** Pre-V25 convenience — defaults the local-markdown ref to null. */
    public ApiSummary(UUID id, String path, String method, String authMethod,
                      String description, String source, String confluencePageId,
                      String openapiSnapshot) {
        this(id, path, method, authMethod, description, source,
                confluencePageId, openapiSnapshot, null);
    }

    /** Pre-V21 convenience — defaults snapshot AND local-markdown ref to null. */
    public ApiSummary(UUID id, String path, String method, String authMethod,
                      String description, String source, String confluencePageId) {
        this(id, path, method, authMethod, description, source,
                confluencePageId, null, null);
    }
}
