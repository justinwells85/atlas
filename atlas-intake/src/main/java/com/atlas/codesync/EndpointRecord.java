package com.atlas.codesync;

/**
 * A single endpoint parsed from an OpenAPI spec. The minimum shape needed for
 * M1 (provenance + DB write). Per-endpoint Confluence-page rendering (M2) will
 * extend this with parameters, request/response schemas, and examples.
 */
public record EndpointRecord(
        String method,
        String path,
        String description,
        String authMethod) {
}
