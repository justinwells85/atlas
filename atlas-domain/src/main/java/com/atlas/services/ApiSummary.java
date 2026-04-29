package com.atlas.services;

import java.util.UUID;

public record ApiSummary(
        UUID id,
        String path,
        String method,
        String authMethod,
        String description,
        String source) {
}
