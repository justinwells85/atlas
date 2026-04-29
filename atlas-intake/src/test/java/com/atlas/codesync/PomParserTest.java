package com.atlas.codesync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-function pom.xml parser (M4 — code-driven docs). Reads the literal
 * declarations in a pom.xml: groupId, artifactId, version, parent
 * coordinates, declared dependencies. No property resolution; no parent
 * inheritance traversal. The output is a {@link PomFacts} record, ready
 * for code-sync to translate into observation rows.
 */
class PomParserTest {

    private PomParser parser;

    @BeforeEach
    void setUp() {
        parser = new PomParser();
    }

    @Test
    void whenPomHasGroupAndArtifact_thenTheyAreExtracted() {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>example-svc</artifactId>
                    <version>1.0.0</version>
                </project>
                """;

        PomFacts facts = parser.parse(pom);

        assertThat(facts.groupId()).isEqualTo("com.example");
        assertThat(facts.artifactId()).isEqualTo("example-svc");
        assertThat(facts.version()).isEqualTo("1.0.0");
    }

    @Test
    void whenPomHasParent_thenParentCoordsAreCaptured() {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>4.0.6</version>
                    </parent>
                    <artifactId>my-svc</artifactId>
                </project>
                """;

        PomFacts facts = parser.parse(pom);

        assertThat(facts.parentGroupId()).isEqualTo("org.springframework.boot");
        assertThat(facts.parentArtifactId()).isEqualTo("spring-boot-starter-parent");
        assertThat(facts.parentVersion()).isEqualTo("4.0.6");
    }

    @Test
    void whenPomDeclaresSpringBootStarterParent_thenFrameworkIsInferredAsSpringBoot() {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>4.0.6</version>
                    </parent>
                    <artifactId>my-svc</artifactId>
                </project>
                """;

        PomFacts facts = parser.parse(pom);

        assertThat(facts.framework()).isEqualTo("Spring Boot");
        assertThat(facts.frameworkVersion()).isEqualTo("4.0.6");
    }

    @Test
    void whenPomIsMavenAndFromAJavaProject_thenLanguageIsJavaAndBuildToolIsMaven() {
        // The Maven Model API handles any pom; reading it confirms the
        // language is Java (Maven exclusively builds JVM languages, default
        // Java) and the build tool is Maven.
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>x</artifactId>
                </project>
                """;

        PomFacts facts = parser.parse(pom);

        assertThat(facts.language()).isEqualTo("Java");
        assertThat(facts.buildTool()).isEqualTo("Maven");
    }

    @Test
    void whenPomHasJavaVersionProperty_thenItIsCapturedAsLanguageVersion() {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>x</artifactId>
                    <properties>
                        <java.version>21</java.version>
                    </properties>
                </project>
                """;

        PomFacts facts = parser.parse(pom);

        assertThat(facts.languageVersion()).isEqualTo("21");
    }

    @Test
    void whenPomDeclaresDependencies_thenAllAreReturned() {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>x</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>com.anthropic</groupId>
                            <artifactId>anthropic-java</artifactId>
                            <version>2.27.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;

        PomFacts facts = parser.parse(pom);

        assertThat(facts.dependencies()).extracting(DependencyCoords::artifactId)
                .containsExactlyInAnyOrder("spring-boot-starter-web", "anthropic-java");
    }

    @Test
    void whenPomIsMalformed_thenParserThrowsCleanly() {
        assertThatThrownBy(() -> parser.parse("not actually xml { { {"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whenPomHasNoDependencies_thenDepListIsEmpty() {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>x</artifactId>
                </project>
                """;

        assertThat(parser.parse(pom).dependencies()).isEmpty();
    }
}
