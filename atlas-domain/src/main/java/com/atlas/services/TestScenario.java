package com.atlas.services;

import java.util.UUID;

/**
 * Read shape for one row in {@code service_test_scenarios}: a single
 * {@code @Test}-annotated method on a class under the service's
 * {@code src/test/java} tree (M3 — code-driven docs).
 *
 * {@code packageName} is the test class's Java package (empty string for
 * the default package). {@code source} mirrors the apis-row provenance
 * vocabulary; only {@code "tests"} is meaningful today.
 */
public record TestScenario(
        UUID id,
        UUID serviceId,
        String packageName,
        String className,
        String methodName,
        String source) {
}
