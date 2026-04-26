# Confluence Page Template

This is the output specification — what each service's Confluence page should contain. The Confluence sync agent transforms data from the Atlas database into pages matching this structure.

## Page Sections

### 1. Overview
- Service name
- Description / purpose
- Owner / team
- Status (active, deprecated, in development)

### 2. Technical Details
- Language / framework
- Runtime environment
- Repository link
- Deployment target

### 3. APIs
- Endpoints exposed
- Authentication method
- Consumers of this API

### 4. Dependencies
- Databases used
- Upstream services depended on
- Downstream services that depend on this one
- External third-party dependencies

### 5. Data
- Databases owned/managed
- Key data entities
- Data classification (PII, sensitive, etc.)

### 6. Operational
- On-call / support contact
- SLA / uptime expectations
- Known issues or tech debt

### 7. Change History
- Last updated (auto-stamped by sync agent)
- Recent significant changes

## Data Inventory (per service)

| Field | Type | Notes |
|---|---|---|
| Service Name | Text | Canonical name |
| Description | Text | What it does |
| Owner | Text | Team or individual |
| Status | Enum | active / deprecated / in_dev |
| Language | Text | Java, Python, etc. |
| Framework | Text | Spring, Django, etc. |
| Repo URL | URL | Link to source |
| Deployment | Text | AWS, on-prem, etc. |
| APIs Exposed | List | Endpoint names/paths |
| API Consumers | List | Services that call this |
| DB Dependencies | List | Databases it reads/writes |
| Upstream Services | List | Services it calls |
| Downstream Services | List | Services that call it |
| External Dependencies | List | 3rd party APIs/tools |
| Support Contact | Text | Who to call |
| SLA | Text | Uptime / response targets |
| Notes | Text | Free-form / tech debt |

## Sync Behavior

- Pages are created on first sync, updated on subsequent syncs.
- The `confluence_page_id` field on the `services` table tracks the page in Confluence.
- The `last_synced_to_confluence` timestamp records the most recent successful sync.
- Diffs between DB state and Confluence content are reflected on next sync. **Atlas is the source of truth; Confluence edits will be overwritten.**

## Notes

- Section structure may evolve as we collect data from real services. Update this document as the spec changes.
- The mapping between this template and the Atlas database schema is documented in `schema.md`.
