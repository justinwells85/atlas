package com.atlas.confluence;

import com.atlas.services.ExternalDependencyInventoryRow;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalDependencyInventoryMarkdownRendererTest {

    private final ExternalDependencyInventoryMarkdownRenderer renderer =
            new ExternalDependencyInventoryMarkdownRenderer();

    @Test
    void whenRendered_thenStartsWithFrontMatterAndH1Title() {
        String rendered = renderer.render(List.of(), Map.of(), OffsetDateTime.now());

        assertThat(rendered).startsWith("---\n");
        assertThat(rendered).contains("title: Inventory: External Dependencies");
        assertThat(rendered).contains("atlas_page_type: inventory");
        assertThat(rendered).contains("---\n\n# Inventory: External Dependencies");
    }

    @Test
    void whenNoRows_thenThinNoteAppears() {
        String rendered = renderer.render(List.of(), Map.of(), OffsetDateTime.now());

        assertThat(rendered).contains("*No external dependencies registered yet.*");
    }

    @Test
    void whenDepHasMultipleConsumers_thenAllAppearUnderOneHeading() {
        UUID edId = UUID.randomUUID();
        UUID svc1 = UUID.randomUUID();
        UUID svc2 = UUID.randomUUID();
        List<ExternalDependencyInventoryRow> rows = List.of(
                new ExternalDependencyInventoryRow(edId, "Stripe", "https://stripe.com", svc1, "checkout-service", "card payments"),
                new ExternalDependencyInventoryRow(edId, "Stripe", "https://stripe.com", svc2, "billing-service", "subscription billing"));

        String rendered = renderer.render(rows, Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .contains("### [Stripe](https://stripe.com)")
                .contains("checkout-service")
                .contains("billing-service")
                .contains("card payments")
                .contains("subscription billing");
    }

    @Test
    void whenDepHasNoUrl_thenHeadingIsPlainText() {
        UUID edId = UUID.randomUUID();
        UUID svcId = UUID.randomUUID();
        List<ExternalDependencyInventoryRow> rows = List.of(
                new ExternalDependencyInventoryRow(edId, "InternalTool", null, svcId, "consumer-svc", "uses it"));

        String rendered = renderer.render(rows, Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .contains("### InternalTool")
                .doesNotContain("[InternalTool](");
    }

    @Test
    void whenServiceRefIsKnown_thenServiceNameRendersAsWikiLink() {
        UUID edId = UUID.randomUUID();
        UUID svcId = UUID.randomUUID();
        List<ExternalDependencyInventoryRow> rows = List.of(
                new ExternalDependencyInventoryRow(edId, "SendGrid", "https://sendgrid.com", svcId, "notifier", "transactional email"));

        String rendered = renderer.render(
                rows,
                Map.of(svcId, "notifier"),
                OffsetDateTime.now());

        assertThat(rendered).contains("- [[notifier]] — transactional email");
    }

    @Test
    void whenDepHasNoConsumers_thenSectionShowsThinNote() {
        UUID edId = UUID.randomUUID();
        List<ExternalDependencyInventoryRow> rows = List.of(
                new ExternalDependencyInventoryRow(edId, "UnusedTool", "https://example.com", null, null, null));

        String rendered = renderer.render(rows, Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .contains("UnusedTool")
                .contains("*No services depend on this third-party tool yet.*");
    }

    @Test
    void whenRendered_thenNoStrayHtml() {
        UUID edId = UUID.randomUUID();
        UUID svcId = UUID.randomUUID();
        List<ExternalDependencyInventoryRow> rows = List.of(
                new ExternalDependencyInventoryRow(edId, "Stripe", "https://stripe.com", svcId, "checkout", "primary payments"));

        String rendered = renderer.render(rows, Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .doesNotContain("<h2>")
                .doesNotContain("<h3>")
                .doesNotContain("<ul>")
                .doesNotContain("<a href=");
    }

    @Test
    void whenAskedForPageTitle_thenReturnsCanonicalTitle() {
        assertThat(ExternalDependencyInventoryMarkdownRenderer.pageTitle())
                .isEqualTo("Inventory: External Dependencies");
    }
}
