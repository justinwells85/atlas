package com.atlas.services;

import java.util.UUID;

/**
 * One observation of a service's metadata key/value pair (M4 — code-driven
 * docs). Examples: {@code key="language", value="Java"};
 * {@code key="framework", value="Spring Boot 4.0.6"};
 * {@code key="build_tool", value="Maven"}. Append-only model from day
 * one — multiple observations of the same key over time, distinguished by
 * {@code observedAt} and {@code source}. Renderers read the latest
 * observation per key via a window-function "live view" query.
 */
public record ServiceMetadata(
        UUID id,
        UUID serviceId,
        String key,
        String value,
        String source) {
}
