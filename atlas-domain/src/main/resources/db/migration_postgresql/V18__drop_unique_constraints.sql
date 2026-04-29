-- V18 (Postgres): drop the unique constraints that block multiple observations
-- of the same key under append-only ingestion (M3.5). See V17 for the rest of
-- the append-only switch.
--
-- Postgres uses DROP CONSTRAINT for named UNIQUE constraints, which
-- automatically removes the underlying index. MariaDB has a separate
-- migration in db/migration/mariadb/V18__drop_unique_constraints.sql that
-- uses DROP INDEX (the name is registered there as an index, not a
-- catalog constraint, before MariaDB 10.5 — keeping the two paths separate
-- avoids runtime feature detection).

ALTER TABLE apis DROP CONSTRAINT apis_service_path_method_unique;
ALTER TABLE service_test_scenarios DROP CONSTRAINT service_test_scenarios_unique;
