package com.atlas.services;

import java.util.UUID;

public record ExternalDependencyUsage(
        UUID externalDependencyId,
        String name,
        String url,
        String description,
        String source) {
}
