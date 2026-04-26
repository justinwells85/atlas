-- V6: Directed service-to-service dependency edges.
-- Reading: "downstream depends on upstream" / "upstream serves downstream".
-- A service may not depend on itself (CHECK), and the same edge may not appear
-- twice (UNIQUE). Cascade on either side: removing a service drops its edges.

CREATE TABLE service_dependencies (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    upstream_service_id     UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    downstream_service_id   UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    description             TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT service_dependencies_no_self_edge
        CHECK (upstream_service_id <> downstream_service_id),
    CONSTRAINT service_dependencies_pair_unique
        UNIQUE (upstream_service_id, downstream_service_id)
);

CREATE INDEX idx_service_dependencies_upstream   ON service_dependencies (upstream_service_id);
CREATE INDEX idx_service_dependencies_downstream ON service_dependencies (downstream_service_id);
