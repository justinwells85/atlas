-- V3: APIs exposed by services.
-- Each row is one endpoint (path + method) exposed by exactly one service.
-- Cascade on service delete: orphan APIs are nonsense.

CREATE TABLE apis (
    id              UUID PRIMARY KEY,
    service_id      UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    path            TEXT NOT NULL,
    method          TEXT NOT NULL DEFAULT 'GET',
    description     TEXT,
    auth_method     TEXT,
    metadata        JSON NOT NULL DEFAULT '{}',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT apis_service_path_method_unique UNIQUE (service_id, method, path)
);

CREATE INDEX idx_apis_service_id ON apis (service_id);
