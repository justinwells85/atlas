-- V7: Which services use which databases.
-- is_owner distinguishes the owning service (responsible for schema) from
-- mere users — supports both "Databases used" and "Databases owned/managed"
-- sections of the Confluence template.

CREATE TABLE service_databases (
    id              UUID PRIMARY KEY,
    service_id      UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    database_id     UUID NOT NULL REFERENCES data_stores(id) ON DELETE CASCADE,
    is_owner        BOOLEAN NOT NULL DEFAULT FALSE,
    description     TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT service_databases_pair_unique UNIQUE (service_id, database_id)
);

CREATE INDEX idx_service_databases_service  ON service_databases (service_id);
CREATE INDEX idx_service_databases_database ON service_databases (database_id);
