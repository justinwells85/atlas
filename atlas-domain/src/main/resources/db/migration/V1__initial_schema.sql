-- V1: Initial schema for Atlas service documentation system.
-- Creates the core `services` table.
--
-- Portable across PostgreSQL and MySQL/MariaDB. The Postgres-specific
-- constructs from the original prototype draft were rewritten in Phase 5.5
-- (DD-002) — see docs/schema.md for the prototype-stage exception to the
-- "never edit a committed migration" rule.
--
--   * UUIDs: no DB-side default. Hibernate generates via @GeneratedValue;
--     direct JdbcTemplate inserts pass UUID.randomUUID() explicitly.
--   * status: TEXT + CHECK from the start (ADR-009). The original V1 used
--     a Postgres ENUM type and V2 migrated it to TEXT; V1 now does the
--     final shape directly and V2 is a no-op.
--   * metadata: JSON (the standard type both engines support); JSONB and
--     GIN indexing were Postgres-only and unused by application code per
--     ADR-010 (filter in Java, not in WHERE).
--   * timestamps: TIMESTAMP + CURRENT_TIMESTAMP (ANSI; works on both).

CREATE TABLE services (
    id                          UUID PRIMARY KEY,
    name                        TEXT NOT NULL UNIQUE,
    description                 TEXT,
    owner_team                  TEXT,
    status                      TEXT NOT NULL DEFAULT 'active'
                                CHECK (status IN ('active', 'deprecated', 'in_dev')),
    language                    TEXT,
    framework                   TEXT,
    repo_url                    TEXT,
    deployment                  TEXT,
    support_contact             TEXT,
    sla                         TEXT,
    notes                       TEXT,
    metadata                    JSON NOT NULL DEFAULT '{}',
    confluence_page_id          TEXT,
    last_synced_to_confluence   TIMESTAMP,
    created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
