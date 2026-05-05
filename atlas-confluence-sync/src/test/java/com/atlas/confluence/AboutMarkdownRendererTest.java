package com.atlas.confluence;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AboutMarkdownRendererTest {

    private final AboutMarkdownRenderer renderer = new AboutMarkdownRenderer(
            "https://github.com/example/atlas",
            "http://intake.example",
            "http://mcp.example",
            "http://sync.example");

    @Test
    void whenRendered_thenStartsWithFrontMatterAndH1Title() {
        String rendered = renderer.render(OffsetDateTime.parse("2026-04-28T10:00:00Z"));

        assertThat(rendered).startsWith("---\n");
        assertThat(rendered).contains("title: About Atlas");
        assertThat(rendered).contains("atlas_page_type: about");
        assertThat(rendered).contains("last_synced_at: 2026-04-28T10:00Z");
        assertThat(rendered).contains("---\n\n# About Atlas\n");
    }

    @Test
    void whenRendered_thenSectionsCoverWhatHowArchitectureEndpointsAndSource() {
        String rendered = renderer.render(OffsetDateTime.parse("2026-04-28T10:00:00Z"));

        assertThat(rendered)
                .contains("## What is Atlas?")
                .contains("## How to add your service")
                .contains("## Architecture")
                .contains("## Local endpoints (prototype)")
                .contains("## Source code & design docs")
                .contains("source of truth")
                .contains("intake interview")
                .contains("**atlas-intake**")
                .contains("**atlas-mcp**")
                .contains("**atlas-confluence-sync**")
                .contains("**atlas-domain**");
    }

    @Test
    void whenRepoUrlIsConfigured_thenLinksPointAtIt() {
        String rendered = renderer.render(OffsetDateTime.now());

        assertThat(rendered)
                .contains("[https://github.com/example/atlas](https://github.com/example/atlas)")
                .contains("(https://github.com/example/atlas/blob/main/docs/architecture.md)");
    }

    @Test
    void whenLocalEndpointsAreConfigured_thenLinksAppearInListing() {
        String rendered = renderer.render(OffsetDateTime.now());

        assertThat(rendered)
                .contains("[http://intake.example](http://intake.example)")
                .contains("[http://mcp.example](http://mcp.example)")
                .contains("[http://sync.example](http://sync.example)");
    }

    @Test
    void whenLastSyncProvided_thenItAppearsInOutputAndFrontMatter() {
        OffsetDateTime t = OffsetDateTime.parse("2026-04-28T15:30:00Z");
        String rendered = renderer.render(t);

        assertThat(rendered)
                .contains("last_synced_at: 2026-04-28T15:30Z")
                .contains("**Last refreshed:** 2026-04-28T15:30Z");
    }

    @Test
    void whenLastSyncIsNull_thenLastRefreshedFieldIsOmittedFromBodyAndFrontMatter() {
        String rendered = renderer.render(null);

        assertThat(rendered)
                .doesNotContain("Last refreshed")
                .doesNotContain("last_synced_at");
    }

    @Test
    void whenRendered_thenIsValidMarkdownWithNoStrayHtmlTags() {
        String rendered = renderer.render(OffsetDateTime.now());

        assertThat(rendered)
                .doesNotContain("<h2>")
                .doesNotContain("<p>")
                .doesNotContain("<ul>")
                .doesNotContain("<li>")
                .doesNotContain("<a href")
                .doesNotContain("<strong>")
                .doesNotContain("&amp;");
    }

    @Test
    void whenAskedForPageTitle_thenReturnsAboutAtlas() {
        assertThat(AboutMarkdownRenderer.pageTitle()).isEqualTo("About Atlas");
    }
}
