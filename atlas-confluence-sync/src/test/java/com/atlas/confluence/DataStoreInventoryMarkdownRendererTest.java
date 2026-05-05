package com.atlas.confluence;

import com.atlas.services.DataStoreInventoryRow;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DataStoreInventoryMarkdownRendererTest {

    private final DataStoreInventoryMarkdownRenderer renderer = new DataStoreInventoryMarkdownRenderer();

    @Test
    void whenRendered_thenStartsWithFrontMatterAndH1Title() {
        String rendered = renderer.render(List.of(), Map.of(), OffsetDateTime.now());

        assertThat(rendered).startsWith("---\n");
        assertThat(rendered).contains("title: Inventory: Data Stores");
        assertThat(rendered).contains("atlas_page_type: inventory");
        assertThat(rendered).contains("---\n\n# Inventory: Data Stores");
    }

    @Test
    void whenNoRows_thenThinNoteAppears() {
        String rendered = renderer.render(List.of(), Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .contains("## About this page")
                .contains("## Data stores")
                .contains("*No data stores registered yet.*");
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
                .contains("### checkout-db (postgres)")
                .contains("checkout-service")
                .contains("reporting-service")
                .contains("primary store")
                .contains("read-only replica")
                .contains("**owner**");
    }

    @Test
    void whenServiceRefIsKnown_thenServiceNameRendersAsWikiLink() {
        UUID dsId = UUID.randomUUID();
        UUID svcId = UUID.randomUUID();
        List<DataStoreInventoryRow> rows = List.of(
                new DataStoreInventoryRow(dsId, "users-db", "postgres", svcId, "user-service", true, ""));

        String rendered = renderer.render(
                rows,
                Map.of(svcId, "user-service"),
                OffsetDateTime.now());

        assertThat(rendered).contains("- [[user-service]] — **owner**");
    }

    @Test
    void whenStoreHasNoUsers_thenStoreSectionShowsThinNote() {
        UUID dsId = UUID.randomUUID();
        List<DataStoreInventoryRow> rows = List.of(
                new DataStoreInventoryRow(dsId, "orphan-db", "mysql", null, null, null, null));

        String rendered = renderer.render(rows, Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .contains("### orphan-db (mysql)")
                .contains("*No services use this data store yet.*");
    }

    @Test
    void whenRendered_thenNoStrayHtml() {
        UUID dsId = UUID.randomUUID();
        UUID svcId = UUID.randomUUID();
        List<DataStoreInventoryRow> rows = List.of(
                new DataStoreInventoryRow(dsId, "users-db", "postgres", svcId, "user-service", true, "primary"));

        String rendered = renderer.render(rows, Map.of(), OffsetDateTime.now());

        assertThat(rendered)
                .doesNotContain("<h2>")
                .doesNotContain("<h3>")
                .doesNotContain("<ul>")
                .doesNotContain("<a href=");
    }

    @Test
    void whenAskedForPageTitle_thenReturnsCanonicalTitle() {
        assertThat(DataStoreInventoryMarkdownRenderer.pageTitle()).isEqualTo("Inventory: Data Stores");
    }
}
