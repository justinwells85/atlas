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

/**
 * L4 module Confluence-page renderer (Phase 5.6 M2). Pure function: given
 * the module + the service's full module set, produce a Confluence
 * storage-format string with sections for parent / sub-modules /
 * coordinates / language+framework / declared deps.
 */
class ModulePageRendererTest {

    private ModulePageRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new ModulePageRenderer();
    }

    @Test
    void whenModuleHasParentAndChildren_thenLinksRenderInBothDirections() {
        ServiceModule root = mod("", null, "billing", "billing-service", "1.0", "pom");
        ServiceModule api = mod("billing-api", "", "billing", "billing-api", "1.0", "jar");
        ServiceModule core = mod("billing-core", "", "billing", "billing-core", "1.0", "jar");

        ModulePageContext ctx = new ModulePageContext(
                service("billing"),
                root,
                List.of(root, api, core),
                Map.of(
                        "billing-api", "https://wiki/billing-api-page",
                        "billing-core", "https://wiki/billing-core-page"),
                "https://wiki/billing-service-page");

        String rendered = renderer.render(ctx);

        assertThat(rendered).contains("Top-level module");
        assertThat(rendered)
                .contains("https://wiki/billing-api-page")
                .contains("billing-api")
                .contains("https://wiki/billing-core-page")
                .contains("billing-core");
    }

    @Test
    void whenSubModuleRenders_thenParentLinkBackToParent() {
        ServiceModule root = mod("", null, "billing", "billing-service", "1.0", "pom");
        ServiceModule api = mod("billing-api", "", "billing", "billing-api", "1.0", "jar");

        ModulePageContext ctx = new ModulePageContext(
                service("billing"),
                api,
                List.of(root, api),
                Map.of("", "https://wiki/billing-root-page"),
                "https://wiki/billing-service-page");

        String rendered = renderer.render(ctx);

        assertThat(rendered)
                .contains("Parent module")
                .contains("https://wiki/billing-root-page")
                .contains("billing-service"); // parent's artifactId surfaced
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
                .contains("com.example")
                .contains("my-svc")
                .contains("2.1.0")
                .contains("jar");
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
                .contains("Declared dependencies")
                .contains("org.springframework.boot:spring-boot-starter-web")
                .contains("com.fasterxml.jackson.core:jackson-databind");
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

        String title = ModulePageRenderer.pageTitle(s, m);

        assertThat(title).isEqualTo("billing-service — Module: billing-api");
    }

    @Test
    void whenRootModuleRendersTitle_thenLabelIsRootRatherThanEmpty() {
        Service s = service("leaf-svc");
        ServiceModule root = mod("", null, "leaf", "leaf-svc", "1.0", "jar");

        String title = ModulePageRenderer.pageTitle(s, root);

        assertThat(title).isEqualTo("leaf-svc — Module: (root)");
    }

    @Test
    void whenChildModuleHasNoConfluencePageYet_thenLinkFallsBackToPlainText() {
        // First-sync round: a sub-module is observed but not yet synced
        // to its own page. The renderer should still emit the listing,
        // just without a hyperlink.
        ServiceModule root = mod("", null, "leaf", "leaf-svc", "1.0", "pom");
        ServiceModule api = mod("api", "", "leaf", "leaf-api", "1.0", "jar");

        ModulePageContext ctx = new ModulePageContext(
                service("leaf-svc"),
                root,
                List.of(root, api),
                Map.of(), // no URLs known yet
                null);

        String rendered = renderer.render(ctx);

        assertThat(rendered).contains("api"); // listed even without a URL
        assertThat(rendered).doesNotContain("href=\"\""); // not a broken link
    }

    // ---- helpers --------------------------------------------------------

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
