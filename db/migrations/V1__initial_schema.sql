-- V1: Initial schema for Atlas service documentation system.
-- Creates the core `services` table.

-- Status enum: locks the column to valid values at the DB level.
-- Note: Postgres ENUM types don't port directly to MySQL. To be revisited
-- in a portability-focused migration before production cutover.
CREATE TYPE service_status AS ENUM ('active', 'deprecated', 'in_dev');

-- Main services table. One row per service.
CREATE TABLE services (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                        TEXT NOT NULL UNIQUE,
    description                 TEXT,
    owner_team                  TEXT,
    status                      service_status NOT NULL DEFAULT 'active',
    language                    TEXT,
    framework                   TEXT,
    repo_url                    TEXT,
    deployment                  TEXT,
    support_contact             TEXT,
    sla                         TEXT,
    notes                       TEXT,
    metadata                    JSONB NOT NULL DEFAULT '{}'::jsonb,
    confluence_page_id          TEXT,
    last_synced_to_confluence   TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- GIN index on metadata for fast JSONB containment queries.
-- Application code should query JSON via JPA portable methods, not
-- Postgres-specific operators, to maintain MySQL/MariaDB portability.
CREATE INDEX idx_services_metadata ON services USING GIN (metadata);
