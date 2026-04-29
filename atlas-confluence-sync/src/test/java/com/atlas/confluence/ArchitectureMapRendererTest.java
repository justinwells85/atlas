package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceDependencyEdge;
import com.atlas.services.ServiceStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureMapRendererTest {

    private final ArchitectureMapRenderer renderer = new ArchitectureMapRenderer();

    @Test
    void whenServicesHaveDependencies_thenMermaidEdgesAndFallbackBulletsAppear() {
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

        // Mermaid block present.
        assertThat(rendered)
                .contains("flowchart")
                .contains("atlas-intake")
                .contains("atlas-mcp")
                .contains("atlas-domain")
                .contains("-->");

        // Plain-text fallback list also present.
        assertThat(rendered)
                .contains("<ul>")
                .contains("atlas-domain")
                .contains("atlas-intake");
    }

    @Test
    void whenServicesHaveNoDependencies_thenMermaidGraphIsEmptyAndNoteAppears() {
        Service a = svc(UUID.randomUUID(), "lonely-svc-a");
        Service b = svc(UUID.randomUUID(), "lonely-svc-b");

        String rendered = renderer.render(List.of(a, b), List.of(), OffsetDateTime.now());

        // Nodes still appear in the mermaid block (so the diagram isn't empty).
        assertThat(rendered)
                .contains("lonely-svc-a")
                .contains("lonely-svc-b");
        // No edges drawn.
        assertThat(rendered).doesNotContain("-->");
        // A "no dependencies recorded" hint is shown for the fallback section.
        assertThat(rendered).containsIgnoringCase("no service-to-service dependencies");
    }

    @Test
    void whenNoServicesAtAll_thenEmptyStateMessageAppears() {
        String rendered = renderer.render(List.of(), List.of(), OffsetDateTime.now());

        assertThat(rendered).containsIgnoringCase("no services registered");
        assertThat(rendered).doesNotContain("-->");
    }

    @Test
    void whenServiceNameContainsSpecialCharacters_thenItIsEscapedInOutput() {
        UUID id = UUID.randomUUID();
        Service s = svc(id, "weird<name>&\"svc\"");

        String rendered = renderer.render(List.of(s), List.of(), OffsetDateTime.now());

        assertThat(rendered)
                .doesNotContain("<name>")
                .contains("weird&lt;name&gt;");
    }

    @Test
    void whenLastSyncProvided_thenItAppearsInOutput() {
        OffsetDateTime t = OffsetDateTime.parse("2026-04-28T15:30:00Z");
        String rendered = renderer.render(List.of(), List.of(), t);

        assertThat(rendered)
                .contains("Last refreshed")
                .contains("2026-04-28T15:30");
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
