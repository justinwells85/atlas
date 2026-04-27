-- V4: Data stores.
-- Independent of services (a DB can be shared by multiple services).
-- Service-DB usage is captured by the service_databases relationship table (V7).
--
-- Table is named data_stores rather than databases because DATABASES is a
-- reserved word in MariaDB. The relationship table V7 keeps the
-- service_databases name and database_id column for human readability.

CREATE TABLE data_stores (
    id                  UUID PRIMARY KEY,
    name                TEXT NOT NULL UNIQUE,
    engine              TEXT,
    description         TEXT,
    owner_team          TEXT,
    data_classification TEXT,
    metadata            JSON NOT NULL DEFAULT '{}',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
