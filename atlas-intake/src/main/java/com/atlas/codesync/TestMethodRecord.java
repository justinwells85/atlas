package com.atlas.codesync;

/**
 * One {@code @Test}-annotated method extracted from a Java source file.
 * {@code packageName} is the source's package declaration, or empty string
 * for the default package.
 */
public record TestMethodRecord(
        String packageName,
        String className,
        String methodName) {
}
