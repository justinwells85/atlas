# Atlas — Database Schema

## Design Principles

1. **Hybrid relational + JSON metadata.** Each entity table has structured columns for fields we'll query on, plus a `metadata` JSON column for flexible, evolving attributes.
2. **Portability between Postgres and MySQL/MariaDB.** Application code uses portable JSON access patterns (JPA methods), not Postgres-only operators (`@>`, `?`).
3. **Explicit relationship tables for graph-like behavior.** Service-to-service dependencies, API consumers, and data ownership are modeled as join tables. SQL JOIN traversal without a graph database, with a clear escape hatch to Neo4j later if traversal patterns get complex.
4. **UUID primary keys.** Avoids ID collisions on migration and doesn't leak record counts.
5. **Timestamps in TIMESTAMPTZ.** Always store timezone, never plain timestamp.

## Tables

### Entity Tables ("nouns")

- `services` — the main entity, one row per service
- `apis` — endpoints exposed by services (separate because one service exposes many APIs)
- `databases` — data stores (separate because multiple services can share a DB)
- `external_dependencies` — third-party APIs, tools, SaaS

### Relationship Tables ("graph")

- `service_dependencies` — service-to-service edges (upstream/downstream)
- `service_databases` — which services use which databases
- `api_consumers` — which services call which APIs
- `service_external_deps` — which services rely on which third-party tools

### Audit

- `service_changes` — append-only log of changes to service records, supports the "Change History" section of the Confluence template

## Status

| Table | Status |
|-------|--------|
| services | ✅ DDL complete (V1) |
| apis | ⬜ TBD |
| databases | ⬜ TBD |
| external_dependencies | ⬜ TBD |
| service_dependencies | ⬜ TBD |
| service_databases | ⬜ TBD |
| api_consumers | ⬜ TBD |
| service_external_deps | ⬜ TBD |
| service_changes | ⬜ TBD |

## services Table

The main table. One row per service. DDL lives in `db/migrations/V1__initial_schema.sql`.

```sql
CREATE TYPE service_status AS ENUM ('active', 'deprecated', 'in_dev');

CREATE TABLE services (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                        TEXT NOT NULL UNIQUE,
    description                 TEXT,
    owner_team                  TEXT,
    status                      service_status NOT NULL DEFAULT 'active',
    language                    TEXT,
    framework                   TEXT,
    repo_url                    TEXT,
    deployment                  TEXT,
    support_contact             TEXT,
    sla                         TEXT,
    notes                       TEXT,
    metadata                    JSONB NOT NULL DEFAULT '{}'::jsonb,
    confluence_page_id          TEXT,
    last_synced_to_confluence   TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_services_metadata ON services USING GIN (metadata);
```

### Field Mapping to Confluence Template

| Confluence Section | Atlas Source |
|---|---|
| Overview | `services.name`, `services.description`, `services.owner_team`, `services.status` |
| Technical Details | `services.language`, `services.framework`, `services.repo_url`, `services.deployment` |
| APIs | `apis` (joined on service_id) |
| Dependencies | `service_dependencies`, `service_databases`, `service_external_deps` |
| Data | `databases` (joined), `services.metadata` data classification |
| Operational | `services.support_contact`, `services.sla`, `services.notes` |
| Change History | `services.updated_at`, `service_changes` |

## Open Schema Decisions

These need resolution before V2 migrations are written:

- **`updated_at` auto-update**: Currently a default on insert but doesn't change on row updates. Decide: DB trigger vs. application-level via JPA `@PreUpdate`.
- **`status` enum portability**: Postgres ENUM types don't port directly to MySQL. May need to switch to a CHECK constraint with a TEXT column for portable schema.
- **JSON query patterns**: Spec how the application accesses `metadata`. If we use JPA's portable JSON support, we don't need Postgres-specific operators in code.

## Migrations

Managed via Flyway. Files in `db/migrations/`:

- `V1__initial_schema.sql` — services table

New migrations follow `V<N>__<description>.sql` naming. Never edit a committed migration; create a new one.
