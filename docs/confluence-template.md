# Confluence Page Template

This is the output specification — what each service's Confluence page should contain, and what its descendant detail pages should contain. The Confluence sync agent transforms data from the Atlas database into pages matching this structure.

The space is organised as a five-layer drill-down so a newcomer can walk from "what is this app" down to "what does this method do" without leaving Confluence:

- **L1 Space** — landing page + About (see `confluence-layout.md`).
- **L2 Service** — the seven-section service page documented below.
- **L3 Endpoint** — one page per API endpoint with full schema-level detail.
- **L4 Module** — one page per Maven module/sub-module of the service.
- **L5 Bean/Class** — one page per service indexing Spring stereotypes + their public methods.

Sections 8–10 below specify the L3, L4 and L5 content. Section 11 specifies the cross-links between layers.

## Page Sections (L2 Service Page)

### 1. Overview
- Service name
- Description / purpose
- Owner / team
- Status (active, deprecated, in development)

### 2. Technical Details
- Language / framework (with version, when known)
- Build tool (Maven / Gradle / etc.)
- Runtime environment
- Repository link
- Deployment target

### 3. APIs
A summary list. One row per endpoint: `METHOD path` (linked to its **L3 endpoint page** — see Section 8), short description, auth method, consumers. Schema-level detail lives on the linked endpoint page, not here.

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

### 8. Internals (drill-down index)
A short cross-reference block, generated last so it always reflects what descendant pages exist:

- **Modules** — list of L4 module pages for this service, with one-line summaries.
- **Code index** — link to the L5 Beans page for this service.
- **Tests** — link to the existing per-service Tests page (M3 of code-driven-documentation).
- **Endpoints** — recap link list of L3 endpoint pages (the same set surfaced in Section 3, repeated here so the Internals block is the single drill-down anchor).

When a layer has no data yet (e.g. a service has no parsed pom.xml so no modules are known), the corresponding bullet renders as a thin "No modules documented yet." note rather than disappearing — same convention as the rest of the template.

## L3 Endpoint Page (one per `apis` row)

Title: `"{service.name} — {METHOD} {path}"`. Parented under the service page. Source: composed from the OpenAPI spec at code-sync time.

### Sections
- **Heading** — `METHOD path`.
- **Source provenance** — `intake` / `openapi` badge.
- **Description** — endpoint summary/description from the spec, or a thin "No description documented." note.
- **Auth** — security scheme(s) declared on the operation, when present.
- **Parameters** — table: name, in (path/query/header/cookie), type, required, description. Empty section omitted.
- **Request body** — content type(s); for each, the inline schema (object → fields with type/required/description; arrays → element schema; primitives → type+format). Schemas referenced via `$ref` are resolved from `components/schemas` and rendered inline (one level deep; deeper nesting collapses to type names with the FQ schema name as a hint).
- **Responses** — table grouped by status code: status, description, content type, schema (rendered inline as for request body).
- **Examples** — request and response examples from the spec, when present, rendered as code blocks.
- **Spec link** — for `source='openapi'` rows, a link to the source spec URL for the canonical version.
- **Back-link** — to the parent service page.

Schema-level data (parameters, request body, responses, examples) lives in a JSON snapshot column on `apis` so the renderer is a pure function of DB state. See `schema.md` for the column.

## L4 Module Page (one per `service_modules` row)

Title: `"{service.name} — Module: {module-path}"`. Parented under the service page. Source: pom.xml traversal at code-sync time.

### Sections
- **Heading** — module path (e.g. `atlas-intake`, `services/billing/api`).
- **Parent module** — link to the parent module's page, or "Top-level module" when this is the root.
- **Sub-modules** — list of links to child module pages, or "No sub-modules." when this is a leaf.
- **Coordinates** — groupId, artifactId, version, packaging.
- **Language / framework versions** — module-specific values when they differ from the service-level values; otherwise "Inherited from parent: ..." with the inherited value.
- **Declared dependencies** — list of `groupId:artifactId` extracted by the org-prefix-filtered pom parser. Reuses the same provenance + composition rules as the service-page External Dependencies section.
- **Beans declared in this module** — link list to the Beans page anchors for the classes that live under this module's source root. Lets a newcomer pivot from "module" to "what classes does this module contain."
- **Back-link** — to the parent service page.

## L5 Beans Page (one per service)

Title: `"{service.name} — Beans"`. Parented under the service page. Source: source-tree AST traversal at code-sync time.

### Sections
- **Heading** — `{service.name} — Beans`.
- **Preamble** — one paragraph: "Each entry below is a Spring stereotype-annotated class found in this service's source tree. The names + method signatures describe the architectural seams of the service."
- **Per-stereotype groupings** — one section per stereotype that appears (`@RestController`, `@Controller`, `@Service`, `@Repository`, `@Component`, `@Configuration`). Within a section, classes are grouped by Maven module (linking back to the relevant L4 module page) and listed by fully-qualified class name.
- **Per-class block** — class FQN, one-line javadoc summary (when present), public method signatures (`returnType methodName(paramType paramName, ...)`) with the first sentence of each method's javadoc as the description.
- **Back-link** — to the parent service page.

Excluded by default: DTOs, POJOs, enums, JPA `@Entity` classes, Spring Data repository interfaces. The page is intentionally an architectural-seam index, not a complete class catalogue. (Widening the included set is a deferred decision — see `deferred-decisions.md`.)

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
| API Schema Snapshot | JSON | Parameters, request/response schemas, examples (per endpoint) |
| API Consumers | List | Services that call this |
| Modules | List | Maven sub-modules (path, parent, coordinates, deps) |
| Beans | List | Spring stereotype classes (FQN, stereotype, public methods) |
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
