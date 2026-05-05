package com.atlas.codesync;

/**
 * One {@code @Enable*}-prefixed annotation use-site extracted from a Java
 * source file (Phase 5.9 M3). The extractor emits these directly; the
 * coordinator translates each into a {@code service_enable_annotations}
 * observation row, fills in {@code javadocFirstSentence} when the
 * annotation's source is reachable in the same-module source tree, and
 * persists the result.
 *
 * <p>{@code annotationSimpleName} is the simple name as written on the
 * use-site. {@code annotationFqn} is the import-resolved fully-qualified
 * name; falls back to the simple name when the annotation is not imported
 * (wildcard import, same-package usage). {@code enclosingClass} is the FQN
 * of the {@code @Configuration} / {@code @SpringBootApplication} class
 * that declares the annotation.
 */
public record EnableAnnotationRecord(
        String enclosingClass,
        String annotationSimpleName,
        String annotationFqn) {
}
