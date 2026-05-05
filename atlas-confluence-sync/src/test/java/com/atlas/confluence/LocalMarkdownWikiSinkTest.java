package com.atlas.confluence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavior coverage for {@link LocalMarkdownWikiSink}. Uses {@link TempDir}
 * so writes hit a real filesystem (per ADR-006: real implementations for
 * everything inside Atlas; the filesystem is inside Atlas).
 */
class LocalMarkdownWikiSinkTest {

    @TempDir
    Path vault;

    LocalMarkdownWikiSink sink;

    @BeforeEach
    void setUp() {
        sink = new LocalMarkdownWikiSink(vault.toString());
    }

    @Test
    void whenAskedForName_thenReturnsLocalMarkdown() {
        assertThat(sink.name()).isEqualTo(LocalMarkdownWikiSink.NAME);
        assertThat(LocalMarkdownWikiSink.NAME).isEqualTo("local-markdown");
    }

    @Test
    void whenCreatePageForLanding_thenReadmeAtVaultRootContainsBody() throws IOException {
        String ref = sink.createPage("Atlas — Service Inventory", "# hi", null);

        assertThat(ref).isEqualTo("README.md");
        Path file = vault.resolve("README.md");
        assertThat(Files.readString(file)).isEqualTo("# hi");
    }

    @Test
    void whenCreatePageForService_thenNestedFileWrittenWithMatchingFilename() throws IOException {
        String ref = sink.createPage("Service: atlas-intake", "service body", "README.md");

        assertThat(ref).isEqualTo("services/atlas-intake/atlas-intake.md");
        Path file = vault.resolve("services/atlas-intake/atlas-intake.md");
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.readString(file)).isEqualTo("service body");
    }

    @Test
    void whenUpdatePage_thenContentReplacedAtomically() throws IOException {
        sink.createPage("About Atlas", "v1", null);

        sink.updatePage("about.md", "About Atlas", "v2", null);

        assertThat(Files.readString(vault.resolve("about.md"))).isEqualTo("v2");
        // Atomic move means no leftover .tmp file at the target site.
        try (var stream = Files.newDirectoryStream(vault, "*.tmp")) {
            assertThat(stream.iterator().hasNext()).isFalse();
        }
    }

    @Test
    void whenUpdatePageRefDoesNotExist_thenWikiPageNotFoundExceptionThrown() {
        assertThatThrownBy(() -> sink.updatePage("services/ghost/ghost.md", "irrelevant", "body", null))
                .isInstanceOf(WikiPageNotFoundException.class)
                .hasMessageContaining("services/ghost/ghost.md");
    }

    @Test
    void whenDeletePageOnExistingFile_thenFileRemovedAndEmptyDirsCleanedUp() {
        sink.createPage("Service: atlas-intake", "body", null);

        sink.deletePage("services/atlas-intake/atlas-intake.md");

        assertThat(Files.exists(vault.resolve("services/atlas-intake/atlas-intake.md"))).isFalse();
        // Empty parent dirs cleaned up.
        assertThat(Files.exists(vault.resolve("services/atlas-intake"))).isFalse();
        assertThat(Files.exists(vault.resolve("services"))).isFalse();
    }

    @Test
    void whenDeletePageOnAlreadyMissingFile_thenNoException() {
        // Already-gone refs are treated as success — same contract as
        // ConfluenceClient.deletePage swallowing 404.
        sink.deletePage("services/never-existed/never-existed.md");
    }

    @Test
    void whenDeletePageHasSiblingsInDir_thenSiblingsAndDirSurvive() throws IOException {
        sink.createPage("Service: alpha", "alpha", null);
        sink.createPage("Service: beta", "beta", null);

        sink.deletePage("services/alpha/alpha.md");

        // services/ stays because services/beta/beta.md is still there.
        assertThat(Files.exists(vault.resolve("services"))).isTrue();
        assertThat(Files.exists(vault.resolve("services/beta/beta.md"))).isTrue();
        assertThat(Files.exists(vault.resolve("services/alpha"))).isFalse();
    }

    @Test
    void whenFindPageByTitleForExistingPage_thenReturnsRelativePath() {
        sink.createPage("About Atlas", "body", null);

        Optional<String> ref = sink.findPageByTitle("About Atlas");

        assertThat(ref).contains("about.md");
    }

    @Test
    void whenFindPageByTitleForMissingPage_thenReturnsEmpty() {
        Optional<String> ref = sink.findPageByTitle("About Atlas");

        assertThat(ref).isEmpty();
    }

    @Test
    void whenCreatePageWithNullBody_thenEmptyFileWritten() throws IOException {
        String ref = sink.createPage("About Atlas", null, null);

        assertThat(Files.readString(vault.resolve(ref))).isEmpty();
    }

    @Test
    void whenCreatePageMultipleTimesWithSameTitle_thenOverwriteSilently() throws IOException {
        sink.createPage("About Atlas", "first", null);
        sink.createPage("About Atlas", "second", null);

        assertThat(Files.readString(vault.resolve("about.md"))).isEqualTo("second");
    }

    @Test
    void whenSinkInstantiatedWithBlankPath_thenIllegalStateException() {
        assertThatThrownBy(() -> new LocalMarkdownWikiSink(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LocalMarkdownWikiSink(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void whenWritingUtf8Content_thenContentRoundTripsLossless() throws IOException {
        // mermaid + em-dash + emoji + Cyrillic — the renderers emit
        // some of these regularly. Confirm UTF-8 end-to-end.
        String body = "# Atlas — Service Inventory\n\n```mermaid\nflowchart\n```\nПривет 👋";
        sink.createPage("Atlas — Service Inventory", body, null);

        assertThat(Files.readString(vault.resolve("README.md"), StandardCharsets.UTF_8)).isEqualTo(body);
    }
}
