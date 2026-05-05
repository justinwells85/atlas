-- V25: parallel local_markdown_path columns alongside every existing
-- confluence_page_id-equivalent column (Phase 5.8 M3).
--
-- The WikiSink abstraction (Phase 5.8 M1) supports multiple sinks running
-- side-by-side. M2 added Markdown renderers; M3 introduces the
-- LocalMarkdownWikiSink that writes Markdown files to a local vault.
-- Each sink owns its own per-page ref column:
--   * Confluence sink → confluence_page_id (existing) — stores the numeric
--     Confluence page id assigned by the v2 REST API.
--   * Markdown sink → local_markdown_path (new) — stores the vault-relative
--     file path of the rendered .md file (e.g. "services/atlas-intake/atlas-intake.md").
--
-- Both columns coexist on every row; whichever sink is enabled populates
-- its own column independently. Existing rows have a null in the new
-- column on day one; the next sync run for that sink fills it in.
--
-- Append-only tables (apis, service_modules) carry the new column on
-- every observation row, mirroring the existing confluence_page_id
-- carry-forward semantics. Live-view queries that resolve the latest
-- non-tombstone row continue to work unchanged.
--
-- Postgres + MariaDB both accept this DDL as-is — TEXT NULL column
-- additions need no vendor split.

-- Service-level: 3 columns (one per existing *_page_id column).
ALTER TABLE services ADD COLUMN local_markdown_path TEXT NULL;
ALTER TABLE services ADD COLUMN tests_markdown_path TEXT NULL;
ALTER TABLE services ADD COLUMN beans_markdown_path TEXT NULL;

-- Append-only relationship tables: one column each.
ALTER TABLE apis ADD COLUMN local_markdown_path TEXT NULL;
ALTER TABLE service_modules ADD COLUMN local_markdown_path TEXT NULL;
