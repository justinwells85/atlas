-- V16: Test-method extraction (M3 — code-driven docs).
--
-- Each row is one @Test-annotated method on a class in the service's
-- src/test/java tree. Rendered into a per-service "What this service
-- guarantees" Confluence page (M3) — driven by the project rule that
-- test names read as specifications (CLAUDE.md §4).
--
-- services.module_path tells code-sync where in the repo to look (e.g.,
-- "atlas-intake" for Atlas's multi-module dogfood). Null/empty means
-- repo root for single-module repos.
--
-- services.tests_page_id mirrors confluence_page_id at the per-service-
-- tests-page grain, parented under the service page.
--
-- service_test_scenarios.source uses the same per-source provenance
-- vocabulary as apis.source (intake | openapi | pom-xml | tests). Only
-- 'tests' is meaningful for now; the constraint accepts the others so
-- humans can override via SQL if the workflow ever needs it.

ALTER TABLE services ADD COLUMN module_path TEXT NULL;
ALTER TABLE services ADD COLUMN tests_page_id TEXT NULL;

-- Identifier columns are VARCHAR(191), not TEXT, so the four-column unique
-- index fits inside MariaDB's 3072-byte index-key limit at utf8mb4
-- (191 * 4 = 764 bytes per column, plus the UUID, totals ~2.5KB). Java
-- package/class/method names well over 191 chars don't occur in practice.
CREATE TABLE service_test_scenarios (
    id              UUID PRIMARY KEY,
    service_id      UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    package_name    VARCHAR(191) NOT NULL DEFAULT '',
    class_name      VARCHAR(191) NOT NULL,
    method_name     VARCHAR(191) NOT NULL,
    source          VARCHAR(16) NOT NULL DEFAULT 'tests'
                    CHECK (source IN ('intake', 'openapi', 'pom-xml', 'tests')),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT service_test_scenarios_unique UNIQUE (service_id, package_name, class_name, method_name)
);

CREATE INDEX idx_service_test_scenarios_service_id ON service_test_scenarios (service_id);
CREATE INDEX idx_service_test_scenarios_class ON service_test_scenarios (service_id, class_name);
