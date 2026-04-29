-- V18 (MariaDB): drop the unique indexes that block multiple observations
-- of the same key under append-only ingestion (M3.5). See V17 for the rest
-- of the append-only switch.
--
-- MariaDB stores inline-named UNIQUE constraints from CREATE TABLE as
-- indexes (not catalog constraints, prior to 10.5+ behavior with named
-- constraint records). DROP INDEX is the reliable cross-version form.
-- Postgres has a separate migration in db/migration/postgresql/ that
-- uses DROP CONSTRAINT.

ALTER TABLE apis DROP INDEX apis_service_path_method_unique;
ALTER TABLE service_test_scenarios DROP INDEX service_test_scenarios_unique;
