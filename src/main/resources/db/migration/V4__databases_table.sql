-- V4: Data stores.
-- Independent of services (a DB can be shared by multiple services).
-- Service-DB usage is captured by the service_databases relationship table (V7).

CREATE TABLE databases (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT NOT NULL UNIQUE,
    engine              TEXT,
    description         TEXT,
    owner_team          TEXT,
    data_classification TEXT,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
