-- V24: services.beans_page_id (Phase 5.6 M3).
--
-- One Beans Confluence page per service. Tracked on the services row,
-- mirrors the existing services.tests_page_id (V16). Lifecycle is the
-- same: create on first sync, update on subsequent syncs, page persists
-- even when the service has zero stereotype classes (renders a thin
-- "no beans documented" note for sidebar-tree consistency — same as the
-- Tests page convention).

ALTER TABLE services ADD COLUMN beans_page_id TEXT NULL;
