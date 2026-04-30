package com.atlas.services;

import java.util.UUID;

/**
 * One Spring-stereotype class observation belonging to a service
 * (Phase 5.6 M3 — L5 drill-down). Append-only model: live view returns
 * latest observation per
 * {@code (service_id, module_path, package_name, class_name, source)}
 * where {@code presence='present'}.
 *
 * <p>{@code stereotype} is one of {@code RestController}, {@code Controller},
 * {@code Service}, {@code Repository}, {@code Component}, {@code Configuration}
 * — the narrow Spring set per Phase 5.6 open-question resolution (Option A).
 *
 * <p>{@code publicMethods} is a JSON array of
 * {@code [{name, signature, javadoc}, ...]} captured from the AST.
 * The renderer parses this back at render time.
 */
public record ServiceBean(
        UUID id,
        UUID serviceId,
        String modulePath,
        String packageName,
        String className,
        String stereotype,
        String classJavadocSummary,
        String publicMethods,
        String source,
        String confluencePageId) {
}
