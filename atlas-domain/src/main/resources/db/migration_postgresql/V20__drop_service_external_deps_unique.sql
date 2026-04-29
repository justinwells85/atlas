-- V20 (Postgres): drop the unique constraint on service_external_deps
-- so multiple observations of the same (service, external_dep) pair can
-- coexist over time, distinguished by observed_at and source. M4 / M3.5.

ALTER TABLE service_external_deps DROP CONSTRAINT service_external_deps_pair_unique;
