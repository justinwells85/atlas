-- V5: Third-party APIs, SaaS, and tools that services depend on.
-- Service usage is captured by the service_external_deps relationship table (V9).

CREATE TABLE external_dependencies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL UNIQUE,
    description     TEXT,
    url             TEXT,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
