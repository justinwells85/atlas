-- V14: Track each API endpoint's Confluence page id so per-endpoint pages
-- can be addressed (updated, eventually deleted) across syncs.
--
-- Mirrors services.confluence_page_id but at the endpoint grain. Filled
-- in by atlas-confluence-sync's SyncCoordinator on first creation; null
-- means "no page exists yet — sync will create one".
--
-- Orphan cleanup (when an api row is removed by code-sync) is deliberately
-- not handled in this migration — the page is left in Confluence until a
-- follow-up adds soft-delete on apis or a list-children orphan pass.
-- See docs/plans/2026-04-29-code-driven-documentation.md M2 scope decision.

ALTER TABLE apis ADD COLUMN confluence_page_id TEXT NULL;
