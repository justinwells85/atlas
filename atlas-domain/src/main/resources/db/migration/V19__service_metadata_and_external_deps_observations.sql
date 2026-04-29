-- V19: pom.xml ingestion (M4 — code-driven docs).
--
-- Two changes:
--
-- 1. New service_metadata table holds key/value observations of a
--    service's metadata (language, framework, build tool, ...) sourced
--    from intake or any code-sync source. Append-only from day one
--    (M3.5 model): each refresh appends observations, never edits.
--
--    Today the renderer reads services.language and services.framework
--    directly. Pom-derived observations live here in parallel; renderer
--    integration is a follow-up. M5 (interview shrinkage) is the natural
--    place to migrate intake to write here too and have services.language /
--    services.framework go away.
--
-- 2. service_external_deps gains observed_at + presence so pom-sourced
--    external-dep observations append rather than overwrite. The unique
--    constraint on (service_id, external_dependency_id) gets dropped via
--    a vendor-split V20 (Postgres uses DROP CONSTRAINT, MariaDB uses
--    DROP INDEX) — same pattern as V18 for apis.
--
-- A service_external_deps.source column is added with default 'intake'
-- so existing rows are tagged historically; it's CHECK-constrained over
-- the same vocabulary as apis.source.

-- ---- service_metadata ------------------------------------------------

CREATE TABLE service_metadata (
    id              UUID PRIMARY KEY,
    service_id      UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    metadata_key    VARCHAR(64) NOT NULL,
    metadata_value  TEXT,
    source          VARCHAR(16) NOT NULL DEFAULT 'pom-xml'
                    CHECK (source IN ('intake', 'openapi', 'pom-xml', 'tests')),
    presence        VARCHAR(8) NOT NULL DEFAULT 'present'
                    CHECK (presence IN ('present', 'absent')),
    observed_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_service_metadata_service_id ON service_metadata (service_id, observed_at);
CREATE INDEX idx_service_metadata_presence ON service_metadata (presence);

-- ---- service_external_deps ------------------------------------------

ALTER TABLE service_external_deps ADD COLUMN observed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
UPDATE service_external_deps SET observed_at = COALESCE(updated_at, created_at, observed_at);

ALTER TABLE service_external_deps ADD COLUMN presence VARCHAR(8) NOT NULL DEFAULT 'present'
    CHECK (presence IN ('present', 'absent'));

ALTER TABLE service_external_deps ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'intake'
    CHECK (source IN ('intake', 'openapi', 'pom-xml', 'tests'));

CREATE INDEX idx_service_external_deps_observed_at ON service_external_deps (service_id, observed_at);
