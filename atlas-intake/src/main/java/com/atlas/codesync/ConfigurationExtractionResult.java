package com.atlas.codesync;

import java.util.List;

/**
 * Combined output of {@code JavaConfigurationExtractor} for one Java source
 * file (Phase 5.9 M2): all {@code @Value} injection sites plus all
 * {@code @ConfigurationProperties}-annotated types in the file.
 */
public record ConfigurationExtractionResult(
        List<ValueInjectionRecord> valueInjections,
        List<ConfigurationPropertiesTypeRecord> configurationPropertiesTypes) {

    public static ConfigurationExtractionResult empty() {
        return new ConfigurationExtractionResult(List.of(), List.of());
    }
}
