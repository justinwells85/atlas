package com.atlas.confluence;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AboutPageRendererTest {

    private final AboutPageRenderer renderer = new AboutPageRenderer(
            "https://github.com/example/atlas",
            "http://intake.example",
            "http://mcp.example",
            "http://sync.example");

    @Test
    void whenRendered_thenSectionsCoverWhatHowArchitectureEndpointsAndSource() {
        String rendered = renderer.render(OffsetDateTime.parse("2026-04-28T10:00:00Z"));

        assertThat(rendered)
                .contains("<h2>What is Atlas?</h2>")
                .contains("<h2>How to add your service</h2>")
                .contains("<h2>Architecture</h2>")
                .contains("<h2>Local endpoints (prototype)</h2>")
                .contains("<h2>Source code &amp; design docs</h2>")
                .contains("source of truth")
                .contains("intake interview")
                .contains("atlas-intake")
                .contains("atlas-mcp")
                .contains("atlas-confluence-sync")
                .contains("atlas-domain");
    }

    @Test
    void whenRepoUrlIsConfigured_thenLinksPointAtIt() {
        String rendered = renderer.render(OffsetDateTime.now());

        assertThat(rendered)
                .contains("<a href=\"https://github.com/example/atlas\">https://github.com/example/atlas</a>")
                .contains("<a href=\"https://github.com/example/atlas/blob/main/docs/architecture.md\">");
    }

    @Test
    void whenLocalEndpointsAreConfigured_thenLinksAppearInListing() {
        String rendered = renderer.render(OffsetDateTime.now());

        assertThat(rendered)
                .contains("<a href=\"http://intake.example\">")
                .contains("<a href=\"http://mcp.example\">")
                .contains("<a href=\"http://sync.example\">");
    }

    @Test
    void whenLastSyncProvided_thenItAppearsInOutput() {
        OffsetDateTime t = OffsetDateTime.parse("2026-04-28T15:30:00Z");
        String rendered = renderer.render(t);

        assertThat(rendered)
                .contains("Last refreshed")
                .contains("2026-04-28T15:30");
    }

    @Test
    void whenLastSyncIsNull_thenLastRefreshedFieldIsOmitted() {
        String rendered = renderer.render(null);

        assertThat(rendered).doesNotContain("Last refreshed");
    }
}
