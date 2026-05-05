package com.atlas.codesync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function parser for Spring Boot configuration files (Phase 5.9 M1).
 * Inputs: file content + filename. Outputs: list of {@link PropertyEntry}
 * tuples. Filename determines format and profile; content drives keys / values.
 *
 * <p>Supported filenames (any other returns an empty list — the coordinator
 * is responsible for filtering before calling):
 * <ul>
 *   <li>{@code application.properties}, {@code application-{profile}.properties}</li>
 *   <li>{@code application.yml}, {@code application-{profile}.yml}</li>
 *   <li>{@code application.yaml}, {@code application-{profile}.yaml}</li>
 * </ul>
 *
 * <p>Profile rule: "default" for plain {@code application.{ext}}; the
 * captured suffix otherwise. YAML nested maps flatten to dot-joined keys
 * per Spring Boot conventions; multi-document YAML splits on lines that
 * are exactly {@code ---}.
 */
class PropertiesFileParserTest {

    private PropertiesFileParser parser;

    @BeforeEach
    void setUp() {
        parser = new PropertiesFileParser();
    }

    // --- application.properties ----------------------------------------------

    @Test
    void whenPropertiesFileHasKeyValuePairs_thenAllAreExtractedAsDefaultProfile() {
        String content = """
                spring.datasource.url=jdbc:postgresql://localhost/atlas
                spring.datasource.username=atlas
                atlas.feature.flag=true
                """;

        List<PropertyEntry> entries = parser.parse(content, "application.properties");

        assertThat(entries).hasSize(3);
        assertThat(entries).extracting(PropertyEntry::keyPath)
                .containsExactlyInAnyOrder(
                        "spring.datasource.url",
                        "spring.datasource.username",
                        "atlas.feature.flag");
        assertThat(entries).extracting(PropertyEntry::profile)
                .containsOnly("default");
    }

    @Test
    void whenPropertiesFileHasCommentsAndBlankLines_thenTheyAreIgnored() {
        String content = """
                # This is a comment
                ! Bang comment
                spring.application.name=atlas

                # another comment
                atlas.port=8080
                """;

        List<PropertyEntry> entries = parser.parse(content, "application.properties");

        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(PropertyEntry::keyPath)
                .containsExactlyInAnyOrder("spring.application.name", "atlas.port");
    }

    @Test
    void whenPropertiesFileHasEmptyValue_thenItIsCapturedAsEmptyString() {
        String content = "atlas.empty.key=\n";

        List<PropertyEntry> entries = parser.parse(content, "application.properties");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).keyPath()).isEqualTo("atlas.empty.key");
        assertThat(entries.get(0).value()).isEmpty();
    }

    @Test
    void whenPropertiesFilenameCarriesProfileSuffix_thenProfileIsExtracted() {
        String content = "atlas.api.url=http://prod\n";

        List<PropertyEntry> entries = parser.parse(content, "application-prod.properties");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).profile()).isEqualTo("prod");
    }

    @Test
    void whenPropertiesFilenameCarriesMultiSegmentProfile_thenWholeSuffixIsTheProfile() {
        // Spring Boot accepts compound profiles like "prod-eu" via the suffix.
        String content = "atlas.api.url=http://prod-eu\n";

        List<PropertyEntry> entries = parser.parse(content, "application-prod-eu.properties");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).profile()).isEqualTo("prod-eu");
    }

    // --- application.yml -----------------------------------------------------

    @Test
    void whenYamlFileIsFlat_thenKeysAreCapturedAsDefaultProfile() {
        String content = """
                spring.application.name: atlas
                atlas.port: 8080
                """;

        List<PropertyEntry> entries = parser.parse(content, "application.yml");

        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(PropertyEntry::keyPath)
                .containsExactlyInAnyOrder("spring.application.name", "atlas.port");
        assertThat(entries).extracting(PropertyEntry::profile)
                .containsOnly("default");
    }

    @Test
    void whenYamlFileIsNested_thenKeysAreFlattenedWithDotJoin() {
        String content = """
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost/atlas
                    username: atlas
                  jpa:
                    show-sql: true
                """;

        List<PropertyEntry> entries = parser.parse(content, "application.yml");

        assertThat(entries).extracting(PropertyEntry::keyPath)
                .containsExactlyInAnyOrder(
                        "spring.datasource.url",
                        "spring.datasource.username",
                        "spring.jpa.show-sql");
    }

    @Test
    void whenYamlValueIsScalar_thenItIsCapturedAsItsStringForm() {
        String content = """
                atlas.port: 8080
                atlas.enabled: true
                atlas.ratio: 0.75
                atlas.label: hello
                """;

        List<PropertyEntry> entries = parser.parse(content, "application.yml");

        assertThat(entries).extracting(PropertyEntry::keyPath, PropertyEntry::value)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple("atlas.port", "8080"),
                        org.assertj.core.api.Assertions.tuple("atlas.enabled", "true"),
                        org.assertj.core.api.Assertions.tuple("atlas.ratio", "0.75"),
                        org.assertj.core.api.Assertions.tuple("atlas.label", "hello"));
    }

    @Test
    void whenYamlValueIsExplicitlyNull_thenItIsCapturedAsEmptyString() {
        String content = """
                atlas.empty: ~
                atlas.also-empty:
                """;

        List<PropertyEntry> entries = parser.parse(content, "application.yml");

        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(e -> assertThat(e.value()).isEmpty());
    }

    @Test
    void whenYamlIsMultiDocument_thenAllDocumentsAreFlattenedIntoOneList() {
        // Spring Boot reads multi-document YAML as a sequence of profile slices.
        // The parser surfaces every key from every document.
        String content = """
                spring:
                  application:
                    name: atlas
                ---
                atlas.port: 8080
                """;

        List<PropertyEntry> entries = parser.parse(content, "application.yml");

        assertThat(entries).extracting(PropertyEntry::keyPath)
                .containsExactlyInAnyOrder("spring.application.name", "atlas.port");
    }

    @Test
    void whenYamlFilenameCarriesProfileSuffix_thenProfileIsExtracted() {
        String content = "atlas.api.url: http://staging\n";

        List<PropertyEntry> entries = parser.parse(content, "application-staging.yml");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).profile()).isEqualTo("staging");
    }

    @Test
    void whenYamlExtensionIsYaml_thenItIsParsedTheSameAsYml() {
        String content = "atlas.port: 8080\n";

        List<PropertyEntry> entries = parser.parse(content, "application.yaml");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).keyPath()).isEqualTo("atlas.port");
    }

    // --- unrecognised input --------------------------------------------------

    @Test
    void whenFilenameIsNotRecognised_thenEmptyListIsReturned() {
        List<PropertyEntry> entries = parser.parse("anything=goes\n", "logback.xml");

        assertThat(entries).isEmpty();
    }

    @Test
    void whenContentIsEmpty_thenEmptyListIsReturned() {
        List<PropertyEntry> entries = parser.parse("", "application.properties");

        assertThat(entries).isEmpty();
    }

    @Test
    void whenYamlContentIsEmpty_thenEmptyListIsReturned() {
        List<PropertyEntry> entries = parser.parse("", "application.yml");

        assertThat(entries).isEmpty();
    }
}
