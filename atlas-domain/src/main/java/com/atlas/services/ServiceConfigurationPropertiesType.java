package com.atlas.services;

import java.util.UUID;

/**
 * One {@code @ConfigurationProperties(prefix=...)} type observation belonging
 * to a service (Phase 5.9 M2 — configuration extraction). Append-only model:
 * live view returns latest observation per
 * {@code (service_id, module_path, enclosing_class)} where
 * {@code presence='present'}.
 *
 * <p>{@code prefix} is the annotation's prefix value, or {@code ""} for
 * prefix-less {@code @ConfigurationProperties} (the annotation accepts no prefix).
 *
 * <p>{@code typeKind} is one of {@code 'class'}, {@code 'record'},
 * {@code 'interface'} — Spring Boot does not bind to interfaces today but
 * the column shape leaves room.
 *
 * <p>{@code components} is a JSON-array string carrying the type's declared
 * components — record components for a record; bean property fields for a
 * class. Shape: {@code [{"name":"<name>","declaredType":"<type>"}, ...]}.
 * The renderer parses this back at render time.
 */
public record ServiceConfigurationPropertiesType(
        UUID id,
        UUID serviceId,
        String modulePath,
        String enclosingClass,
        String prefix,
        String typeKind,
        String components,
        String source) {
}
