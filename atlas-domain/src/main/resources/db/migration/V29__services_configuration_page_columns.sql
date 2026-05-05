-- V29: services.configuration_page_id + services.configuration_markdown_path
-- (Phase 5.9 M4 — closes the per-service Configuration page lifecycle).
--
-- One pair of dual-sink page ref columns per the Phase 5.8 M3 pattern:
--   * services.configuration_page_id  → Confluence sink: stores the
--     numeric Confluence page id for the per-service Configuration page.
--   * services.configuration_markdown_path → local-markdown sink: stores
--     the vault-relative path of the rendered .md file (typically
--     "services/<svc>/configuration.md" per MarkdownPagePathResolver).
--
-- Both columns are nullable; whichever sink is enabled populates its own
-- column independently. Existing rows have NULL in both on day one; the
-- next sync run for that sink fills it in.
--
-- Mirrors V16 (tests_page_id) and V24 (beans_page_id) on the Confluence
-- side, and V25's pattern for the parallel _markdown_path columns. No
-- vendor split — TEXT NULL column additions apply cleanly under both
-- Postgres and MariaDB.

ALTER TABLE services ADD COLUMN configuration_page_id TEXT NULL;
ALTER TABLE services ADD COLUMN configuration_markdown_path TEXT NULL;
