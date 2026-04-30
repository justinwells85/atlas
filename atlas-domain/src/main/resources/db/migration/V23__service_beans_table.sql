-- V23: Spring stereotype bean index (Phase 5.6 M3 — L5 of the drill-down).
--
-- One row per observation of a Spring stereotype-annotated class found in
-- a service's source tree. Append-only from day one: each refresh appends,
-- never updates; disappearance is recorded as a presence='absent' tombstone.
-- Same shape as service_metadata (V19) and service_modules (V22) — the
-- "latest per key where presence='present'" live-view query reuses without
-- changes.
--
-- Stereotype scope (Phase 5.6 plan, M3 open question 1, resolved Option A):
--   * @RestController, @Controller, @Service, @Repository, @Component,
--     @Configuration
--   * Excluded: DTOs / POJOs / enums / @Entity / Spring Data interfaces /
--     @ConfigurationProperties
-- Widening to those classes is a deferred decision (DD-015 candidate); the
-- regenerate-on-every-sync model means widening costs no migration.
--
-- public_methods is a JSON array of {name, signature, javadoc} objects
-- captured from the AST. Stored as TEXT-carrying-JSON for the same reasons
-- V21/V22 chose TEXT for the snapshot/declared-deps columns: we never query
-- inside, and TEXT applies cleanly under both Postgres and MariaDB.

CREATE TABLE service_beans (
    id                       UUID PRIMARY KEY,
    service_id               UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    module_path              VARCHAR(191) NOT NULL,
    package_name             VARCHAR(191) NOT NULL,
    class_name               VARCHAR(191) NOT NULL,
    stereotype               VARCHAR(32) NOT NULL
                             CHECK (stereotype IN ('RestController', 'Controller',
                                                    'Service', 'Repository',
                                                    'Component', 'Configuration')),
    class_javadoc_summary    TEXT,
    public_methods           TEXT,
    source                   VARCHAR(16) NOT NULL DEFAULT 'source-tree'
                             CHECK (source IN ('intake', 'openapi', 'pom-xml',
                                               'tests', 'source-tree')),
    presence                 VARCHAR(8) NOT NULL DEFAULT 'present'
                             CHECK (presence IN ('present', 'absent')),
    confluence_page_id       TEXT,
    observed_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_service_beans_service ON service_beans (service_id);
