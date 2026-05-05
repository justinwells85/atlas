package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceConfigProperty;
import com.atlas.services.ServiceConfigurationPropertiesType;
import com.atlas.services.ServiceEnableAnnotation;
import com.atlas.services.ServiceStatus;
import com.atlas.services.ServiceValueInjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Markdown counterpart of {@link ConfigurationPageRendererTest} (Phase 5.9 M4).
 * GFM with YAML front matter; one H3 per property key whose heading text
 * equals the key path so Obsidian heading-anchor links resolve from
 * {@code @Value} rows.
 */
class ConfigurationMarkdownRendererTest {

    private ConfigurationMarkdownRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new ConfigurationMarkdownRenderer();
    }

    @Test
    void whenContextIsCompletelyEmpty_thenThinNoteIsRenderedWithFrontMatter() {
        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("empty-svc"),
                List.of(), List.of(), List.of(), List.of(),
                "https://wiki/empty-svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .startsWith("---\n")
                .contains("title: empty-svc — Configuration")
                .contains("atlas_page_type: configuration")
                .contains("# empty-svc — Configuration")
                .contains("No configuration data captured yet");
    }

    @Test
    void whenPropertiesArePresent_thenOneH3PerKeyWhoseTextMatchesTheKey() {
        ServiceConfigProperty defaultProp = property("atlas.api.key", "secret",
                "application.properties", "default");
        ServiceConfigProperty prodProp = property("atlas.api.key", "prod-secret",
                "application-prod.properties", "prod");

        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("svc"),
                List.of(prodProp, defaultProp),
                List.of(), List.of(), List.of(),
                "https://wiki/svc");

        String rendered = renderer.render(ctx);

        // Heading text == key path so Obsidian anchor links resolve.
        assertThat(rendered).contains("### atlas.api.key");
        // Both profile rows render in the same key section.
        assertThat(rendered).contains("default");
        assertThat(rendered).contains("prod");
        assertThat(rendered).contains("`secret`");
        assertThat(rendered).contains("`prod-secret`");
    }

    @Test
    void whenValueInjectionMatchesPropertyKey_thenWikiLinkAnchorIsRendered() {
        ServiceConfigProperty p = property("atlas.api.key", "secret",
                "application.properties", "default");
        ServiceValueInjection v = valueInjection("com.example.Cfg", "apiKey", "field",
                "${atlas.api.key:fallback}", "atlas.api.key", "fallback");

        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("svc"),
                List.of(p), List.of(v), List.of(), List.of(),
                "https://wiki/svc");

        String rendered = renderer.render(ctx);

        // Obsidian heading-anchor link: [[Page#heading|display]].
        assertThat(rendered)
                .contains("[[svc — Configuration#atlas.api.key|declared in properties]]");
    }

    @Test
    void whenValueInjectionDoesNotMatchAnyProperty_thenNoWikiLinkIsRendered() {
        ServiceValueInjection v = valueInjection("com.example.Cfg", "thing", "field",
                "${atlas.unknown}", "atlas.unknown", null);

        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("svc"),
                List.of(), List.of(v), List.of(), List.of(),
                "https://wiki/svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("`${atlas.unknown}`")
                .doesNotContain("declared in properties");
    }

    @Test
    void whenConfigurationPropertiesTypeIsPresent_thenComponentsTableRenders() {
        ServiceConfigurationPropertiesType t = configType(
                "com.example.AtlasProperties", "atlas", "record",
                "[{\"name\":\"apiKey\",\"declaredType\":\"String\"}," +
                        "{\"name\":\"port\",\"declaredType\":\"int\"}]");

        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("svc"),
                List.of(), List.of(), List.of(t), List.of(),
                "https://wiki/svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("### com.example.AtlasProperties")
                .contains("**Prefix:** `atlas`")
                .contains("**Kind:** record")
                .contains("| `apiKey` | `String` |")
                .contains("| `port` | `int` |");
    }

    @Test
    void whenEnableAnnotationHasJavadoc_thenItRendersInline() {
        ServiceEnableAnnotation a = enable(
                "com.example.App", "EnableMystery", "com.example.EnableMystery",
                "Activates the mystery subsystem.");

        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("svc"),
                List.of(), List.of(), List.of(), List.of(a),
                "https://wiki/svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("### com.example.App")
                .contains("`@EnableMystery`")
                .contains("`com.example.EnableMystery`")
                .contains("Activates the mystery subsystem.");
    }

    @Test
    void whenEnableAnnotationHasNoJavadoc_thenJavadocSegmentIsAbsent() {
        ServiceEnableAnnotation a = enable("com.example.App", "EnableScheduling",
                "org.springframework.scheduling.annotation.EnableScheduling", null);

        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("svc"),
                List.of(), List.of(), List.of(), List.of(a),
                "https://wiki/svc");

        String rendered = renderer.render(ctx);

        // No "— " javadoc separator after the FQN parenthesis when javadoc null.
        assertThat(rendered).contains("`@EnableScheduling` (`org.springframework.scheduling.annotation.EnableScheduling`)");
    }

    @Test
    void whenContextHasFullSurface_thenAllFourSectionsAppearInOrder() {
        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("svc"),
                List.of(property("a.b", "v", "application.properties", "default")),
                List.of(valueInjection("com.example.C", "x", "field", "${a.b}", "a.b", null)),
                List.of(configType("com.example.P", "atlas", "record", "[]")),
                List.of(enable("com.example.App", "EnableScheduling",
                        "org.springframework.scheduling.annotation.EnableScheduling", null)),
                "https://wiki/svc");

        String rendered = renderer.render(ctx);

        int props = rendered.indexOf("## Properties");
        int values = rendered.indexOf("## `@Value` injections");
        int types = rendered.indexOf("## `@ConfigurationProperties` types");
        int enables = rendered.indexOf("## `@Enable*` activations");

        assertThat(props).isPositive();
        assertThat(values).isGreaterThan(props);
        assertThat(types).isGreaterThan(values);
        assertThat(enables).isGreaterThan(types);
    }

    @Test
    void pageTitleFollowsServiceNameDashConfigurationPattern() {
        Service s = service("orders-svc");
        assertThat(ConfigurationMarkdownRenderer.pageTitle(s))
                .isEqualTo("orders-svc — Configuration");
    }

    @Test
    void whenPropertyValueContainsPipeCharacter_thenItIsEscapedInTheGfmTable() {
        ServiceConfigProperty p = property("atlas.regex", "a|b", "application.properties", "default");

        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("svc"),
                List.of(p), List.of(), List.of(), List.of(),
                "https://wiki/svc");

        String rendered = renderer.render(ctx);

        // GFM table cells require pipe-escape; assert the escape.
        assertThat(rendered).contains("a\\|b");
    }

    // ---- helpers --------------------------------------------------------

    private static Service service(String name) {
        Service s = new Service();
        s.setName(name);
        s.setOwnerTeam("team");
        s.setStatus(ServiceStatus.ACTIVE);
        return s;
    }

    private static ServiceConfigProperty property(String keyPath, String value,
                                                    String sourceFile, String profile) {
        return new ServiceConfigProperty(UUID.randomUUID(), UUID.randomUUID(),
                keyPath, value, sourceFile, profile, "properties-file");
    }

    private static ServiceValueInjection valueInjection(String enclosingClass, String memberName,
                                                          String memberKind, String rawSpel,
                                                          String keyPath, String defaultValue) {
        return new ServiceValueInjection(UUID.randomUUID(), UUID.randomUUID(), "",
                enclosingClass, memberName, memberKind, rawSpel, keyPath,
                defaultValue, "source-tree");
    }

    private static ServiceConfigurationPropertiesType configType(String enclosingClass,
                                                                   String prefix, String typeKind,
                                                                   String components) {
        return new ServiceConfigurationPropertiesType(UUID.randomUUID(), UUID.randomUUID(), "",
                enclosingClass, prefix, typeKind, components, "source-tree");
    }

    private static ServiceEnableAnnotation enable(String enclosingClass, String simpleName,
                                                    String fqn, String javadoc) {
        return new ServiceEnableAnnotation(UUID.randomUUID(), UUID.randomUUID(), "",
                enclosingClass, simpleName, fqn, javadoc, "source-tree");
    }
}
