package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceModule;
import com.atlas.services.ServiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleMarkdownRendererTest {

    private ModuleMarkdownRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new ModuleMarkdownRenderer();
    }

    @Test
    void whenRendered_thenStartsWithFrontMatterAndH1Title() {
        ServiceModule m = mod("", null, "g", "a", "1.0", "jar");
        ModulePageContext ctx = new ModulePageContext(
                service("svc"), m, List.of(m), Map.of(), null);

        String rendered = renderer.render(ctx);

        assertThat(rendered).startsWith("---\n");
        assertThat(rendered).contains("title: svc — Module: (root)");
        assertThat(rendered).contains("atlas_page_type: module");
        assertThat(rendered).contains("---\n\n# (root)");
    }

    @Test
    void whenModuleHasParentAndChildren_thenWikiLinksRenderInBothDirections() {
        ServiceModule root = mod("", null, "billing", "billing-service", "1.0", "pom");
        ServiceModule api = mod("billing-api", "", "billing", "billing-api", "1.0", "jar");
        ServiceModule core = mod("billing-core", "", "billing", "billing-core", "1.0", "jar");

        ModulePageContext ctx = new ModulePageContext(
                service("billing"),
                root,
                List.of(root, api, core),
                Map.of(
                        "billing-api", "billing-api",
                        "billing-core", "billing-core"),
                "billing");

        String rendered = renderer.render(ctx);

        assertThat(rendered).contains("*Top-level module.*");
        assertThat(rendered)
                .contains("[[billing-api|billing-api]]")
                .contains("[[billing-core|billing-core]]");
    }

    @Test
    void whenSubModuleRenders_thenParentLinkBackToParent() {
        ServiceModule root = mod("", null, "billing", "billing-service", "1.0", "pom");
        ServiceModule api = mod("billing-api", "", "billing", "billing-api", "1.0", "jar");

        ModulePageContext ctx = new ModulePageContext(
                service("billing"),
                api,
                List.of(root, api),
                Map.of("", "billing-root"),
                "billing");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("## Parent module")
                .contains("[[billing-root|(root)]]")
                .contains("billing-service"); // parent's artifactId
    }

    @Test
    void whenModuleHasNoChildren_thenSubModulesSectionShowsThinNote() {
        ServiceModule leaf = mod("", null, "leaf", "leaf-svc", "1.0", "jar");

        ModulePageContext ctx = new ModulePageContext(
                service("leaf-svc"),
                leaf,
                List.of(leaf),
                Map.of(),
                null);

        String rendered = renderer.render(ctx);

        assertThat(rendered).containsIgnoringCase("No sub-modules");
    }

    @Test
    void whenModuleHasCoords_thenAllCoordsRendered() {
        ServiceModule m = mod("", null, "com.example", "my-svc", "2.1.0", "jar");

        ModulePageContext ctx = new ModulePageContext(
                service("my-svc"),
                m,
                List.of(m),
                Map.of(),
                null);

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("**Group ID:** com.example")
                .contains("**Artifact ID:** my-svc")
                .contains("**Version:** 2.1.0")
                .contains("**Packaging:** jar");
    }

    @Test
    void whenModuleHasDeclaredDeps_thenDepsAreListedFromJsonArray() {
        ServiceModule m = new ServiceModule(
                UUID.randomUUID(), UUID.randomUUID(),
                "", null,
                "com.example", "my-svc", "1.0", "jar",
                null, null, null,
                "[\"org.springframework.boot:spring-boot-starter-web\",\"com.fasterxml.jackson.core:jackson-databind\"]",
                "pom-xml", null);

        ModulePageContext ctx = new ModulePageContext(
                service("my-svc"),
                m,
                List.of(m),
                Map.of(),
                null);

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("## Declared dependencies")
                .contains("- org.springframework.boot:spring-boot-starter-web")
                .contains("- com.fasterxml.jackson.core:jackson-databind");
    }

    @Test
    void whenDeclaredDepsAreEmptyArray_thenSectionRendersThinNote() {
        ServiceModule m = new ServiceModule(
                UUID.randomUUID(), UUID.randomUUID(),
                "", null,
                "com.example", "my-svc", "1.0", "jar",
                null, null, null,
                "[]",
                "pom-xml", null);

        ModulePageContext ctx = new ModulePageContext(
                service("my-svc"),
                m,
                List.of(m),
                Map.of(),
                null);

        String rendered = renderer.render(ctx);

        assertThat(rendered).containsIgnoringCase("No declared dependencies");
    }

    @Test
    void whenComputingPageTitle_thenTitleIsServiceNameDashModule() {
        Service s = service("billing-service");
        ServiceModule m = mod("billing-api", "", "billing", "billing-api", "1.0", "jar");

        assertThat(ModuleMarkdownRenderer.pageTitle(s, m))
                .isEqualTo("billing-service — Module: billing-api");
    }

    @Test
    void whenRootModuleRendersTitle_thenLabelIsRootRatherThanEmpty() {
        Service s = service("leaf-svc");
        ServiceModule root = mod("", null, "leaf", "leaf-svc", "1.0", "jar");

        assertThat(ModuleMarkdownRenderer.pageTitle(s, root))
                .isEqualTo("leaf-svc — Module: (root)");
    }

    @Test
    void whenChildModuleHasNoPageRefYet_thenItIsListedAsPlainText() {
        ServiceModule root = mod("", null, "leaf", "leaf-svc", "1.0", "pom");
        ServiceModule api = mod("api", "", "leaf", "leaf-api", "1.0", "jar");

        ModulePageContext ctx = new ModulePageContext(
                service("leaf-svc"),
                root,
                List.of(root, api),
                Map.of(),
                null);

        String rendered = renderer.render(ctx);

        assertThat(rendered).contains("- api");
        assertThat(rendered).doesNotContain("[[api"); // no broken WikiLink
    }

    @Test
    void whenServicePageRefIsProvided_thenWikiLinkBackLinkRendered() {
        ServiceModule m = mod("", null, "g", "a", "1.0", "jar");

        ModulePageContext ctx = new ModulePageContext(
                service("svc"), m, List.of(m), Map.of(), "svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered).contains("Back to [[svc|svc]]");
    }

    @Test
    void whenRendered_thenNoStrayHtml() {
        ServiceModule m = mod("", null, "com.example", "my-svc", "2.1.0", "jar");

        ModulePageContext ctx = new ModulePageContext(
                service("my-svc"), m, List.of(m), Map.of(), "my-svc");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .doesNotContain("<h2>")
                .doesNotContain("<p>")
                .doesNotContain("<ul>")
                .doesNotContain("<a href=");
    }

    private Service service(String name) {
        Service s = new Service();
        s.setName(name);
        s.setStatus(ServiceStatus.ACTIVE);
        return s;
    }

    private ServiceModule mod(String path, String parentPath, String groupId,
                              String artifactId, String version, String packaging) {
        return new ServiceModule(
                UUID.randomUUID(), UUID.randomUUID(),
                path, parentPath,
                groupId, artifactId, version, packaging,
                null, null, null, "[]",
                "pom-xml", null);
    }
}
