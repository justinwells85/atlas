package com.atlas.codesync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure AST extractor for {@code @Value} injection sites and
 * {@code @ConfigurationProperties}-annotated types (Phase 5.9 M2). Mirrors
 * {@link JavaBeanExtractor}: source-level only, no compilation, no classpath.
 *
 * <p>The extractor's surface is one method that returns a
 * {@link ConfigurationExtractionResult} carrying both lists — a single AST
 * parse populates both. Tests assert the observable shape: which records
 * appear, what their fields contain, edge cases like default-from-SpEL
 * parsing and malformed-SpEL tolerance.
 */
class JavaConfigurationExtractorTest {

    private JavaConfigurationExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new JavaConfigurationExtractor();
    }

    // --- @Value injection sites ---------------------------------------------

    @Test
    void whenFieldHasValueAnnotationWithDefault_thenInjectionIsCapturedWithKeyPathAndDefault() {
        String source = """
                package com.example;
                import org.springframework.beans.factory.annotation.Value;
                public class MyConfig {
                    @Value("${atlas.api.key:fallback}")
                    private String apiKey;
                }
                """;

        ConfigurationExtractionResult r = extractor.extract(source);

        assertThat(r.valueInjections()).hasSize(1);
        ValueInjectionRecord v = r.valueInjections().get(0);
        assertThat(v.enclosingClass()).isEqualTo("com.example.MyConfig");
        assertThat(v.memberName()).isEqualTo("apiKey");
        assertThat(v.memberKind()).isEqualTo("field");
        assertThat(v.rawSpel()).isEqualTo("${atlas.api.key:fallback}");
        assertThat(v.keyPath()).isEqualTo("atlas.api.key");
        assertThat(v.defaultValue()).isEqualTo("fallback");
    }

    @Test
    void whenFieldHasValueAnnotationWithoutDefault_thenDefaultValueIsNull() {
        String source = """
                package com.example;
                import org.springframework.beans.factory.annotation.Value;
                public class C {
                    @Value("${atlas.url}")
                    private String url;
                }
                """;

        ConfigurationExtractionResult r = extractor.extract(source);

        assertThat(r.valueInjections()).hasSize(1);
        ValueInjectionRecord v = r.valueInjections().get(0);
        assertThat(v.keyPath()).isEqualTo("atlas.url");
        assertThat(v.defaultValue()).isNull();
    }

    @Test
    void whenConstructorHasValueAnnotatedParameter_thenInjectionIsCapturedWithMemberKindConstructorParameter() {
        String source = """
                package com.example;
                import org.springframework.beans.factory.annotation.Value;
                public class C {
                    private final String url;
                    public C(@Value("${atlas.url}") String url) {
                        this.url = url;
                    }
                }
                """;

        ConfigurationExtractionResult r = extractor.extract(source);

        assertThat(r.valueInjections()).hasSize(1);
        ValueInjectionRecord v = r.valueInjections().get(0);
        assertThat(v.memberName()).isEqualTo("url");
        assertThat(v.memberKind()).isEqualTo("constructor-parameter");
        assertThat(v.keyPath()).isEqualTo("atlas.url");
    }

    @Test
    void whenSetterMethodHasValueAnnotatedParameter_thenInjectionIsCapturedWithMemberKindMethodParameter() {
        String source = """
                package com.example;
                import org.springframework.beans.factory.annotation.Value;
                public class C {
                    public void setPort(@Value("${atlas.port:8080}") int port) {}
                }
                """;

        ConfigurationExtractionResult r = extractor.extract(source);

        assertThat(r.valueInjections()).hasSize(1);
        ValueInjectionRecord v = r.valueInjections().get(0);
        assertThat(v.memberName()).isEqualTo("port");
        assertThat(v.memberKind()).isEqualTo("method-parameter");
        assertThat(v.keyPath()).isEqualTo("atlas.port");
        assertThat(v.defaultValue()).isEqualTo("8080");
    }

    @Test
    void whenClassHasMultipleValueAnnotations_thenAllAreCaptured() {
        String source = """
                package com.example;
                import org.springframework.beans.factory.annotation.Value;
                public class C {
                    @Value("${a.b}") private String first;
                    @Value("${c.d:fallback}") private String second;
                    public C(@Value("${e.f}") String e) {}
                }
                """;

        ConfigurationExtractionResult r = extractor.extract(source);

        assertThat(r.valueInjections()).hasSize(3);
        assertThat(r.valueInjections()).extracting(ValueInjectionRecord::keyPath)
                .containsExactlyInAnyOrder("a.b", "c.d", "e.f");
    }

    @Test
    void whenValueAnnotationHasMalformedSpel_thenInjectionIsCapturedWithEmptyKeyPath() {
        // Malformed-SpEL tolerance: surface what we have, do not throw.
        String source = """
                package com.example;
                import org.springframework.beans.factory.annotation.Value;
                public class C {
                    @Value("plain-string-no-dollar-brace")
                    private String thing;
                }
                """;

        ConfigurationExtractionResult r = extractor.extract(source);

        assertThat(r.valueInjections()).hasSize(1);
        ValueInjectionRecord v = r.valueInjections().get(0);
        assertThat(v.rawSpel()).isEqualTo("plain-string-no-dollar-brace");
        assertThat(v.keyPath()).isEmpty();
        assertThat(v.defaultValue()).isNull();
    }

    @Test
    void whenSourceHasNoValueAnnotations_thenValueInjectionsListIsEmpty() {
        String source = """
                package com.example;
                public class Plain {
                    private String thing;
                }
                """;

        ConfigurationExtractionResult r = extractor.extract(source);

        assertThat(r.valueInjections()).isEmpty();
    }

    // --- @ConfigurationProperties types -------------------------------------

    @Test
    void whenClassHasConfigurationPropertiesWithPositionalPrefix_thenItIsCaptured() {
        String source = """
                package com.example;
                import org.springframework.boot.context.properties.ConfigurationProperties;
                @ConfigurationProperties("atlas")
                public class AtlasProperties {
                    private String apiKey;
                    private int port;
                }
                """;

        ConfigurationExtractionResult r = extractor.extract(source);

        assertThat(r.configurationPropertiesTypes()).hasSize(1);
        ConfigurationPropertiesTypeRecord t = r.configurationPropertiesTypes().get(0);
        assertThat(t.enclosingClass()).isEqualTo("com.example.AtlasProperties");
        assertThat(t.prefix()).isEqualTo("atlas");
        assertThat(t.typeKind()).isEqualTo("class");
        assertThat(t.components()).extracting(ConfigurationPropertiesTypeRecord.Component::name)
                .containsExactlyInAnyOrder("apiKey", "port");
        assertThat(t.components()).extracting(ConfigurationPropertiesTypeRecord.Component::declaredType)
                .containsExactlyInAnyOrder("String", "int");
    }

    @Test
    void whenRecordHasConfigurationPropertiesWithNamedPrefix_thenItIsCapturedWithComponents() {
        String source = """
                package com.example;
                import org.springframework.boot.context.properties.ConfigurationProperties;
                @ConfigurationProperties(prefix = "db")
                public record DbProperties(String url, String username, int poolSize) {}
                """;

        ConfigurationExtractionResult r = extractor.extract(source);

        assertThat(r.configurationPropertiesTypes()).hasSize(1);
        ConfigurationPropertiesTypeRecord t = r.configurationPropertiesTypes().get(0);
        assertThat(t.enclosingClass()).isEqualTo("com.example.DbProperties");
        assertThat(t.prefix()).isEqualTo("db");
        assertThat(t.typeKind()).isEqualTo("record");
        assertThat(t.components()).extracting(ConfigurationPropertiesTypeRecord.Component::name)
                .containsExactly("url", "username", "poolSize");
        assertThat(t.components()).extracting(ConfigurationPropertiesTypeRecord.Component::declaredType)
                .containsExactly("String", "String", "int");
    }

    @Test
    void whenConfigurationPropertiesHasNoPrefix_thenPrefixIsEmptyString() {
        String source = """
                package com.example;
                import org.springframework.boot.context.properties.ConfigurationProperties;
                @ConfigurationProperties
                public record TopLevelProperties(String name) {}
                """;

        ConfigurationExtractionResult r = extractor.extract(source);

        assertThat(r.configurationPropertiesTypes()).hasSize(1);
        assertThat(r.configurationPropertiesTypes().get(0).prefix()).isEmpty();
    }

    @Test
    void whenClassHasNoConfigurationProperties_thenTypesListIsEmpty() {
        String source = """
                package com.example;
                public class Plain {
                    private String thing;
                }
                """;

        ConfigurationExtractionResult r = extractor.extract(source);

        assertThat(r.configurationPropertiesTypes()).isEmpty();
    }

    @Test
    void whenClassConfigurationPropertiesIsExcludedFromComponentsByStaticModifier_thenStaticFieldsAreSkipped() {
        // Static fields aren't bean properties; skip them.
        String source = """
                package com.example;
                import org.springframework.boot.context.properties.ConfigurationProperties;
                @ConfigurationProperties("atlas")
                public class AtlasProperties {
                    private static final String CONSTANT = "x";
                    private String apiKey;
                }
                """;

        ConfigurationExtractionResult r = extractor.extract(source);

        assertThat(r.configurationPropertiesTypes()).hasSize(1);
        assertThat(r.configurationPropertiesTypes().get(0).components())
                .extracting(ConfigurationPropertiesTypeRecord.Component::name)
                .containsExactly("apiKey");
    }

    // --- combined / error handling ------------------------------------------

    @Test
    void whenSourceHasBothValueAndConfigurationProperties_thenBothListsArePopulated() {
        String source = """
                package com.example;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.boot.context.properties.ConfigurationProperties;
                @ConfigurationProperties("atlas")
                public class AtlasProperties {
                    private String apiKey;
                    @Value("${atlas.url}")
                    private String url;
                }
                """;

        ConfigurationExtractionResult r = extractor.extract(source);

        assertThat(r.valueInjections()).hasSize(1);
        assertThat(r.configurationPropertiesTypes()).hasSize(1);
    }

    @Test
    void whenSourceIsNotParseable_thenIllegalArgumentExceptionIsThrown() {
        String source = "this is not valid java";

        assertThatThrownBy(() -> extractor.extract(source))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
