-- V3: APIs exposed by services.
-- Each row is one endpoint (path + method) exposed by exactly one service.
-- Cascade on service delete: orphan APIs are nonsense.

CREATE TABLE apis (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id      UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    path            TEXT NOT NULL,
    method          TEXT NOT NULL DEFAULT 'GET',
    description     TEXT,
    auth_method     TEXT,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT apis_service_path_method_unique UNIQUE (service_id, method, path)
);

CREATE INDEX idx_apis_service_id ON apis (service_id);
