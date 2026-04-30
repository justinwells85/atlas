-- V22: Maven module tree (Phase 5.6 M2 — L4 of the drill-down).
--
-- One row per observation of a Maven module within a service. Append-only
-- from day one: each refresh appends, never updates; disappearance is
-- recorded as a presence='absent' tombstone observation that carries
-- forward the previous confluence_page_id so the sync coordinator can
-- find and delete the orphan page (mutable bookkeeping, immutable
-- observation — same pattern as apis since M3.5).
--
-- Module path semantics:
--
--   * module_path is RELATIVE to the service's services.module_path root.
--     The root module of a multi-module service has module_path='' (empty
--     string, never NULL — keeps the partition key simple).
--   * parent_path identifies the parent module's module_path, or NULL for
--     the root.
--
-- For Atlas itself, each registered service (atlas-domain, atlas-intake,
-- atlas-mcp, atlas-confluence-sync) is a leaf — services.module_path
-- already points at the leaf pom, so each will produce one root-module
-- observation with module_path='' and no children. The richer payoff is
-- for a real multi-module service (e.g., billing-service with
-- billing-api / billing-core / billing-persistence sub-modules).
--
-- declared_deps is a JSON-string column carrying the list of declared
-- "groupId:artifactId" coords for THIS module (not the union across the
-- tree — per-module-page rendering shows what this specific pom declared).
-- Stored as TEXT carrying JSON for the same reasons V21 chose TEXT for
-- apis.openapi_snapshot: Atlas never queries inside it, and TEXT applies
-- cleanly under both Postgres and MariaDB without driver-specific binding
-- ceremony.

CREATE TABLE service_modules (
    id                  UUID PRIMARY KEY,
    service_id          UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    module_path         VARCHAR(191) NOT NULL,
    parent_path         VARCHAR(191),
    group_id            VARCHAR(255),
    artifact_id         VARCHAR(255),
    version             VARCHAR(64),
    packaging           VARCHAR(32),
    language_version    VARCHAR(32),
    framework           VARCHAR(64),
    framework_version   VARCHAR(64),
    declared_deps       TEXT,
    source              VARCHAR(16) NOT NULL DEFAULT 'pom-xml'
                        CHECK (source IN ('intake', 'openapi', 'pom-xml', 'tests')),
    presence            VARCHAR(8) NOT NULL DEFAULT 'present'
                        CHECK (presence IN ('present', 'absent')),
    confluence_page_id  TEXT,
    observed_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_service_modules_service ON service_modules (service_id);
