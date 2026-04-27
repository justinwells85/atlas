-- V2: Superseded by the rewritten V1 (services.status is now TEXT + CHECK
-- from the start). This migration originally converted the column from a
-- Postgres ENUM to portable TEXT (ADR-009); the rewrite in Phase 5.5
-- (DD-002) folded that change into V1 directly. Kept as a no-op so
-- Flyway's version sequence stays continuous.

SELECT 1;
