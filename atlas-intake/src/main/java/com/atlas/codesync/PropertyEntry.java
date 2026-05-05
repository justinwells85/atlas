package com.atlas.codesync;

/**
 * One parsed property entry from a Spring Boot configuration file (Phase 5.9
 * M1). The parser emits these directly; the coordinator translates each into
 * a {@code service_config_properties} observation row.
 *
 * <p>{@code keyPath} is the dot-joined Spring-Boot key (YAML nesting flattens
 * to dots). {@code value} is the raw string from the file ({@code ""} for
 * empty / null scalars). {@code profile} is the source-file's profile suffix
 * or {@code "default"} for plain {@code application.properties} /
 * {@code application.yml}.
 */
public record PropertyEntry(String keyPath, String value, String profile) {
}
