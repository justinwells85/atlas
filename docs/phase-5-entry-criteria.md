# Phase 5 Entry Criteria — "Demo-Ready Service Shape"

Phase 5 (End-to-End Validation) picks one real service and runs it through the full pipeline: intake → DB → MCP → Confluence. The rendered page is the demo artifact. A service with sparse data renders to mostly thin notes ("No APIs documented yet."), which is structurally correct but does not demonstrate Atlas's value to a stakeholder.

This document defines the **minimum populated state** that makes a service worth using as the Phase 5 subject. If the chosen service does not meet these criteria, run the intake again with a more substantive participant or add the missing rows directly before kicking off the demo.

## Required services row

All 11 columns in the `services` table (excluding the auto-managed ones — `id`, `created_at`, `updated_at`, `confluence_page_id`, `last_synced_to_confluence`, and `metadata` is optional) populated with non-empty values:

| Column | Drives Confluence section |
|---|---|
| `name` | Page title + Overview |
| `description` | Overview |
| `owner_team` | Overview |
| `status` | Overview |
| `language` | Technical Details |
| `framework` | Technical Details |
| `repo_url` | Technical Details (rendered as link) |
| `deployment` | Technical Details |
| `support_contact` | Operational |
| `sla` | Operational |
| `notes` | Operational |

Optional but improves the Data section: `metadata.data_classification` (any string, e.g., "PII", "internal").

## Required relationship rows

| Table | Minimum | Drives |
|---|---|---|
| `apis` | 2 rows for this service | APIs section (list with method/path/auth) |
| `api_consumers` | 1 row for at least 1 of the above APIs | APIs section ("Consumers: …" line) |
| `service_dependencies` | 1 row where this service is downstream | Dependencies → Upstream Services |
| `service_dependencies` | 1 row where this service is upstream | Dependencies → Downstream Services |
| `service_databases` | 1 row with `is_owner = true` | Dependencies → Databases AND Data → Owned Databases |
| `service_external_deps` | 1 row | Dependencies → External Dependencies |
| `service_changes` | 1 row | Change History → Recent Changes |

The intake interview that ships in Phase 3.5/3.6 captures all of the above. A service that completed intake with substantive answers will meet the criteria; a service registered with mostly-skipped questions will not.

## Verification query

Run this against the local Postgres to check whether a candidate service `<name>` meets all criteria. Returns one row of booleans — every column should be `t`:

```sql
WITH s AS (SELECT * FROM services WHERE name = '<name>')
SELECT
    (SELECT description IS NOT NULL AND owner_team IS NOT NULL AND
            language    IS NOT NULL AND framework  IS NOT NULL AND
            repo_url    IS NOT NULL AND deployment IS NOT NULL AND
            support_contact IS NOT NULL AND sla IS NOT NULL AND
            notes IS NOT NULL FROM s)               AS all_columns,
    (SELECT COUNT(*) >= 2 FROM apis WHERE service_id = (SELECT id FROM s))
                                                    AS at_least_two_apis,
    (SELECT COUNT(*) >= 1 FROM api_consumers ac
        JOIN apis a ON ac.api_id = a.id
        WHERE a.service_id = (SELECT id FROM s))    AS at_least_one_api_consumer,
    (SELECT COUNT(*) >= 1 FROM service_dependencies
        WHERE downstream_service_id = (SELECT id FROM s))
                                                    AS at_least_one_upstream,
    (SELECT COUNT(*) >= 1 FROM service_dependencies
        WHERE upstream_service_id = (SELECT id FROM s))
                                                    AS at_least_one_downstream,
    (SELECT COUNT(*) >= 1 FROM service_databases
        WHERE service_id = (SELECT id FROM s) AND is_owner = true)
                                                    AS at_least_one_owned_db,
    (SELECT COUNT(*) >= 1 FROM service_external_deps
        WHERE service_id = (SELECT id FROM s))      AS at_least_one_external_dep,
    (SELECT COUNT(*) >= 1 FROM service_changes
        WHERE service_id = (SELECT id FROM s))      AS at_least_one_change;
```

## Phase 5 entry checklist

1. Pick a candidate service from the local DB.
2. Run the verification query above. Every boolean is `t`.
3. Trigger sync (`POST /api/sync/run/{serviceId}`) and visually inspect the Confluence page. No section should be a thin note; every section should have substantive content.
4. Make a small DB change (e.g., update the description) and re-sync. Confirm the change appears on the page.
5. Document any rough edges in `docs/deferred-decisions.md`.

If steps 1–4 all pass, Phase 5 is ready to begin. If any step fails, fix the gap (re-run intake, add missing relationship rows directly, or fix a renderer issue) before proceeding.
