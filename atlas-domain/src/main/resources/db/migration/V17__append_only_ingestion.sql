-- V17: switch ingested data to append-only semantics (M3.5).
--
-- Per the project rule: persist ingested artifacts as observed; updates
-- decorate by appending new observations rather than overwriting. This
-- migration reshapes the apis and service_test_scenarios tables so each
-- refresh inserts new rows tagged with observed_at and presence.
--
--  * observed_at — when the observation was made (one-tick-per-refresh).
--  * presence    — 'present' or 'absent'. Tombstones (presence='absent')
--                  record disappearance from the spec/code; the Confluence
--                  cleanup pass keys off them and nulls confluence_page_id
--                  after deleting the page (mutable bookkeeping is allowed
--                  on the tombstone; the observation itself is immutable).
--
-- Soft-delete (apis.deleted_at, V15) is subsumed by tombstones and dropped.
-- The unique constraints on (service_id, method, path) and the four-column
-- one on service_test_scenarios — which would block multiple observations
-- of the same key — are dropped in vendor-specific V18 migrations
-- (Postgres uses DROP CONSTRAINT; MariaDB uses DROP INDEX).

-- ---- apis ------------------------------------------------------------

ALTER TABLE apis ADD COLUMN observed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
UPDATE apis SET observed_at = COALESCE(updated_at, created_at, observed_at);

ALTER TABLE apis ADD COLUMN presence VARCHAR(8) NOT NULL DEFAULT 'present'
    CHECK (presence IN ('present', 'absent'));

ALTER TABLE apis DROP COLUMN deleted_at;

-- Performance index over (service_id, observed_at) to help the "latest per
-- key" window function's WHERE service_id = ? prefix. Cannot include
-- method/path/source because those are TEXT and would blow past MariaDB's
-- 3072-byte index-key limit. The window function still gets ordered scans
-- per service via this index; partitioning happens in memory.
CREATE INDEX idx_apis_observed_at ON apis (service_id, observed_at);
CREATE INDEX idx_apis_presence ON apis (presence);

-- ---- service_test_scenarios ------------------------------------------

ALTER TABLE service_test_scenarios ADD COLUMN observed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
UPDATE service_test_scenarios SET observed_at = COALESCE(updated_at, created_at, observed_at);

ALTER TABLE service_test_scenarios ADD COLUMN presence VARCHAR(8) NOT NULL DEFAULT 'present'
    CHECK (presence IN ('present', 'absent'));

-- Bounded to (service_id, observed_at) for the same MariaDB-index-length
-- reason; the window function handles the full key partitioning.
CREATE INDEX idx_service_test_scenarios_observed_at
    ON service_test_scenarios (service_id, observed_at);
