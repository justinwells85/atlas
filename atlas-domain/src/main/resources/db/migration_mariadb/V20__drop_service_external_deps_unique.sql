-- V20 (MariaDB): drop the unique index on service_external_deps so
-- multiple observations of the same (service, external_dep) pair can
-- coexist over time. MariaDB stores inline-named UNIQUE constraints as
-- indexes; DROP INDEX is the reliable form. Postgres has the matching
-- DROP CONSTRAINT in db/migration_postgresql/. M4 / M3.5.

ALTER TABLE service_external_deps DROP INDEX service_external_deps_pair_unique;
