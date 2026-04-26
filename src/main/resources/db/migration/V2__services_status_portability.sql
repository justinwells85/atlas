-- V2: Make services.status portable to MySQL/MariaDB.
-- Replaces the Postgres-only service_status ENUM with a TEXT column constrained
-- by a CHECK predicate. Same SQL behaviour on Postgres and MySQL/MariaDB.
-- See ADR-009 for rationale.

ALTER TABLE services
    ALTER COLUMN status DROP DEFAULT,
    ALTER COLUMN status TYPE TEXT USING status::TEXT,
    ALTER COLUMN status SET DEFAULT 'active';

ALTER TABLE services
    ADD CONSTRAINT services_status_check
    CHECK (status IN ('active', 'deprecated', 'in_dev'));

DROP TYPE service_status;
