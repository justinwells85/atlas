package com.atlas.codesync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure AST extractor for {@code @Enable*}-prefixed annotation use-sites
 * (Phase 5.9 M3). Mirrors {@link JavaBeanExtractor} / {@link JavaConfigurationExtractor}:
 * source-level only, no compilation, no classpath. Filter is lexical —
 * top-level classes annotated with {@code @Configuration} or
 * {@code @SpringBootApplication} carry {@code @Enable*}-named annotations;
 * we capture each annotation's simple name plus the FQN resolved via the
 * source file's import statements.
 */
class JavaEnableAnnotationExtractorTest {

    private JavaEnableAnnotationExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new JavaEnableAnnotationExtractor();
    }

    @Test
    void whenConfigurationClassHasEnableAnnotation_thenItIsCapturedWithSimpleNameAndFqn() {
        String source = """
                package com.example;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.annotation.EnableScheduling;
                @Configuration
                @EnableScheduling
                public class AppConfig {}
                """;

        List<EnableAnnotationRecord> records = extractor.extract(source);

        assertThat(records).hasSize(1);
        EnableAnnotationRecord r = records.get(0);
        assertThat(r.enclosingClass()).isEqualTo("com.example.AppConfig");
        assertThat(r.annotationSimpleName()).isEqualTo("EnableScheduling");
        assertThat(r.annotationFqn()).isEqualTo("org.springframework.scheduling.annotation.EnableScheduling");
    }

    @Test
    void whenSpringBootApplicationClassHasEnableAnnotation_thenItIsCaptured() {
        String source = """
                package com.example;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.scheduling.annotation.EnableAsync;
                @SpringBootApplication
                @EnableAsync
                public class App {}
                """;

        List<EnableAnnotationRecord> records = extractor.extract(source);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).annotationSimpleName()).isEqualTo("EnableAsync");
        assertThat(records.get(0).enclosingClass()).isEqualTo("com.example.App");
    }

    @Test
    void whenConfigurationClassHasMultipleEnableAnnotations_thenAllAreCaptured() {
        String source = """
                package com.example;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.annotation.EnableScheduling;
                import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
                import org.springframework.scheduling.annotation.EnableAsync;
                @Configuration
                @EnableScheduling
                @EnableJpaRepositories
                @EnableAsync
                public class AppConfig {}
                """;

        List<EnableAnnotationRecord> records = extractor.extract(source);

        assertThat(records).hasSize(3);
        assertThat(records).extracting(EnableAnnotationRecord::annotationSimpleName)
                .containsExactlyInAnyOrder("EnableScheduling", "EnableJpaRepositories", "EnableAsync");
    }

    @Test
    void whenEnableAnnotationIsOnNonConfigurationClass_thenItIsSkipped() {
        // Spring won't activate @Enable* on a plain class. Skip per the
        // plan's "@Configuration / @SpringBootApplication classes" filter.
        String source = """
                package com.example;
                import org.springframework.scheduling.annotation.EnableScheduling;
                @EnableScheduling
                public class JustAClass {}
                """;

        List<EnableAnnotationRecord> records = extractor.extract(source);

        assertThat(records).isEmpty();
    }

    @Test
    void whenEnableAnnotationIsNotImported_thenFqnIsResolvedToTheSamePackage() {
        // Java's resolution rule: an unqualified, unimported type is
        // resolved against the current package first. The extractor
        // mirrors this — the right default for org-internal @Enable*
        // annotations declared alongside their @Configuration use sites.
        String source = """
                package com.example;
                import org.springframework.context.annotation.Configuration;
                @Configuration
                @EnableMystery
                public class AppConfig {}
                """;

        List<EnableAnnotationRecord> records = extractor.extract(source);

        assertThat(records).hasSize(1);
        EnableAnnotationRecord r = records.get(0);
        assertThat(r.annotationSimpleName()).isEqualTo("EnableMystery");
        assertThat(r.annotationFqn()).isEqualTo("com.example.EnableMystery");
    }

    @Test
    void whenSourceUsesFullyQualifiedAnnotationName_thenFqnIsTakenFromTheUseSite() {
        // Edge case: @org.springframework.scheduling.annotation.EnableScheduling
        // is rare but legal. The simple name is still "EnableScheduling".
        String source = """
                package com.example;
                import org.springframework.context.annotation.Configuration;
                @Configuration
                @org.springframework.scheduling.annotation.EnableScheduling
                public class AppConfig {}
                """;

        List<EnableAnnotationRecord> records = extractor.extract(source);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).annotationSimpleName()).isEqualTo("EnableScheduling");
        assertThat(records.get(0).annotationFqn())
                .isEqualTo("org.springframework.scheduling.annotation.EnableScheduling");
    }

    @Test
    void whenConfigurationClassHasNoEnableAnnotations_thenEmptyListIsReturned() {
        String source = """
                package com.example;
                import org.springframework.context.annotation.Configuration;
                @Configuration
                public class AppConfig {
                    // No @Enable* annotations
                }
                """;

        List<EnableAnnotationRecord> records = extractor.extract(source);

        assertThat(records).isEmpty();
    }

    @Test
    void whenAnnotationStartsWithEnableButIsAttachedToInnerClass_thenItIsSkipped() {
        // Top-level only — nested @Configuration classes aren't a typical
        // pattern and we follow the JavaBeanExtractor precedent.
        String source = """
                package com.example;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.annotation.EnableScheduling;
                @Configuration
                public class AppConfig {
                    @Configuration
                    @EnableScheduling
                    static class Inner {}
                }
                """;

        List<EnableAnnotationRecord> records = extractor.extract(source);

        assertThat(records).isEmpty();
    }

    @Test
    void whenAnnotationSimpleNameDoesNotStartWithEnable_thenItIsSkipped() {
        String source = """
                package com.example;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.context.annotation.Bean;
                @Configuration
                public class AppConfig {
                    @Bean public String hello() { return "hi"; }
                }
                """;

        List<EnableAnnotationRecord> records = extractor.extract(source);

        // @Configuration and @Bean don't start with "Enable" — neither captured.
        assertThat(records).isEmpty();
    }

    @Test
    void whenSourceIsNotParseable_thenIllegalArgumentExceptionIsThrown() {
        String source = "not a valid java source";

        assertThatThrownBy(() -> extractor.extract(source))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
