package com.atlas.codesync;

import java.util.List;

/**
 * One {@code @ConfigurationProperties}-annotated type extracted from a Java
 * source file (Phase 5.9 M2). The extractor emits these directly; the
 * coordinator translates each into a
 * {@code service_configuration_properties_types} observation row.
 *
 * <p>{@code prefix} is the annotation's value (string member or named
 * {@code prefix=} member), or {@code ""} when absent. {@code typeKind} is
 * {@code 'class'} or {@code 'record'}. {@code components} is the type's
 * declared property surface — record components for a record, non-static
 * fields for a class.
 */
public record ConfigurationPropertiesTypeRecord(
        String enclosingClass,
        String prefix,
        String typeKind,
        List<Component> components) {

    public record Component(String name, String declaredType) {}
}
