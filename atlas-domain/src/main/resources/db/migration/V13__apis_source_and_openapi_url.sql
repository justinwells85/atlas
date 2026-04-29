-- V13: Per-source provenance on apis + an OpenAPI spec URL on services.
--
-- See docs/plans/2026-04-29-code-driven-documentation.md for the full
-- shift from human-interview-only to code-derived facts. The provenance
-- column is the design rule that prevents code-sync and intake from
-- silently overwriting each other's rows: code-sync only ever upserts
-- rows it owns (source != 'intake'), intake only writes 'intake' rows.
--
-- Allowed values: 'intake' (today's behaviour), 'openapi' (M1+),
-- 'pom-xml' (M4), 'tests' (M3 — for completeness; tests live in their
-- own table but other code-derived rows may use the same enum).
--
-- DEFAULT 'intake' so the migration is backfill-free: existing rows
-- carry the historically-correct provenance of "a human typed this in".

ALTER TABLE apis ADD COLUMN source TEXT NOT NULL DEFAULT 'intake'
    CHECK (source IN ('intake', 'openapi', 'pom-xml', 'tests'));

CREATE INDEX idx_apis_source ON apis (source);

ALTER TABLE services ADD COLUMN openapi_spec_url TEXT NULL;
