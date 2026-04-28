package com.atlas.confluence;

import com.atlas.services.ExternalDependencyInventoryRow;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalDependencyInventoryRendererTest {

    private final ExternalDependencyInventoryRenderer renderer = new ExternalDependencyInventoryRenderer();

    @Test
    void whenNoRows_thenThinNoteAppears() {
        String rendered = renderer.render(List.of(), Map.of(), OffsetDateTime.now());

        assertThat(rendered).contains("No external dependencies registered yet");
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
                .contains("<h3><a href=\"https://stripe.com\">Stripe</a></h3>")
                .contains("checkout-service")
                .contains("billing-service")
                .contains("card payments")
                .contains("subscription billing");
    }

    @Test
    void whenServiceUrlIsKnown_thenServiceNameIsHyperlinked() {
        UUID edId = UUID.randomUUID();
        UUID svcId = UUID.randomUUID();
        List<ExternalDependencyInventoryRow> rows = List.of(
                new ExternalDependencyInventoryRow(edId, "SendGrid", "https://sendgrid.com", svcId, "notifier", "transactional email"));

        String rendered = renderer.render(
                rows,
                Map.of(svcId, "https://example.atlassian.net/wiki/spaces/ATLAS/pages/99"),
                OffsetDateTime.now());

        assertThat(rendered)
                .contains("<a href=\"https://example.atlassian.net/wiki/spaces/ATLAS/pages/99\">notifier</a>");
    }

    @Test
    void whenDepHasNoConsumers_thenSectionShowsThinNote() {
        UUID edId = UUID.randomUUID();
        // LEFT JOIN result for an unused external dep.
        List<ExternalDependencyInventoryRow> rows = List.of(
                new ExternalDependencyInventoryRow(edId, "UnusedTool", "https://example.com", null, null, null));

        String rendered = renderer.render(rows, Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .contains("UnusedTool")
                .contains("No services depend on this third-party tool yet");
    }
}
