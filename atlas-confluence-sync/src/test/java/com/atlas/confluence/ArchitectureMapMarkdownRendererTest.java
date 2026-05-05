package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceDependencyEdge;
import com.atlas.services.ServiceStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureMapMarkdownRendererTest {

    private final ArchitectureMapMarkdownRenderer renderer = new ArchitectureMapMarkdownRenderer();

    @Test
    void whenRendered_thenStartsWithFrontMatterAndH1Title() {
        String rendered = renderer.render(List.of(), List.of(),
                OffsetDateTime.parse("2026-04-28T10:00:00Z"));

        assertThat(rendered).startsWith("---\n");
        assertThat(rendered).contains("title: Atlas — Architecture Map");
        assertThat(rendered).contains("atlas_page_type: architecture-map");
        assertThat(rendered).contains("---\n\n# Atlas — Architecture Map");
    }

    @Test
    void whenServicesHaveDependencies_thenMermaidFencedBlockAndFallbackBulletsAppear() {
        UUID intakeId = UUID.randomUUID();
        UUID mcpId = UUID.randomUUID();
        UUID domainId = UUID.randomUUID();
        Service intake = svc(intakeId, "atlas-intake");
        Service mcp = svc(mcpId, "atlas-mcp");
        Service domain = svc(domainId, "atlas-domain");

        List<ServiceDependencyEdge> edges = List.of(
                edge(domainId, "atlas-domain", intakeId, "atlas-intake", "JPA repos"),
                edge(domainId, "atlas-domain", mcpId, "atlas-mcp", "JPA repos"));

        String rendered = renderer.render(List.of(intake, mcp, domain), edges,
                OffsetDateTime.parse("2026-04-28T10:00:00Z"));

        // GFM mermaid fenced block: ```mermaid ... ```
        assertThat(rendered)
                .contains("```mermaid\n")
                .contains("flowchart LR")
                .contains("atlas-intake")
                .contains("atlas-mcp")
                .contains("atlas-domain")
                .contains("-->")
                .contains("```\n");

        // Plain-text fallback list also present.
        assertThat(rendered)
                .contains("### Edges (text fallback)")
                .contains("- atlas-domain → atlas-intake — JPA repos")
                .contains("- atlas-domain → atlas-mcp — JPA repos");
    }

    @Test
    void whenServicesHaveNoDependencies_thenMermaidGraphHasNodesButNoEdges() {
        Service a = svc(UUID.randomUUID(), "lonely-svc-a");
        Service b = svc(UUID.randomUUID(), "lonely-svc-b");

        String rendered = renderer.render(List.of(a, b), List.of(), OffsetDateTime.now());

        assertThat(rendered)
                .contains("lonely-svc-a")
                .contains("lonely-svc-b");
        assertThat(rendered).doesNotContain("-->");
        assertThat(rendered).containsIgnoringCase("no service-to-service dependencies");
    }

    @Test
    void whenNoServicesAtAll_thenEmptyStateMessageAppears() {
        String rendered = renderer.render(List.of(), List.of(), OffsetDateTime.now());

        assertThat(rendered).containsIgnoringCase("no services registered");
        assertThat(rendered).doesNotContain("-->");
    }

    @Test
    void whenServiceNameContainsBracketsAndQuotes_thenItIsSanitizedForMermaid() {
        UUID id = UUID.randomUUID();
        // Brackets break mermaid label syntax; the renderer must replace them.
        Service s = svc(id, "weird[name]\"svc\"");

        String rendered = renderer.render(List.of(s), List.of(), OffsetDateTime.now());

        // Brackets replaced with parens, quotes with apostrophes — readable AND mermaid-safe.
        assertThat(rendered).contains("weird(name)'svc'");
        assertThat(rendered).doesNotContain("[name]");
    }

    @Test
    void whenLastSyncProvided_thenItAppearsInBodyAndFrontMatter() {
        OffsetDateTime t = OffsetDateTime.parse("2026-04-28T15:30:00Z");
        String rendered = renderer.render(List.of(), List.of(), t);

        assertThat(rendered)
                .contains("last_synced_at: 2026-04-28T15:30Z")
                .contains("**Last refreshed:** 2026-04-28T15:30Z");
    }

    @Test
    void whenLastSyncIsNull_thenLastSyncedAtIsOmittedFromFrontMatter() {
        String rendered = renderer.render(List.of(), List.of(), null);

        assertThat(rendered).doesNotContain("last_synced_at");
    }

    @Test
    void whenRendered_thenIsValidMarkdownWithNoConfluenceMacros() {
        UUID id = UUID.randomUUID();
        Service s = svc(id, "svc");
        String rendered = renderer.render(List.of(s), List.of(), OffsetDateTime.now());

        assertThat(rendered)
                .doesNotContain("<ac:")
                .doesNotContain("CDATA")
                .doesNotContain("<h2>")
                .doesNotContain("<ul>");
    }

    @Test
    void whenAskedForPageTitle_thenReturnsCanonicalTitle() {
        assertThat(ArchitectureMapMarkdownRenderer.pageTitle()).isEqualTo("Atlas — Architecture Map");
    }

    private Service svc(UUID id, String name) {
        Service s = new Service();
        s.setName(name);
        s.setStatus(ServiceStatus.ACTIVE);
        try {
            java.lang.reflect.Field f = Service.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(s, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return s;
    }

    private ServiceDependencyEdge edge(UUID upstreamId, String upstreamName,
                                       UUID downstreamId, String downstreamName,
                                       String description) {
        return new ServiceDependencyEdge(upstreamId, upstreamName, downstreamId, downstreamName, description);
    }
}
