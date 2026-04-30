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
        String openapiSnapshot) {

    /** Construct without an openapi snapshot — convenience for intake-source rows and pre-V21 callers. */
    public ApiSummary(UUID id, String path, String method, String authMethod,
                      String description, String source, String confluencePageId) {
        this(id, path, method, authMethod, description, source, confluencePageId, null);
    }
}
