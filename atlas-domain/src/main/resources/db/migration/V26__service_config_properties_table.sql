-- V26: Configuration property keys (Phase 5.9 M1).
--
-- One row per observation of a property key declared in a service's
-- `application.properties` / `application.yml` / profile-specific override
-- (`application-{profile}.properties`, `application-{profile}.yml`,
-- `application-{profile}.yaml`). Append-only from day one: each refresh
-- appends, never updates; disappearance is recorded as a presence='absent'
-- tombstone observation. Same shape as service_modules (V22) and
-- service_beans (V23) — the "latest per (service_id, key_path, profile)
-- where presence='present'" live-view query reuses the existing pattern.
--
-- The composite identity for a property is (key_path, profile, source_file):
--
--   * key_path is the dot-joined Spring-Boot key (e.g. `spring.datasource.url`).
--     YAML nested maps flatten on dot-join before insert.
--   * profile is the source-file profile suffix or 'default' for plain
--     application.properties / application.yml (NEVER NULL — keeps the
--     latest-per-key partition key simple, mirroring V22's empty-string
--     module_path convention).
--   * source_file is the filename relative to the service's module-path
--     resources root (e.g. `application.yml`, `application-prod.properties`).
--     Lets two profile files declaring the same key both be observed.
--
-- value carries the raw string from the source file. YAML scalar values are
-- captured as their stringified form; null / missing values are stored as
-- the empty string so the column stays NOT NULL (Spring Boot itself treats
-- `key=` as the empty-string default).
--
-- spring.config.import chained imports are NOT followed (DD-016). Spring
-- Boot relaxed-binding aliases (my.app.url ≡ MY_APP_URL) are NOT normalised
-- (DD-017). Keys are recorded verbatim.

CREATE TABLE service_config_properties (
    id           UUID PRIMARY KEY,
    service_id   UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    key_path     VARCHAR(191) NOT NULL,
    value        TEXT NOT NULL,
    source_file  VARCHAR(255) NOT NULL,
    profile      VARCHAR(64) NOT NULL DEFAULT 'default',
    source       VARCHAR(16) NOT NULL DEFAULT 'properties-file'
                 CHECK (source IN ('intake', 'openapi', 'pom-xml', 'tests',
                                    'source-tree', 'properties-file')),
    presence     VARCHAR(8) NOT NULL DEFAULT 'present'
                 CHECK (presence IN ('present', 'absent')),
    observed_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_service_config_properties_service ON service_config_properties (service_id);
