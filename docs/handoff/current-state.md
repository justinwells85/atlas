# Atlas — Current State

One-page summary of what is built, what is tested, what is not done, and what is deferred. Last updated: 2026-04-29, end of code-driven-documentation phase (M5 + end-of-phase reflection).

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
- **247 active tests, 0 failures** across all four modules (atlas-domain 39, atlas-intake 88, atlas-mcp 24, atlas-confluence-sync 96). Behavior-focused per ADR-006; mocks only at architectural seams (Anthropic, Confluence, remote OpenAPI hosts, GitHub Contents API). Test count dropped 254 → 247 in M5: 9 API-section tests removed (covered behavior no longer exists), 2 new tests added pinning the post-M5 shape.

## What's tested

| Module | Tests | What they cover |
|---|---|---|
| atlas-domain | 39 | Schema constraints (CHECK, UNIQUE, FK CASCADE), repository reads/writes, MariaDB portability smoke (now exercising V13–V20), live-view external-deps + stale-intake-apis lookup (M4.5). |
| atlas-intake | 88 | Multi-turn interview state machine (post-M5: no LANGUAGE/FRAMEWORK/APIs prompts; OPENAPI_SPEC_URL + MODULE_PATH added), upstream/downstream/database/external-dep relationship-table write paths, Anthropic gateway smoke, intake removal flow, code-sync OpenAPI refresh (provenance, intake-skip, transactional rollback, idempotency), JavaTestExtractor (AST parsing), RepoFileFetcher (GitHub Contents API), code-sync test refresh, PomParser (Maven Model API), code-sync pom refresh (metadata + external-dep observations, org-prefix filter), stale-intake-rows cleanup (M4.5). |
| atlas-mcp | 24 | Each MCP tool's contract: search, list, get_service_details, update_service, delete_service, ping. |
| atlas-confluence-sync | 96 | Renderer output for full/partial/minimal services; ConfluenceClient HTTP shape via WireMock; SyncCoordinator orchestration including 404-recreate, service-level orphan-cleanup, per-endpoint page lifecycle (M2), endpoint-page orphan cleanup (M2.5), per-service tests-page lifecycle (M3); architecture-map renderer; ApiEndpointPageRenderer; TestScenariosPageRenderer; SyncController endpoints; service_metadata-driven Technical Details with fallback (M4.5); intake+pom external-deps composition (M4.5). |

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

The phase's end-of-phase reflection recommends **Phase 6 (Demo and Handoff)** as the next phase per the roadmap. Rationale: the prototype is at a peak of architectural completeness, and the demo is the gate to Phase 7 production migration. AI-narrated walkthroughs (deferred layer 3 from the original strategic conversation) and production-readiness DDs (DD-001 auth, DD-003 CI, AWS provisioning) are alternative directions but should both follow Phase 6.

Carry-overs into the next phase (small, none blocking): private-repo GitHub auth (DD-014 candidate); test-name normalisation for nicer "Tests" page rendering (polish); sync-side live-verify of all milestones against the real ATLAS space (deferred to user discretion); MariaDB project rule about `VARCHAR(191)` for unique-index columns worth promoting to CLAUDE.md Constraints; production-scale window-function index design (would benefit from `apis.method`/`apis.path` becoming bounded VARCHAR); pom parent-chain traversal (M4 PomParser is single-pom-only); `ServicePageContext` builder candidate (fixture sprawl); unreachable LANGUAGE/FRAMEWORK/API_* applyInput cases in `InterviewService` (~120 lines of defensive code re-evaluable post-Phase 6); `otherRows` defensive branch in `renderExternalDependencies` (YAGNI candidate).

Resolved this session: stale `/api/smoke/anthropic` intake row carry-over (M4.5 cleanup endpoint exists; dogfood DB is one REST call away from being clean); renderer integration of `service_metadata` (M4.5); interview shrunk to human-only fields plus code-sync inputs (M5).

## Repository pointers

- Code: `https://github.com/justinwells85/atlas`
- Docs root: `docs/` in the repo
- Production-readiness checklist: `docs/deferred-decisions.md`
- Provisioning starting point: `docs/aws-migration-plan.md`
- Demo script: `docs/demo-script.md`
