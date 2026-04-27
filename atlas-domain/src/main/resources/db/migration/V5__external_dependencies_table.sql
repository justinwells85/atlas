-- V5: Third-party APIs, SaaS, and tools that services depend on.
-- Service usage is captured by the service_external_deps relationship table (V9).

CREATE TABLE external_dependencies (
    id              UUID PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    description     TEXT,
    url             TEXT,
    metadata        JSON NOT NULL DEFAULT '{}',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
