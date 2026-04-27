# Atlas — Confluence Space Layout

How Atlas-generated content is organised in Confluence: a single overview/landing page that parents one sub-page per service. This document covers the **space-wide hierarchy** and the **landing page** content. For the field-level layout of an individual service page, see `confluence-template.md`.

## Page Hierarchy

```
Atlas — Service Inventory                ← overview / landing page
├── Service: <service-name-A>            ← one sub-page per service
├── Service: <service-name-B>
├── Service: <service-name-C>
└── ...
```

- The landing page exists once. It is regenerated on every sync (stable preamble + a refreshed service index).
- Each service has exactly one sub-page, named `Service: <service-name>`. Sub-pages are created on first sync, updated on subsequent syncs, and removed when the service is deleted from Atlas.
- The Atlas database is the source of truth. Direct edits to any page in this space will be overwritten on the next sync.

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

## Service Detail Sub-Page

Each service has one sub-page under the landing page. The content of that page is defined in `confluence-template.md`, which covers all seven sections:

1. Overview
2. Technical Details
3. APIs
4. Dependencies
5. Data
6. Operational
7. Change History

Identifiers used during sync:
- `services.confluence_page_id` — Confluence's identifier for the sub-page.
- `services.last_synced_to_confluence` — when the sub-page was last refreshed.

## Sync Model

- **Direction**: one-way, Atlas → Confluence. Edits made directly in Confluence are overwritten.
- **Trigger**: scheduled (periodic) and on-demand.
- **Landing page**: regenerated each sync. The preamble text is static; the service index table reflects the live database.
- **Sub-pages**: created the first time a service is synced, updated on each subsequent sync, removed when the service is deleted from Atlas.
- **Sparse data is OK**: a service whose relationship data (APIs, dependencies, databases) has not yet been captured renders with thin or empty sections rather than failing the sync. Filling those gaps is the job of Atlas's intake flow, not the renderer.

## Out of Scope for the First Release

The first version of the Confluence space is intentionally narrow — landing page plus per-service sub-pages, nothing else. The following are deferred until the prototype is in real use and reading patterns are observable:

- Cross-service dashboards (e.g., "all services with no on-call contact").
- Per-team summary pages.
- Confluence labels, comments, watchers, or change-notification emails.
- Embedded diagrams of the dependency graph.
