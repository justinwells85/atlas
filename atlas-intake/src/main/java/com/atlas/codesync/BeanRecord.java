package com.atlas.codesync;

import java.util.List;

/**
 * One Spring-stereotype-annotated class extracted from a Java source file
 * (Phase 5.6 M3 — L5 of the drill-down).
 *
 * <p>{@code stereotype} is the simple name of the stereotype annotation
 * found on the class — one of {@code RestController}, {@code Controller},
 * {@code Service}, {@code Repository}, {@code Component},
 * {@code Configuration}.
 *
 * <p>{@code classJavadocSummary} is the first sentence of the class's
 * Javadoc (when present), trimmed.
 *
 * <p>{@code publicMethods} carries one record per declared public method —
 * inherited methods are not enumerated since the AST cannot reach them
 * without a classpath.
 */
public record BeanRecord(
        String packageName,
        String className,
        String stereotype,
        String classJavadocSummary,
        List<MethodRecord> publicMethods) {

    /** One declared public method on a stereotype class. */
    public record MethodRecord(
            String name,
            String signature,
            String javadocSummary) {
    }
}
