package com.atlas.services;

import java.util.UUID;

/**
 * One {@code @Enable*}-prefixed annotation use-site observation belonging
 * to a service (Phase 5.9 M3 — configuration extraction). Append-only
 * model: live view returns latest observation per
 * {@code (service_id, module_path, enclosing_class, annotation_simple_name)}
 * where {@code presence='present'}.
 *
 * <p>{@code annotationSimpleName} is the simple class name as written on
 * the use-site (e.g. {@code "EnableScheduling"}). {@code annotationFqn}
 * is the fully-qualified name resolved from the source file's import
 * statements; falls back to the simple name when the annotation is not
 * imported (wildcard or same-package usage).
 *
 * <p>{@code javadocFirstSentence} is captured opportunistically when the
 * annotation's source is reachable in the same-module source tree
 * (M3 limit — cross-module same-repo resolution is deferred). {@code null}
 * for Spring's built-in {@code @Enable*} annotations and for org-internal
 * annotations whose definitions live in another module.
 */
public record ServiceEnableAnnotation(
        UUID id,
        UUID serviceId,
        String modulePath,
        String enclosingClass,
        String annotationSimpleName,
        String annotationFqn,
        String javadocFirstSentence,
        String source) {
}
