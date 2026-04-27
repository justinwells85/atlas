-- V9: Which services rely on which third-party tools.

CREATE TABLE service_external_deps (
    id                          UUID PRIMARY KEY,
    service_id                  UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    external_dependency_id      UUID NOT NULL REFERENCES external_dependencies(id) ON DELETE CASCADE,
    description                 TEXT,
    created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT service_external_deps_pair_unique
        UNIQUE (service_id, external_dependency_id)
);

CREATE INDEX idx_service_external_deps_service ON service_external_deps (service_id);
CREATE INDEX idx_service_external_deps_dep     ON service_external_deps (external_dependency_id);
