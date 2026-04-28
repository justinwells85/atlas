package com.atlas.confluence;

import com.atlas.services.DataStoreInventoryRow;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DataStoreInventoryRendererTest {

    private final DataStoreInventoryRenderer renderer = new DataStoreInventoryRenderer();

    @Test
    void whenNoRows_thenThinNoteAppears() {
        String rendered = renderer.render(List.of(), Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .contains("<h2>About this page</h2>")
                .contains("<h2>Data stores</h2>")
                .contains("No data stores registered yet");
    }

    @Test
    void whenStoreHasMultipleServices_thenAllAppearUnderOneHeading() {
        UUID dsId = UUID.randomUUID();
        UUID svc1 = UUID.randomUUID();
        UUID svc2 = UUID.randomUUID();
        List<DataStoreInventoryRow> rows = List.of(
                new DataStoreInventoryRow(dsId, "checkout-db", "postgres", svc1, "checkout-service", true, "primary store"),
                new DataStoreInventoryRow(dsId, "checkout-db", "postgres", svc2, "reporting-service", false, "read-only replica"));

        String rendered = renderer.render(rows, Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .contains("<h3>checkout-db (postgres)</h3>")
                .contains("checkout-service")
                .contains("reporting-service")
                .contains("primary store")
                .contains("read-only replica")
                .contains("<strong>owner</strong>");
    }

    @Test
    void whenServiceUrlIsKnown_thenServiceNameIsHyperlinked() {
        UUID dsId = UUID.randomUUID();
        UUID svcId = UUID.randomUUID();
        List<DataStoreInventoryRow> rows = List.of(
                new DataStoreInventoryRow(dsId, "users-db", "postgres", svcId, "user-service", true, ""));

        String rendered = renderer.render(
                rows,
                Map.of(svcId, "https://example.atlassian.net/wiki/spaces/ATLAS/pages/77"),
                OffsetDateTime.now());

        assertThat(rendered)
                .contains("<a href=\"https://example.atlassian.net/wiki/spaces/ATLAS/pages/77\">user-service</a>");
    }

    @Test
    void whenStoreHasNoUsers_thenStoreSectionShowsThinNote() {
        UUID dsId = UUID.randomUUID();
        // LEFT JOIN result for an unused data store: one row with null service fields.
        List<DataStoreInventoryRow> rows = List.of(
                new DataStoreInventoryRow(dsId, "orphan-db", "mysql", null, null, null, null));

        String rendered = renderer.render(rows, Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .contains("<h3>orphan-db (mysql)</h3>")
                .contains("No services use this data store yet");
    }
}
