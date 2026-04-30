# Atlas — Confluence Space Layout

How Atlas-generated content is organised in Confluence. This document covers the **space-wide hierarchy**, the **landing page**, and the **inventory + About sub-pages**. For the field-level layout of an individual service page, see `confluence-template.md`.

> **Status (Phase 4.5)**: items below are implemented as described, *except* orphan-page deletion when a service is removed from the Atlas DB. That item is deferred — see `deferred-decisions.md` DD-013.

## Page Hierarchy

```
Atlas — Service Inventory                ← overview / landing page (parented under space home)
├── About Atlas                          ← what this space is, how to add a service
├── Inventory: Data Stores               ← per-resource view of data_stores
├── Inventory: External Dependencies     ← per-resource view of external_dependencies
├── Service: <service-name-A>            ← one sub-page per service (L2)
│   ├── <service-A> — METHOD /path-1     ← L3 endpoint pages (one per apis row, source='openapi')
│   ├── <service-A> — METHOD /path-2
│   ├── <service-A> — Module: <root>     ← L4 module pages (one per service_modules row)
│   │   ├── <service-A> — Module: <sub>  ← nested per pom <modules> tree
│   │   └── ...
│   ├── <service-A> — Beans              ← L5 beans/class index page (one per service)
│   └── <service-A> — Tests              ← Tests page (M3 of code-driven-documentation)
├── Service: <service-name-B>
│   └── ...
└── ...
```

- The landing page exists once. It is regenerated on every sync (stable preamble + a refreshed service index).
- Each service has exactly one L2 sub-page, named `Service: <service-name>`. Service pages are created on first sync, updated on subsequent syncs, and removed when the service is deleted from Atlas.
- L3 endpoint pages, L4 module pages, the L5 Beans page and the Tests page all parent under their service page. Page lifecycle (create / update / orphan-cleanup) follows the same rules as service pages — on append-only model, "absent" rows trigger Confluence-page deletion.
- The Atlas database is the source of truth. Direct edits to any page in this space will be overwritten on the next sync.

### Drill-down navigation

A reader new to the application is expected to walk the hierarchy top-down:

1. **Landing** answers "what is this app, what services compose it" (L1).
2. **Service page** answers "what does this service do, what are its inputs/outputs/owners" (L2). Section 8 ("Internals") cross-links to L3/L4/L5 detail pages.
3. **Endpoint page** answers "what parameters / request / response shape does this API have" (L3).
4. **Module page** answers "how is this service composed internally — sub-modules, parent pom, declared dependencies" (L4). Cross-links to its module-scoped beans (L5).
5. **Beans page** answers "what classes implement the architectural seams of this service" (L5). Per-class blocks link back to the relevant module.

Cross-links go both directions where it costs nothing. A back-link to the immediate parent page is always emitted on every detail page.

## Landing Page — "Atlas — Service Inventory"

### 1. About this space
- One paragraph: what Atlas is, why this space exists, who maintains it.
- Source-of-truth statement: the Atlas database is canonical; pages here are generated and overwritten.
- "Last refresh" timestamp (most recent sync run).

### 2. Service index
A single table, one row per service, ordered alphabetically. Each name links to its sub-page.

| Column | Source |
|---|---|
| Name | `services.name` (linked to sub-page) |
| Owner | `services.owner_team` |
| Status | `services.status` — active / deprecated / in_dev |
| Language | `services.language` |
| Last synced | `services.last_synced_to_confluence` |

### 3. Browse by status
Three filtered views of the index:
- Active services
- In development
- Deprecated

### 4. Browse by team
Service names grouped by `services.owner_team`. Helps a new joiner find what their team owns without scrolling the master table.

### 5. How updates work
- Short note: "Changes are made in Atlas, not in Confluence. Edits to these pages will be overwritten on the next sync."
- Link to the Atlas intake entry point (or its eventual UI).

## Service Detail Sub-Page (L2)

Each service has one sub-page under the landing page. The content of that page is defined in `confluence-template.md`, which covers all eight sections:

1. Overview
2. Technical Details
3. APIs (summary table, links to L3 endpoint pages)
4. Dependencies
5. Data
6. Operational
7. Change History
8. Internals (cross-reference index to L4 modules, L5 Beans, Tests)

Identifiers used during sync:
- `services.confluence_page_id` — Confluence's identifier for the sub-page.
- `services.last_synced_to_confluence` — when the sub-page was last refreshed.

## Detail Sub-Pages (L3, L4, L5, Tests)

Each service's L2 page parents up to four kinds of detail page. Layouts and identifiers:

| Layer | Page | Source | Identifier column | Lifecycle |
|---|---|---|---|---|
| L3 | Per-endpoint page | OpenAPI spec ingestion | `apis.confluence_page_id` | Create on new endpoint, update on schema change, delete on `presence='absent'` |
| L4 | Per-module page | pom.xml traversal | `service_modules.confluence_page_id` | Create on new module, update on coord/dep change, delete on `presence='absent'` |
| L5 | Beans page (one per service) | Source-tree AST | `services.beans_page_id` | Create on first sync that finds beans, update on every sync, delete when no beans remain |
| — | Tests page (one per service) | Test-tree AST | `services.tests_page_id` | Same as Beans |

All identifiers are stored on the entity's append-only row using the existing pattern (M3.5/M4 from code-driven-documentation). Page IDs carry forward across observations — only when the latest observation has `presence='absent'` does the cleanup pass delete the page and null the id.

## Sync Model

- **Direction**: one-way, Atlas → Confluence. Edits made directly in Confluence are overwritten.
- **Trigger**: scheduled (periodic) and on-demand.
- **Landing page**: regenerated each sync. The preamble text is static; the service index table reflects the live database.
- **Sub-pages**: created the first time a service is synced, updated on each subsequent sync, removed when the service is deleted from Atlas.
- **Sparse data is OK**: a service whose relationship data (APIs, dependencies, databases) has not yet been captured renders with thin or empty sections rather than failing the sync. Filling those gaps is the job of Atlas's intake flow, not the renderer.

## Out of Scope for the First Release

The Confluence space deliberately keeps these out for now; they are reachable in a later iteration once the L1–L5 drill-down is in real use:

- Cross-service dashboards (e.g., "all services with no on-call contact").
- Per-team summary pages.
- Confluence labels, comments, watchers, or change-notification emails.
- Inclusion of non-stereotype classes (DTOs, POJOs, JPA entities) on the L5 Beans page.
- Gradle / Bazel / non-Maven build-system traversal for L4.
- Cross-service "who calls this method" indexing (L5+ analysis is per-service only).
- Live-reload of detail pages on code change — sync is still scheduled (default 15 min) or manual.

Already in scope and built: the landing-level architecture map (mermaid graph of inter-service edges).
