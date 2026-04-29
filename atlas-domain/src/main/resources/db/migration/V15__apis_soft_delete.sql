-- V15: soft-delete column on apis so the Confluence sync can clean up
-- per-endpoint pages when code-sync drops a row that no longer matches the
-- spec. Mirrors ADR-014's services.deleted_at pattern, plus its weakness:
-- the unique constraint on (service_id, method, path) still applies across
-- soft-deleted rows, so re-inserting an endpoint that was just removed will
-- fail until the cleanup pass nulls confluence_page_id and the row is
-- hard-cleared (or until intake-style reactivation lands — see M2.5 in
-- docs/plans/2026-04-29-code-driven-documentation.md). Acceptable at
-- prototype scale; production may want a partial unique index
-- (Postgres-only) or a reactivate-on-collision pattern in code-sync.

ALTER TABLE apis ADD COLUMN deleted_at TIMESTAMP NULL;

CREATE INDEX idx_apis_deleted_at ON apis (deleted_at);
