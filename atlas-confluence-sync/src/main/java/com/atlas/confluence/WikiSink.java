package com.atlas.confluence;

import java.util.Optional;

/**
 * Sink-agnostic abstraction for wiki publication. The Atlas sync coordinator
 * iterates over every enabled {@code WikiSink} bean and dispatches the same
 * logical create / update / delete / lookup calls to each — Confluence Cloud
 * today, local Markdown (Phase 5.8 M3) next, possibly other backends later.
 *
 * Each sink owns its own scope (Confluence space; vault root directory; etc.)
 * and its own page-reference convention: {@code String} refs are opaque to
 * the coordinator. For Confluence the ref is a numeric page id; for the
 * forthcoming Markdown sink it will be a relative file path.
 *
 * Implementations should:
 * <ul>
 *   <li>Throw {@link WikiPageNotFoundException} from {@link #updatePage} when
 *       the referenced page is gone — the coordinator catches this and
 *       recovers by creating a fresh page.</li>
 *   <li>Treat {@link #deletePage} of an already-deleted ref as success (the
 *       end state is what matters).</li>
 *   <li>Be idempotent under retry where possible.</li>
 * </ul>
 */
public interface WikiSink {

    /**
     * Stable identifier for this sink, used by the coordinator for routing
     * (e.g. determining which sink's ref populates which schema column) and
     * by logs. Examples: {@code "confluence"}, {@code "local-markdown"}.
     * Names should be short, lowercase, hyphen-separated, and unique across
     * all enabled sinks in a given Atlas instance.
     */
    String name();

    /**
     * Create a new page in this sink. The returned ref is what the
     * coordinator passes to {@link #updatePage} or {@link #deletePage} on
     * subsequent calls. Pass {@code parentRef = null} for a root page.
     */
    String createPage(String title, String body, String parentRef);

    /**
     * Update an existing page identified by {@code ref}. Throws
     * {@link WikiPageNotFoundException} if the ref no longer resolves —
     * the coordinator's recovery path catches this and recreates.
     * Pass {@code parentRef = null} to leave the parent unchanged (where
     * meaningful for the sink); pass a non-null value to re-parent.
     */
    void updatePage(String ref, String title, String body, String parentRef);

    /**
     * Delete a page by ref. Implementations must treat "already deleted"
     * as success — the cleanup pass runs against pages that may have been
     * removed manually since the last sync.
     */
    void deletePage(String ref);

    /**
     * Look up a page by exact title within this sink's scope. Returns the
     * sink-specific ref if found, {@link Optional#empty()} otherwise. Used
     * to resolve well-known pages (landing, inventory, about, architecture
     * map) without persisting their refs in Atlas's DB — the sink itself
     * is the source of truth.
     */
    Optional<String> findPageByTitle(String title);
}
