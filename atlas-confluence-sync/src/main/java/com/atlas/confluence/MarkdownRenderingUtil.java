package com.atlas.confluence;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared helpers for the Phase 5.8 M2 Markdown renderers. Kept narrow to
 * what is actually reused across two or more renderers; speculative helpers
 * stay out until a third use case demands them.
 *
 * <p>The output dialect is GitHub-Flavored Markdown with Obsidian-friendly
 * extensions: YAML front matter (Properties view), Obsidian WikiLinks
 * ({@code [[Title]]}) for cross-page references, fenced ```mermaid blocks
 * for diagrams.
 */
final class MarkdownRenderingUtil {

    private MarkdownRenderingUtil() {}

    /**
     * Render YAML front matter delimited by {@code ---} fences. Values are
     * written verbatim — they must not contain newlines or unescaped colons
     * in keys; values that would need quoting (timestamps, simple strings)
     * are passed through as-is. {@code null} entries are skipped so callers
     * can build a single ordered map and rely on omission semantics for
     * optional fields like {@code last_synced_at}.
     *
     * <p>Returned string ends with {@code ---\n\n} so the caller can
     * immediately append the H1 title without managing blank lines.
     */
    static String frontMatter(Map<String, Object> kv) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        for (Map.Entry<String, Object> e : kv.entrySet()) {
            if (e.getValue() == null) continue;
            sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        sb.append("---\n\n");
        return sb.toString();
    }

    /**
     * Convenience wrapper for the common case: title + page type + optional
     * last-synced timestamp. Returns an ordered map ready to feed into
     * {@link #frontMatter(Map)}.
     */
    static Map<String, Object> baseFrontMatter(String title, String pageType, OffsetDateTime lastSyncAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("atlas_page_type", pageType);
        m.put("last_synced_at", lastSyncAt);
        return m;
    }

    /**
     * Render an Obsidian WikiLink. With one argument, the link text matches
     * the target page title: {@code [[Service: foo]]}. With two arguments,
     * the second is shown as display text: {@code [[Service: foo|foo]]}.
     * Obsidian resolves the target by filename match across the vault, so
     * page titles within a vault must be unique.
     */
    static String wikiLink(String targetTitle) {
        return "[[" + sanitizeWikiLinkTarget(targetTitle) + "]]";
    }

    static String wikiLink(String targetTitle, String displayText) {
        return "[[" + sanitizeWikiLinkTarget(targetTitle) + "|" + displayText + "]]";
    }

    /**
     * Strip characters Obsidian rejects in WikiLink targets ({@code [ ] |}).
     * Pipes inside a target ambiguate the display-alias separator; brackets
     * close the link prematurely. Replace with safe substitutes.
     */
    private static String sanitizeWikiLinkTarget(String s) {
        if (s == null) return "";
        return s.replace("[", "(")
                .replace("]", ")")
                .replace("|", "/");
    }
}
