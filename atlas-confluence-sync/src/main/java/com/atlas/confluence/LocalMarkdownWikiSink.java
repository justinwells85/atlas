package com.atlas.confluence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * {@link WikiSink} backed by the local filesystem (Phase 5.8 M3). Writes
 * Markdown files into a configured vault directory, designed for browsing
 * in Obsidian. Refs returned to the coordinator are vault-relative paths
 * (e.g. {@code services/atlas-intake/atlas-intake.md}).
 *
 * <p>Atomic write: every page is written to a {@code .tmp} sibling and
 * then moved to the final path with {@link StandardCopyOption#ATOMIC_MOVE}
 * + {@link StandardCopyOption#REPLACE_EXISTING}. Obsidian watching the
 * vault sees one consistent file at a time, never a partial read.
 *
 * <p>Path derivation lives in {@link MarkdownPagePathResolver} — title
 * patterns map deterministically to relative paths so the sink never
 * needs the coordinator to pre-compute paths.
 *
 * <p>Activates only when {@code atlas.wiki.sinks.local-markdown.enabled=true}.
 * The vault path is mandatory ({@code atlas.wiki.sinks.local-markdown.path}).
 */
@Component
@ConditionalOnProperty(
        prefix = "atlas.wiki.sinks.local-markdown",
        name = "enabled",
        havingValue = "true")
public class LocalMarkdownWikiSink implements WikiSink {

    public static final String NAME = "local-markdown";

    private static final Logger log = LoggerFactory.getLogger(LocalMarkdownWikiSink.class);

    private final Path vaultRoot;

    public LocalMarkdownWikiSink(@Value("${atlas.wiki.sinks.local-markdown.path}") String pathConfig) {
        if (pathConfig == null || pathConfig.isBlank()) {
            throw new IllegalStateException(
                    "atlas.wiki.sinks.local-markdown.path must be set when the local-markdown sink is enabled");
        }
        this.vaultRoot = Path.of(pathConfig).toAbsolutePath().normalize();
        log.info("LocalMarkdownWikiSink initialised; vault root: {}", vaultRoot);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String createPage(String title, String body, String parentRef) {
        String relPath = MarkdownPagePathResolver.pathFor(title);
        writeAtomically(resolveSafe(relPath), body);
        return relPath;
    }

    @Override
    public void updatePage(String ref, String title, String body, String parentRef) {
        // Path derivation is deterministic from title — but we honour the
        // stored ref if it differs (e.g. service was renamed: old ref
        // points at the previous path, new derived path is at the new
        // location). For M3 we treat them as identical; renames land in
        // a future polish phase.
        Path target = resolveSafe(ref);
        if (!Files.exists(target)) {
            throw new WikiPageNotFoundException(ref, null);
        }
        writeAtomically(target, body);
    }

    @Override
    public void deletePage(String ref) {
        Path target = resolveSafe(ref);
        try {
            Files.deleteIfExists(target);
            // Best-effort cleanup of empty parent directories so the vault
            // doesn't accumulate orphan folders. Stops at vaultRoot.
            Path parent = target.getParent();
            while (parent != null && !parent.equals(vaultRoot) && parent.startsWith(vaultRoot)) {
                try (var stream = Files.newDirectoryStream(parent)) {
                    if (stream.iterator().hasNext()) break; // non-empty
                }
                Files.delete(parent);
                parent = parent.getParent();
            }
        } catch (IOException e) {
            // "Already gone" + "directory removal failed" both end up as
            // best-effort no-ops — the page-delete contract says treat
            // already-deleted refs as success.
            log.debug("deletePage({}) IOException ignored: {}", ref, e.getMessage());
        }
    }

    @Override
    public Optional<String> findPageByTitle(String title) {
        String relPath = MarkdownPagePathResolver.pathFor(title);
        Path target = resolveSafe(relPath);
        return Files.exists(target) ? Optional.of(relPath) : Optional.empty();
    }

    /**
     * Resolve a vault-relative path against the vault root and verify it
     * stays inside the vault — defence against path-traversal in (likely
     * impossible in practice, but cheap to enforce).
     */
    private Path resolveSafe(String relPath) {
        Path resolved = vaultRoot.resolve(relPath).normalize();
        if (!resolved.startsWith(vaultRoot)) {
            throw new IllegalArgumentException(
                    "Resolved path escapes vault root: " + relPath);
        }
        return resolved;
    }

    private void writeAtomically(Path target, String body) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, body == null ? "" : body, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Some filesystems (older NFS, Windows network shares) don't
                // support atomic move across rename — fall back to
                // non-atomic replace, which is still better than partial reads.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + target, e);
        }
    }
}
