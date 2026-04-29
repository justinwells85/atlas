package com.atlas.codesync;

import java.util.List;

/**
 * Parse an OpenAPI 3.x spec (JSON or YAML) into a list of {@link EndpointRecord}.
 * Wraps the {@code swagger-parser} library so the rest of code-sync depends on
 * a small Atlas-shaped surface rather than the parser's domain model.
 */
public interface OpenApiParser {
    List<EndpointRecord> parse(String specText);
}
