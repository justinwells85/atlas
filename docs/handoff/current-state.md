# Atlas — Current State

One-page summary of what is built, what is tested, what is not done, and what is deferred. Last updated: 2026-04-30, end of Phase 5.6 (drill-down depth L3–L5), M4 + end-of-phase reflection.

## What's built and working

- **Three Spring Boot apps + one shared library**, multi-module Maven build per ADR-012:
  - `atlas-intake` (port 8080) — AI-assisted interview that registers a service into the inventory. Server-stateless per ADR-011; conversation state rides in the request body.
  - `atlas-mcp` (port 8081) — MCP server exposing five tools (`search_services`, `list_services`, `get_service_details`, `update_service`, `ping`) over HTTP/SSE.
  - `atlas-confluence-sync` (port 8082) — Renders the seven-section page template, syncs to Confluence Cloud via the v2 REST API. Manual REST trigger + scheduled cron (default 15 min).
  - `atlas-domain` — JPA entities, repositories, Flyway migrations V1–V10.
- **End-to-end pipeline tested** against the dogfood: Atlas itself is registered in Atlas (`atlas-intake`, `atlas-mcp`, `atlas-confluence-sync` all have Confluence pages with full inter-module dependency edges).
- **Schema portability proven** to MariaDB 10.11 via `MariaDBPortabilitySmokeTest`. Production target was always MariaDB on RDS; this claim was theoretical until Phase 5.5.
- **Architecture-map page** in the ATLAS Confluence space (parented under landing) renders the live service-dependency graph as a mermaid flowchart (Phase 5.5 follow-up, plan 2026-04-28 milestone C).
- **MCP `delete_service` tool + intake removal flow** (plan 2026-04-28 milestone B): MCP-side soft-delete + REST `POST /api/intake/remove` two-stage confirmation flow audited as `intake-removal`.
- **Code-driven documentation, M1–M5 (plan 2026-04-29) — phase closed**: per-source provenance (`apis.source`, V13); OpenAPI ingestion → source-tagged `apis` rows. M2 adds per-endpoint Confluence pages (V14: `apis.confluence_page_id`) with service-page hyperlinks; M2.5 added soft-delete cleanup (since superseded by M3.5). M3 adds `services.module_path`, `services.tests_page_id`, and `service_test_scenarios` (V16) plus RepoFileFetcher + JavaTestExtractor + refreshTests + TestScenariosPageRenderer. **M3.5 retrofitted ingestion to append-only**: V17 adds `observed_at` + `presence` to `apis` and `service_test_scenarios`; V18 (vendor-split between `db/migration_postgresql/` and `db/migration_mariadb/`) drops the unique constraints. Code-sync writes only inserts — disappearance recorded as `presence='absent'` tombstones, content changes recorded as new observations carrying forward the previous `confluence_page_id`. Live readers use window-function "latest per key where presence='present'" queries. **M4 adds pom.xml ingestion**: V19 adds the `service_metadata` table (append-only from day one) and observation columns on `service_external_deps`; V20 (vendor-split) drops the `service_external_deps` unique. PomParser via Maven's MavenXpp3Reader extracts language / language_version / framework / framework_version / build_tool plus declared dependencies (org-prefix-filtered). **M4.5 wires the new data into the renderer and adds opt-in stale-intake cleanup** — no new migrations: `findExternalDependenciesFor` becomes a window-function live-view that exposes `source`; `ServicePageContext` carries `List<ServiceMetadata>` and the renderer's Technical Details section reads it (pom-xml > intake observation > entity-column fallback) with a `(from pom.xml)` suffix; External Dependencies section composes intake + pom rows, collapsing matching pairs into a "✓ matches pom: ..." annotation; new `CodeSyncCoordinator.tombstoneStaleIntakeApis(serviceId)` + `POST /api/code-sync/tombstone-stale-intake-apis/{serviceId}`. **M5 (interview shrinkage + phase close)**: interview no longer prompts for LANGUAGE / FRAMEWORK / HAS_APIS (auto-derived from pom.xml + openapi); two new prompts OPENAPI_SPEC_URL + MODULE_PATH let humans tell code-sync where to look; `ServiceDraft` + `Service` entity + `applyDraftFields` carry the new fields; the entire APIs sub-loop (path, method, auth, description, consumers, another-api/another-consumer — up to 10 prompts per endpoint) is gone from the interview. Stage-enum values retained for serialized-state backward compatibility. atlas-intake dogfoods itself end-to-end: `POST /api/code-sync/refresh/{serviceId}` (OpenAPI), `POST /api/code-sync/refresh-tests/{serviceId}` (GitHub), `POST /api/code-sync/refresh-pom/{serviceId}` (pom.xml), `POST /api/code-sync/tombstone-stale-intake-apis/{serviceId}` (M4.5 cleanup).
- **Drill-down depth, Phase 5.6 M1–M4 (plan 2026-04-29-drill-down-l3-l5) — phase closed**: the Confluence space is now walkable from landing page down to a single class's public method signatures in three clicks. **M1 (L3 schema)**: V21 adds `apis.openapi_snapshot TEXT` (portable JSON-as-text). `SwaggerOpenApiParser` resolves `$ref` inline (`ParseOptions.setResolveFully(true)`) and emits compact JSON via Jackson's `valueToTree` with `JsonInclude.NON_NULL`. `ApiEndpointPageRenderer` renders Parameters / Request Body / Responses by status / Examples one level deep; nested objects collapse to type names. **M2 (L4 modules)**: V22 adds `service_modules` (append-only from day one — `observed_at`, `presence`, `source='pom-xml'`, `confluence_page_id`). `PomFacts` extended with `packaging` + `modules` list. `CodeSyncCoordinator.refreshPom` walks the module tree (root pom → recursively fetch `<modules>` children via `RepoFileFetcher`). New `ModulePageRenderer` + `ModulePageContext` produce per-module Confluence pages. `SyncCoordinator.syncModulePages` lifecycles them in two passes (create-then-update so cross-module links resolve). **M3 (L5 Beans)**: V23 adds `service_beans` (append-only) + V24 adds `services.beans_page_id` (mirrors `services.tests_page_id`). New `JavaBeanExtractor` walks the JavaParser AST, captures top-level Spring stereotype classes (`@RestController`/`@Controller`/`@Service`/`@Repository`/`@Component`/`@Configuration`) with public-method signatures + first-sentence javadoc. New `BeansPageRenderer` produces one page per service grouped by stereotype. New `POST /api/code-sync/refresh-beans/{serviceId}` REST endpoint. **M4 (cross-link integration + phase close)**: `ServicePageRenderer` gains a Section 8 "Internals" block with four sub-blocks (Modules / Code index / Tests / Endpoints), each falling back to a thin note when its layer has no data. `SyncCoordinator.syncOneInternal` now does a two-pass L2 render (pass 1 → child syncs → pass 2) so Section 8 reflects child-page ids without requiring a second sync round. `LandingPageRenderer` gains a "How to read this space" preamble paragraph. **Dogfood verified live** against the ATLAS Confluence space: every service page's Section 8 links its L4 modules + L5 Beans + Tests + L3 endpoints; landing-page preamble visible.
- **317 active tests, 0 failures** across all four modules (atlas-domain 49, atlas-intake 116, atlas-mcp 24, atlas-confluence-sync 128). Behavior-focused per ADR-006; mocks only at architectural seams (Anthropic, Confluence, remote OpenAPI hosts, GitHub Contents API). Net change vs end of code-driven-documentation phase: +70 (M1 +16, M2 +21, M3 +27, M4 +6).

## What's tested

| Module | Tests | What they cover |
|---|---|---|
| atlas-domain | 49 | Schema constraints (CHECK, UNIQUE, FK CASCADE), repository reads/writes, MariaDB portability smoke (now exercising V13–V24), live-view external-deps + stale-intake-apis lookup (M4.5), append-only live-view queries on `service_modules` + `service_beans` (Phase 5.6 M2/M3). |
| atlas-intake | 116 | Multi-turn interview state machine (post-M5: no LANGUAGE/FRAMEWORK/APIs prompts; OPENAPI_SPEC_URL + MODULE_PATH added), upstream/downstream/database/external-dep relationship-table write paths, Anthropic gateway smoke, intake removal flow, code-sync OpenAPI refresh (provenance, intake-skip, transactional rollback, idempotency, openapi_snapshot persistence per Phase 5.6 M1), JavaTestExtractor + JavaBeanExtractor (AST parsing), RepoFileFetcher (GitHub Contents API), code-sync test/pom/beans refresh, PomParser + module-tree walking (Phase 5.6 M2), org-prefix filter, stale-intake-rows cleanup (M4.5). |
| atlas-mcp | 24 | Each MCP tool's contract: search, list, get_service_details, update_service, delete_service, ping. |
| atlas-confluence-sync | 128 | Renderer output for full/partial/minimal services including Section 8 Internals (Phase 5.6 M4); ConfluenceClient HTTP shape via WireMock; SyncCoordinator orchestration including 404-recreate, service-level orphan-cleanup, per-endpoint page lifecycle (M2), endpoint-page orphan cleanup (M2.5), per-service tests-page lifecycle, per-module page lifecycle with create-then-update two-pass (Phase 5.6 M2), per-service Beans-page lifecycle (Phase 5.6 M3), L2 service-page two-pass render (Phase 5.6 M4); architecture-map renderer; ApiEndpointPageRenderer (with schema rendering); ModulePageRenderer; BeansPageRenderer; TestScenariosPageRenderer; LandingPageRenderer (with How-to-read preamble per Phase 5.6 M4); SyncController endpoints; service_metadata-driven Technical Details with fallback (M4.5); intake+pom external-deps composition (M4.5). |

`mvn verify` runs all of these end-to-end with Testcontainers Postgres + MariaDB; no live network calls in CI.

## What's not done

| Area | Status | Pointer |
|---|---|---|
| **Auth model** | Deferred. `127.0.0.1`-only binding is the security model today. | `deferred-decisions.md` DD-001 |
| **Schema portability** | **Resolved Phase 5.5.** | DD-002 |
| **CI/CD pipeline** | Not started. `mvn verify` runs locally only. | DD-003 |
| **`updatedAt` rendering test** | Indirectly covered. | DD-004 |
| **Spring Boot 4 modular-autoconfig wiring for ObjectMapper / RestClient.Builder** | Workaround in place (inline `new ObjectMapper()`); right modules not yet identified. | DD-005 |
| **HTTP timeout tuning** | Defaults only. | DD-006 |
| **Rate limiting / retry-with-backoff** | Not implemented; production concern. | DD-007 |
| **Auth-error classification (401/403 vs 5xx)** | Generic catch-all; revisit at first real-instance failure. | DD-008 |
| **409 Conflict on Confluence PUT** | Not handled; rare race in single-writer prototype. | DD-009 |
| **Intake `method` validation rejects MCP / non-HTTP API surfaces** | **Resolved 2026-04-28** (plan 2026-04-28 milestone A). Whitelist widened to accept MCP/GRPC/GRAPHQL/AMQP/KAFKA. | DD-010 |
| **Confluence layout: landing page + parented sub-pages + cross-links + inventory pages + About page** | **Resolved Phase 4.5 (M1–M3).** Service pages now parent under "Atlas — Service Inventory"; "Inventory: Data Stores", "Inventory: External Dependencies", and "About Atlas" pages added; service-to-service and resource references hyperlinked. | DD-011 |
| **Orphan-page cleanup on service deletion** | **Resolved** (ADR-014). V11 adds `services.deleted_at`; Hibernate `@SQLDelete` + `@SQLRestriction` make soft-delete transparent; the sync agent's cleanup pass deletes orphan Confluence pages on every `syncAll()`. | DD-013 |
| **Internal LLM gateway implementation** | Stub in place (`InternalLlmGateway` throws on call); abstraction `LlmGateway` ready for swap. | DD-012 + ADR-013 |

The deferred-decisions list is the production-readiness checklist. Each entry has a *trigger to revisit* and a *remediation sketch*.

## Current data model in one sentence

`services` is the entity table; `apis`, `data_stores`, `external_dependencies` are leaf entity tables; `service_dependencies`, `service_databases`, `api_consumers`, `service_external_deps` are join/relationship tables; `service_changes` is an append-only audit log with a soft FK so history outlives deleted services. Full detail in `schema.md`.

## What ships when "production-ready"

The minimum to take Atlas off the prototype Mac and onto an org-controlled environment, in priority order:

1. **DD-001** — pick an auth model and bind off `127.0.0.1`. Without this, Atlas can't move to a shared host.
2. **DD-003** — CI pipeline running `mvn verify` against PRs and main. Currently every commit is on local trust.
3. **AWS provisioning** — see `aws-migration-plan.md`. Ten `[org-decision]` open items must be answered.
4. **DD-011 decision** — ship the proposed Confluence layout or stay flat. Either is fine; pick one.
5. **Stakeholder demo** — see `demo-script.md`. Engineering buy-in is the gate to the production-migration funding/approval conversation.

The order of 1–4 is not strict — they can run in parallel.

## What the prototype is **NOT**

Not all gaps are deferred decisions; some are intentional non-goals at prototype scale and will need real engineering thought before they ship.

- **Not a write-side audit trail** beyond the `service_changes` table. The audit row is created on intake/update; it's not a full event-sourced model and is not transactional with the page-render side.
- **Not a service catalog with discovery** — Atlas does not know what services exist beyond what humans/intake registers. A service that's never been through intake doesn't appear, even if it's running in production.
- **Not a UI** — the only entry points are `POST /api/intake/turn`, `POST /api/sync/run`, and the MCP tool surface. A UI is not on the prototype roadmap; if needed, the existing REST shape supports one.
- **Not a real-time sync** — Confluence updates are scheduled (default 15 min) or manual. There is no event-driven "DB row changed → page updated immediately" path. That's a deliberate prototype simplification; production may want lower latency.

## Status of "Phase 5 — End-to-End Validation"

- [x] Define entry criteria — `phase-5-entry-criteria.md`.
- [x] Pick one real service, run the pipeline — Atlas itself, three modules. (Honest gaps in the criteria are documented as guidance.)
- [x] Confluence page renders correctly with all expected sections — verified against the live ATLAS space.
- [x] Update path (DB change → re-sync → Confluence reflects) — exercised on `atlas-intake` in Phase 5.5 M5: description changed to a verification marker (page v1 → v2 reflects the change), restored to the original (page v2 → v3 reflects the restore). Two-way update path proven.
- [x] Document any rough edges discovered — captured as DD-005, DD-008, DD-010 (and DD-002 / DD-011 from earlier phases).

**Phase 5 is closed.**

## Closed phase: code-driven documentation (2026-04-29)

`docs/plans/2026-04-29-code-driven-documentation.md` — closed. Seven milestones (M1, M2, M2.5, M3, M3.5, M4, M4.5, M5 + interim phase reflection + end-of-phase reflection). All seven plan-stated success criteria met. The interview is materially shrunk; the dogfood is honest; per-source provenance is uniform across data types; the append-only model and per-source composition extend cleanly to future writers.

## Closed phase: drill-down depth L3–L5 (2026-04-30)

`docs/plans/2026-04-29-drill-down-l3-l5.md` — closed. Four milestones (M1 L3 schema, M2 L4 module tree, M3 L5 Beans, M4 cross-link integration). All ten plan-stated success criteria met. The Confluence space is now walkable from landing page down to a single class's public method signatures in three clicks; verified live in the dogfood against atlas-intake / atlas-mcp / atlas-confluence-sync.

The phase's end-of-phase reflection recommends **Phase 6 (Demo and Handoff)** as the next phase per the roadmap. The L2 Internals block makes the demo land harder ("three clicks from landing page to any public method"). Phase 6 plan should:
1. Apply a one-line dogfood data fix (`UPDATE services SET module_path = name WHERE module_path IS NULL AND name IN ('atlas-mcp', 'atlas-confluence-sync')`) so those two services render populated Beans pages.
2. Optional polish: omit the trivial root-only L4 module page when `children.isEmpty()` (~50 lines).
3. Demo script updates: walk the L1→L5 drill-down explicitly.
4. Seal the deferred-decisions list as the Phase 6 deliverable.

Carry-overs into Phase 6 (small, none blocking): the dogfood data fix above; L4 root-only page omission; **DD-014 candidate** (private-repo GitHub auth — affects all source-tree readers, including the new bean extractor); **DD-015 candidate** (widen L5 stereotype scope to `@Entity` / Spring Data interfaces / `@ConfigurationProperties` if demo audience asks for the persistence surface); test-name normalisation polish on the Tests page; MariaDB `VARCHAR(191)` rule promotion to CLAUDE.md Constraints; production-scale window-function index design (would benefit from `apis.method`/`apis.path` becoming bounded VARCHAR); pom parent-chain full inheritance (M2 carries `parent_path` only); `ServicePageContext` builder candidate (fixture sprawl is wider after M4); each-Atlas-service-shows-the-whole-monorepo visual quirk in Internals → Modules; service-level external-deps union-from-tree for multi-module services; Confluence `confluenceTable` CSS class on rendered tables; L5 stereotype-group module-of-origin annotation; L5 javadoc first-sentence heuristic edge cases (`Mr.`, `e.g.` mis-classify); unreachable LANGUAGE/FRAMEWORK/API_* applyInput cases in `InterviewService` (~120 lines of defensive code); `otherRows` defensive branch in `renderExternalDependencies`.

Resolved this phase: per-endpoint pages now carry full schema-level detail (parameters / request / responses / examples) — no more "see the OpenAPI spec link" hand-off; per-Maven-module Confluence pages exist (ATLAS space sidebar tree now mirrors the real codebase structure); per-service Beans pages list Spring stereotype classes with public-method signatures; the L2 service page's Section 8 ties the four child page sets together with thin-note fallbacks for empty layers; landing page carries a "How to read this space" preamble explaining the convention.

## Repository pointers

- Code: `https://github.com/justinwells85/atlas`
- Docs root: `docs/` in the repo
- Production-readiness checklist: `docs/deferred-decisions.md`
- Provisioning starting point: `docs/aws-migration-plan.md`
- Demo script: `docs/demo-script.md`
