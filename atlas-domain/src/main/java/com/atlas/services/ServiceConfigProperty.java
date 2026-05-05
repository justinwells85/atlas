package com.atlas.services;

import java.util.UUID;

/**
 * One property-key observation belonging to a service (Phase 5.9 M1 —
 * configuration extraction). Append-only model: the live view returns the
 * latest observation per {@code (service_id, key_path, profile, source_file)}
 * where {@code presence='present'}; tombstones live in the same table and
 * are filtered out by the live-view queries.
 *
 * <p>{@code keyPath} is the dot-joined Spring-Boot property key
 * (e.g. {@code spring.datasource.url}). YAML nested maps flatten on dot-join
 * before insert.
 *
 * <p>{@code profile} is the source-file profile suffix
 * ({@code application-prod.properties} → {@code "prod"}) or {@code "default"}
 * for plain {@code application.properties} / {@code application.yml}.
 * Never {@code null}.
 *
 * <p>{@code value} is the raw string from the source file. Empty string for
 * {@code key=} declarations and YAML scalars whose value is {@code null}.
 *
 * <p>{@code sourceFile} is the filename relative to the service's
 * module-path resources root (e.g. {@code application.yml},
 * {@code application-prod.properties}).
 */
public record ServiceConfigProperty(
        UUID id,
        UUID serviceId,
        String keyPath,
        String value,
        String sourceFile,
        String profile,
        String source) {
}
