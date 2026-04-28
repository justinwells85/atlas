-- V12: Allow 'reactivated' as a service_changes.change_type value, used by
-- intake when the user re-registers a name held by a soft-deleted row.
-- See ADR-014 (soft-delete pattern) and DD-013 caveat for context.
--
-- Both Postgres and MariaDB 10.2+ support DROP CONSTRAINT / ADD CONSTRAINT
-- on the same statement; portable across both.

ALTER TABLE service_changes DROP CONSTRAINT service_changes_change_type_check;

ALTER TABLE service_changes ADD CONSTRAINT service_changes_change_type_check
    CHECK (change_type IN ('created', 'updated', 'deleted', 'reactivated'));
