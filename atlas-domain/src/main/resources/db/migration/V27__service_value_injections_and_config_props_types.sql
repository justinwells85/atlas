-- V27: @Value injection sites + @ConfigurationProperties types (Phase 5.9 M2).
--
-- Two parallel append-only-from-day-one tables, mirroring the V22 / V23 / V26
-- shape (observed_at + presence + window-function "latest per key" live view,
-- never UPDATE / DELETE on observation rows). Both populate from the same
-- AST-walking extractor (JavaConfigurationExtractor) at refresh time.
--
-- service_value_injections — one row per @Value("(SpEL)") site found in the
-- source tree (field, constructor parameter, method parameter, setter
-- parameter). Composite identity for the live view:
--   (service_id, module_path, enclosing_class, member_name, member_kind)
-- where member_kind disambiguates the rare case of a class with both a
-- constructor parameter and a setter parameter sharing a name. raw_spel
-- carries the verbatim SpEL expression; key_path is the resolved key after
-- stripping the optional default suffix; default_value is the part after
-- the colon, NULL if absent.
--
-- service_configuration_properties_types — one row per class / record
-- annotated @ConfigurationProperties(prefix=...). Composite identity:
--   (service_id, module_path, enclosing_class)
-- prefix is "" for prefix-less @ConfigurationProperties (the annotation
-- accepts no prefix). type_kind is 'class' / 'record' (also captures
-- 'interface' for forward-compat though Spring Boot doesn't bind to
-- interfaces today).
--
-- components is a JSON-string TEXT column carrying [{name, declaredType}, …]
-- for the type's declared components — record components for a record;
-- bean property fields (or setter-bound fields) for a class. Stored as TEXT
-- carrying JSON for the same reasons V21 / V22 / V23 chose TEXT for
-- snapshot / declared-deps / public_methods: Atlas never queries inside it,
-- and TEXT applies cleanly under both Postgres and MariaDB. Keeping the
-- child shape in JSON sidesteps the lifecycle coupling a separate child
-- table would introduce (a tombstone on the parent observation would orphan
-- the child rows; a JSON column captures the whole component set atomically
-- with the observation).

CREATE TABLE service_value_injections (
    id                UUID PRIMARY KEY,
    service_id        UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    module_path       VARCHAR(191) NOT NULL,
    enclosing_class   VARCHAR(191) NOT NULL,
    member_name       VARCHAR(191) NOT NULL,
    member_kind       VARCHAR(24) NOT NULL
                      CHECK (member_kind IN ('field', 'constructor-parameter',
                                              'method-parameter', 'setter-parameter')),
    raw_spel          TEXT NOT NULL,
    key_path          VARCHAR(191) NOT NULL,
    default_value     TEXT,
    source            VARCHAR(16) NOT NULL DEFAULT 'source-tree'
                      CHECK (source IN ('intake', 'openapi', 'pom-xml', 'tests',
                                         'source-tree', 'properties-file')),
    presence          VARCHAR(8) NOT NULL DEFAULT 'present'
                      CHECK (presence IN ('present', 'absent')),
    observed_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_service_value_injections_service ON service_value_injections (service_id);

CREATE TABLE service_configuration_properties_types (
    id                UUID PRIMARY KEY,
    service_id        UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    module_path       VARCHAR(191) NOT NULL,
    enclosing_class   VARCHAR(191) NOT NULL,
    prefix            VARCHAR(191) NOT NULL,
    type_kind         VARCHAR(16) NOT NULL
                      CHECK (type_kind IN ('class', 'record', 'interface')),
    components        TEXT,
    source            VARCHAR(16) NOT NULL DEFAULT 'source-tree'
                      CHECK (source IN ('intake', 'openapi', 'pom-xml', 'tests',
                                         'source-tree', 'properties-file')),
    presence          VARCHAR(8) NOT NULL DEFAULT 'present'
                      CHECK (presence IN ('present', 'absent')),
    observed_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_service_config_props_types_service ON service_configuration_properties_types (service_id);
