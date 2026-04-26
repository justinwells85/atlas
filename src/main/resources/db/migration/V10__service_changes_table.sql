-- V10: Append-only audit of changes to service records.
-- Powers the "Change History" section of the Confluence template.
--
-- service_id is intentionally a soft FK (no REFERENCES). The audit row must
-- outlive the audited service: deleting a service must NOT erase its history.
-- The 'before' / 'after' JSON snapshots preserve the deleted service's identity
-- and final state; the 'change_type = deleted' row records the deletion itself.
--
-- Append-only is enforced by application discipline; if needed later, lock it
-- down with revoked UPDATE/DELETE grants on the production role.

CREATE TABLE service_changes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id      UUID NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    changed_by      TEXT,
    change_type     TEXT NOT NULL,
    summary         TEXT,
    before          JSONB,
    after           JSONB,
    CONSTRAINT service_changes_change_type_check
        CHECK (change_type IN ('created', 'updated', 'deleted'))
);

CREATE INDEX idx_service_changes_service_id ON service_changes (service_id);
CREATE INDEX idx_service_changes_changed_at ON service_changes (changed_at);
