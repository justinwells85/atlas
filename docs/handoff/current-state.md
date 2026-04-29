# Atlas — Current State

One-page summary of what is built, what is tested, what is not done, and what is deferred. Last updated: 2026-04-29, end of code-driven-documentation M1.

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
- **Code-driven documentation, M1–M4 (plan 2026-04-29)**: per-source provenance (`apis.source`, V13); OpenAPI ingestion → source-tagged `apis` rows. M2 adds per-endpoint Confluence pages (V14: `apis.confluence_page_id`) with service-page hyperlinks; M2.5 added soft-delete cleanup (since superseded by M3.5). M3 adds `services.module_path`, `services.tests_page_id`, and `service_test_scenarios` (V16) plus RepoFileFetcher + JavaTestExtractor + refreshTests + TestScenariosPageRenderer. **M3.5 retrofitted ingestion to append-only**: V17 adds `observed_at` + `presence` to `apis` and `service_test_scenarios`; V18 (vendor-split between `db/migration_postgresql/` and `db/migration_mariadb/`) drops the unique constraints. Code-sync writes only inserts — disappearance recorded as `presence='absent'` tombstones, content changes recorded as new observations carrying forward the previous `confluence_page_id`. Live readers use window-function "latest per key where presence='present'" queries. **M4 adds pom.xml ingestion**: V19 adds the `service_metadata` table (append-only from day one) and observation columns on `service_external_deps`; V20 (vendor-split) drops the `service_external_deps` unique. PomParser via Maven's MavenXpp3Reader extracts language / language_version / framework / framework_version / build_tool plus declared dependencies (org-prefix-filtered). atlas-intake dogfoods itself end-to-end: `POST /api/code-sync/refresh/{serviceId}` (OpenAPI), `POST /api/code-sync/refresh-tests/{serviceId}` (GitHub), `POST /api/code-sync/refresh-pom/{serviceId}` (pom.xml).
- **236 active tests, 0 failures** across all four modules (atlas-domain 33, atlas-intake 91, atlas-mcp 24, atlas-confluence-sync 88). Behavior-focused per ADR-006; mocks only at architectural seams (Anthropic, Confluence, remote OpenAPI hosts, GitHub Contents API).

## What's tested

| Module | Tests | What they cover |
|---|---|---|
| atlas-domain | 33 | Schema constraints (CHECK, UNIQUE, FK CASCADE), repository reads/writes, MariaDB portability smoke (now exercising V13–V16). |
| atlas-intake | 91 | Multi-turn interview state machine, all 11 services-row fields, every relationship-table write path, Anthropic gateway smoke, intake removal flow, code-sync OpenAPI refresh (provenance, intake-skip, transactional rollback, idempotency), JavaTestExtractor (AST parsing), RepoFileFetcher (GitHub Contents API), code-sync test refresh, PomParser (Maven Model API), code-sync pom refresh (metadata + external-dep observations, org-prefix filter). |
| atlas-mcp | 24 | Each MCP tool's contract: search, list, get_service_details, update_service, delete_service, ping. |
| atlas-confluence-sync | 88 | Renderer output for full/partial/minimal services; ConfluenceClient HTTP shape via WireMock; SyncCoordinator orchestration including 404-recreate, service-level orphan-cleanup, per-endpoint page lifecycle (M2), endpoint-page orphan cleanup (M2.5), per-service tests-page lifecycle (M3); architecture-map renderer; ApiEndpointPageRenderer; TestScenariosPageRenderer; SyncController endpoints. |

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

## Active plan: code-driven documentation (2026-04-29)

`docs/plans/2026-04-29-code-driven-documentation.md` — five-milestone shift from intake-only to a hybrid where APIs, tests, and pom-derived metadata are auto-refreshed from code. M1, M2, M2.5, M3, M3.5 (append-only retrofit), and M4 (pom.xml ingestion) closed. Decision recorded at M3.8: code-sync stays folded into `atlas-intake` for the prototype. Next: M5 — phase-level reflection + interview shrinkage (with the new `service_metadata` table now ready to absorb intake's language/framework writes).

Carry-overs to track: stale `/api/smoke/anthropic` intake row in dogfood DB (M5 cleanup); private-repo GitHub auth (DD-014 candidate); test-name normalisation for nicer "Tests" page rendering (polish); sync-side live-verify of all milestones against the real ATLAS space deferred to user discretion; MariaDB project rule about `VARCHAR(191)` for unique-index columns worth promoting to CLAUDE.md Constraints; production-scale window-function index design (would benefit from `apis.method`/`apis.path` becoming bounded VARCHAR); pom parent-chain traversal (M4 PomParser is single-pom-only — atlas-intake's framework_version came up empty because its parent is `com.atlas:atlas`, not `spring-boot-starter-parent`); renderer integration to surface `service_metadata` alongside `services.language`/`services.framework` is the natural next polish.

## Repository pointers

- Code: `https://github.com/justinwells85/atlas`
- Docs root: `docs/` in the repo
- Production-readiness checklist: `docs/deferred-decisions.md`
- Provisioning starting point: `docs/aws-migration-plan.md`
- Demo script: `docs/demo-script.md`
