-- V11: Add a soft-delete column to services so the Confluence sync agent
-- can find pages that need to be deleted from Confluence after the source
-- service is removed from the inventory.
--
-- See docs/deferred-decisions.md DD-013 for the full rationale and
-- ADR-014 for the soft-delete pattern used here.
--
-- Hibernate's @SQLDelete on the Service entity rewrites delete() calls into
-- UPDATE services SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?, and
-- @SQLRestriction filters all JPA queries to deleted_at IS NULL by default.
-- The sync coordinator's cleanup pass uses a native query to bypass the
-- filter and find rows whose Confluence pages still need deletion.
--
-- Known prototype gotcha: services.name carries a UNIQUE constraint, so a
-- soft-deleted row with name='foo' prevents a fresh INSERT with the same
-- name. Acceptable at prototype scale (renaming is rare); production-team
-- can revisit if name reuse becomes a real workflow.

ALTER TABLE services ADD COLUMN deleted_at TIMESTAMP NULL;

CREATE INDEX idx_services_deleted_at ON services (deleted_at);
