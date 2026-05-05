package com.atlas.confluence;

import com.atlas.services.Service;
import com.atlas.services.ServiceStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LandingMarkdownRendererTest {

    private final LandingMarkdownRenderer renderer = new LandingMarkdownRenderer();

    @Test
    void whenRendered_thenStartsWithFrontMatterAndH1Title() {
        String rendered = renderer.render(List.of(), Map.of(),
                OffsetDateTime.parse("2026-04-28T10:00:00Z"));

        assertThat(rendered).startsWith("---\n");
        assertThat(rendered).contains("title: Atlas — Service Inventory");
        assertThat(rendered).contains("atlas_page_type: landing");
        assertThat(rendered).contains("---\n\n# Atlas — Service Inventory");
    }

    @Test
    void whenServicesAreProvided_thenIndexTableContainsOneRowPerService() {
        Service a = svc("alpha-service", "Platform", "Handles A.", ServiceStatus.ACTIVE);
        Service b = svc("beta-service", "Apps", "Handles B.", ServiceStatus.IN_DEV);

        String rendered = renderer.render(
                List.of(a, b), Map.of(),
                OffsetDateTime.parse("2026-04-28T10:00:00Z"));

        assertThat(rendered)
                .contains("## About this space")
                .contains("## Service index")
                .contains("| Service | Owner | Status | Description |")
                .contains("|---|---|---|---|")
                .contains("alpha-service")
                .contains("beta-service")
                .contains("Platform")
                .contains("Apps")
                .contains("active")
                .contains("in_dev")
                .contains("Handles A.")
                .contains("Handles B.")
                .contains("**Last refreshed:**")
                .contains("source of truth");
    }

    @Test
    void whenNoServicesProvided_thenThinNoteAppearsInsteadOfTable() {
        String rendered = renderer.render(List.of(), Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .contains("*No services registered yet")
                .doesNotContain("| Service | Owner |");
    }

    @Test
    void whenServiceHasPageRef_thenServiceNameRendersAsWikiLink() {
        UUID id = UUID.randomUUID();
        Service s = svcWithId(id, "linked-svc", "team", ServiceStatus.ACTIVE);

        String rendered = renderer.render(
                List.of(s),
                Map.of(id, "linked-svc"),
                OffsetDateTime.now());

        // Target equals display name → short WikiLink form (no alias).
        assertThat(rendered).contains("| [[linked-svc]] | team |");
    }

    @Test
    void whenWikiLinkTargetDiffersFromDisplay_thenAliasFormIsUsed() {
        UUID id = UUID.randomUUID();
        Service s = svcWithId(id, "display-name", "team", ServiceStatus.ACTIVE);

        String rendered = renderer.render(
                List.of(s),
                Map.of(id, "internal-target"),
                OffsetDateTime.now());

        assertThat(rendered).contains("| [[internal-target|display-name]] | team |");
    }

    @Test
    void whenServiceHasNoPageRef_thenServiceNameIsPlainText() {
        UUID id = UUID.randomUUID();
        Service s = svcWithId(id, "unlinked-svc", "team", ServiceStatus.ACTIVE);

        String rendered = renderer.render(List.of(s), Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .contains("| unlinked-svc | team |")
                .doesNotContain("[[unlinked-svc]]");
    }

    @Test
    void whenLandingPageRenders_thenHowToReadThisSpacePreambleExplainsDrillDown() {
        String rendered = renderer.render(List.of(), Map.of(), OffsetDateTime.now());

        assertThat(rendered).contains("How to read this space");
        String lower = rendered.toLowerCase();
        assertThat(lower)
                .contains("endpoint")
                .contains("module")
                .contains("bean")
                .contains("test");
    }

    @Test
    void whenServicesAreOutOfOrder_thenIndexTableSortsAlphabetically() {
        Service zzz = svc("zzz-service", "team", null, ServiceStatus.ACTIVE);
        Service aaa = svc("aaa-service", "team", null, ServiceStatus.ACTIVE);

        String rendered = renderer.render(List.of(zzz, aaa), Map.of(), OffsetDateTime.now());

        int aaaPos = rendered.indexOf("aaa-service");
        int zzzPos = rendered.indexOf("zzz-service");
        assertThat(aaaPos).isPositive();
        assertThat(zzzPos).isPositive();
        assertThat(aaaPos).isLessThan(zzzPos);
    }

    @Test
    void whenServiceNameContainsPipe_thenItIsEscapedInTableCell() {
        Service s = svc("pipe|service", "team", "desc", ServiceStatus.ACTIVE);

        String rendered = renderer.render(List.of(s), Map.of(), OffsetDateTime.now());

        // Bare | would break the GFM pipe table; backslash-escaped is valid.
        assertThat(rendered).contains("pipe\\|service");
    }

    @Test
    void whenRendered_thenIsValidMarkdownWithNoStrayHtml() {
        String rendered = renderer.render(List.of(), Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .doesNotContain("<h2>")
                .doesNotContain("<table>")
                .doesNotContain("<a href=")
                .doesNotContain("<strong>");
    }

    @Test
    void whenAskedForPageTitle_thenReturnsCanonicalTitle() {
        assertThat(LandingMarkdownRenderer.pageTitle()).isEqualTo("Atlas — Service Inventory");
    }

    private Service svc(String name, String owner, String description, ServiceStatus status) {
        Service s = new Service();
        s.setName(name);
        s.setOwnerTeam(owner);
        s.setDescription(description);
        s.setStatus(status);
        return s;
    }

    private Service svcWithId(UUID id, String name, String owner, ServiceStatus status) {
        Service s = svc(name, owner, null, status);
        try {
            java.lang.reflect.Field idField = Service.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(s, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return s;
    }
}
