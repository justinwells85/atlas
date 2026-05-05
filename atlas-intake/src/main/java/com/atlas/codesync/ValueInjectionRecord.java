package com.atlas.codesync;

/**
 * One {@code @Value} injection-site extracted from a Java source file
 * (Phase 5.9 M2). The extractor emits these directly; the coordinator
 * translates each into a {@code service_value_injections} observation row.
 *
 * <p>{@code rawSpel} carries the verbatim SpEL expression (including the
 * dollar-brace wrapper) as written in the source. {@code keyPath} is the
 * resolved key after stripping the optional default suffix; empty string
 * when the SpEL does not match the simple {@code dollar-brace + key + (:default)}
 * shape (malformed-SpEL tolerance — surface what we have, do not throw).
 * {@code defaultValue} is the string after the colon, or {@code null} when
 * absent.
 */
public record ValueInjectionRecord(
        String enclosingClass,
        String memberName,
        String memberKind,
        String rawSpel,
        String keyPath,
        String defaultValue) {
}
