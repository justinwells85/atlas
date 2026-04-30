package com.atlas.codesync;

/**
 * A single endpoint parsed from an OpenAPI spec.
 *
 * <p>Pre-Phase-5.6 carried only method/path/description/auth — the minimum
 * shape needed for the M3.5/M4 ingestion path. Phase 5.6 M1 adds
 * {@code openapiSnapshot}: a JSON document carrying the operation's
 * parameters, request body schema, responses, and examples for L3
 * per-endpoint Confluence-page rendering. The snapshot is stored verbatim
 * on the apis row and parsed at render time; Atlas never queries inside it.
 *
 * <p>The snapshot is null for parsers that don't extract schema-level
 * detail (none today, but the interface stays open).
 */
public record EndpointRecord(
        String method,
        String path,
        String description,
        String authMethod,
        String openapiSnapshot) {

    /** Convenience constructor for callers/tests with no snapshot. */
    public EndpointRecord(String method, String path, String description, String authMethod) {
        this(method, path, description, authMethod, null);
    }
}
