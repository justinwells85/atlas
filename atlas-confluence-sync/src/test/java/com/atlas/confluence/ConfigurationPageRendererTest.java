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
 * Configuration Confluence-page renderer (Phase 5.9 M4). Pure function.
 * Properties grouped by profile (default first); {@code @Value} grouped
 * by enclosing class with cross-link to matching property anchor;
 * {@code @ConfigurationProperties} types listed with components;
 * {@code @Enable*} activations grouped by enclosing class.
 */
class ConfigurationPageRendererTest {

    private ConfigurationPageRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new ConfigurationPageRenderer();
    }

    @Test
    void whenContextIsCompletelyEmpty_thenThinNoteIsRendered() {
        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("empty-svc"),
                List.of(), List.of(), List.of(), List.of(),
                "https://wiki/empty-svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered).contains("No configuration data captured yet");
        assertThat(rendered).contains("Back to");
    }

    @Test
    void whenPropertiesArePresent_thenTableRendersWithKeyValueAndAnchorOnFirstOccurrence() {
        ServiceConfigProperty p = property("atlas.api.key", "secret",
                "application.properties", "default");

        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("svc"),
                List.of(p), List.of(), List.of(), List.of(),
                "https://wiki/svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("atlas.api.key")
                .contains("secret")
                .contains("application.properties")
                .contains("id=\"key-atlas-api-key\"");
    }

    @Test
    void whenPropertiesSpanMultipleProfiles_thenDefaultRendersFirstAndOthersFollow() {
        ServiceConfigProperty defaultProp = property("atlas.url", "http://default",
                "application.properties", "default");
        ServiceConfigProperty prodProp = property("atlas.url", "http://prod",
                "application-prod.properties", "prod");

        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("svc"),
                List.of(prodProp, defaultProp),
                List.of(), List.of(), List.of(),
                "https://wiki/svc");

        String rendered = renderer.render(ctx);

        // "default" profile heading appears before "prod" — default-first ordering.
        int defaultIdx = rendered.indexOf("Profile: default");
        int prodIdx = rendered.indexOf("Profile: prod");
        assertThat(defaultIdx).isPositive();
        assertThat(prodIdx).isGreaterThan(defaultIdx);
    }

    @Test
    void whenValueInjectionMatchesPropertyKey_thenCrossLinkAnchorIsRendered() {
        ServiceConfigProperty p = property("atlas.api.key", "secret",
                "application.properties", "default");
        ServiceValueInjection v = valueInjection("com.example.Cfg", "apiKey", "field",
                "${atlas.api.key:fallback}", "atlas.api.key", "fallback");

        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("svc"),
                List.of(p), List.of(v), List.of(), List.of(),
                "https://wiki/svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("href=\"#key-atlas-api-key\"")
                .contains("declared in properties");
    }

    @Test
    void whenValueInjectionDoesNotMatchAnyProperty_thenNoCrossLinkIsRendered() {
        ServiceValueInjection v = valueInjection("com.example.Cfg", "thing", "field",
                "${atlas.unknown}", "atlas.unknown", null);

        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("svc"),
                List.of(),
                List.of(v),
                List.of(), List.of(),
                "https://wiki/svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("${atlas.unknown}")
                .doesNotContain("declared in properties");
    }

    @Test
    void whenConfigurationPropertiesTypeIsPresent_thenPrefixKindAndComponentsRender() {
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
                .contains("com.example.AtlasProperties")
                .contains("atlas")
                .contains("record")
                .contains("apiKey")
                .contains("port");
    }

    @Test
    void whenConfigurationPropertiesTypeHasNoPrefix_thenNoPrefixLabelIsShown() {
        ServiceConfigurationPropertiesType t = configType(
                "com.example.TopLevel", "", "record", "[]");

        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("svc"),
                List.of(), List.of(), List.of(t), List.of(),
                "https://wiki/svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered).contains("(no prefix)");
    }

    @Test
    void whenEnableAnnotationsArePresent_thenSimpleNameFqnAndJavadocRender() {
        ServiceEnableAnnotation withJavadoc = enable(
                "com.example.App", "EnableMystery", "com.example.EnableMystery",
                "Activates the org's mystery subsystem.");
        ServiceEnableAnnotation builtIn = enable(
                "com.example.App", "EnableScheduling",
                "org.springframework.scheduling.annotation.EnableScheduling", null);

        ConfigurationPageContext ctx = new ConfigurationPageContext(
                service("svc"),
                List.of(), List.of(), List.of(),
                List.of(withJavadoc, builtIn),
                "https://wiki/svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("@EnableMystery")
                .contains("@EnableScheduling")
                // Apostrophe is HTML-escaped by the renderer's `escape()` per
                // the BeansPageRenderer precedent — Confluence storage format
                // accepts &apos; in text content.
                .contains("Activates the org&apos;s mystery subsystem.")
                .contains("org.springframework.scheduling.annotation.EnableScheduling");
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

        // Search for full <h3>...</h3> tags — the preamble paragraph
        // mentions the same substrings ("<code>@Value</code> injection sites",
        // etc.) so a substring match would hit the preamble first.
        int props = rendered.indexOf("<h3>Properties</h3>");
        int values = rendered.indexOf("<h3><code>@Value</code> injections</h3>");
        int types = rendered.indexOf("<h3><code>@ConfigurationProperties</code> types</h3>");
        int enables = rendered.indexOf("<h3><code>@Enable*</code> activations</h3>");

        assertThat(props).isPositive();
        assertThat(values).isGreaterThan(props);
        assertThat(types).isGreaterThan(values);
        assertThat(enables).isGreaterThan(types);
    }

    @Test
    void pageTitleFollowsServiceNameDashConfigurationPattern() {
        Service s = service("orders-svc");
        assertThat(ConfigurationPageRenderer.pageTitle(s))
                .isEqualTo("orders-svc — Configuration");
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
