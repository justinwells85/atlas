# Plan — code-driven documentation: shift from human interview toward code-derived facts

Created: 2026-04-29.

## Goal

Shift Atlas from intake-interview as the only data source toward a hybrid model where factual, verifiable fields (APIs, dependencies, tests, framework metadata) are derived from the code itself, while the interview shrinks to the things only humans know (ownership, SLAs, support contacts, business rationale). The interview never goes away — it gets smaller.

## Success criteria

- A `code-sync` writer runs alongside `confluence-sync` — both internal consumers of `atlas-domain` (per ADR-015), neither going through MCP.
- **Per-source provenance**: each derivable row carries a `source` column (`intake`, `openapi`, `pom-xml`, `tests`). Code-sync only ever upserts rows it owns and never touches `source='intake'` rows. Audit log entries record the writer (`changed_by='code-sync-openapi'` etc.).
- **OpenAPI ingestion** is the authoritative source for `apis`. Per-endpoint Confluence pages render with parameters, request/response schemas, auth metadata, and examples.
- **Test-method extraction** produces a "What this service guarantees" page per service, derived from `src/test/java/**/*.java`.
- **pom.xml ingestion** refreshes `services.language` / `services.framework` and `service_external_deps` rows.
- Atlas dogfood continues to work end-to-end. `atlas-intake`, `atlas-mcp`, `atlas-confluence-sync` get richer pages without manual re-intake.
- The interview has materially shrunk: the count of intake-prompted fields is lower at end of phase than at start; remaining fields are clearly the human-only set.

## Assumptions

- **Pull, not push.** Atlas fetches from repos on a schedule. No CI-hook integration with service teams in the prototype phase.
- **HTTPS-fetch first, JGit later.** Layer 1+2 only need to read specific files (openapi.json, pom.xml, the test directory listing). Full git clone is deferred until AST-level analysis (a future layer 3) actually needs it.
- **Dogfood-first.** Atlas's own three modules are the test bed. Once layer 1 works on `atlas-intake`'s OpenAPI spec, the production team can point it at their own services.
- **Springdoc for the dogfood.** `atlas-intake` (and the other two modules if they expose REST) gets `springdoc-openapi-starter-webmvc-ui` so they expose `/v3/api-docs`. Adding springdoc is part of M1.
- **Per-field provenance is the design rule.** Code-sync owns the rows it writes; intake owns the rest. Intake-source rows are never touched by code-sync, even on collision — collisions get logged for human review, not auto-resolved.
- **DB stays the source of truth.** Code is the source of truth for the *fact*; the DB caches it. Confluence renders from the DB. Code-sync is just another writer.
- **Auth deferred.** Dogfood specs are reachable from localhost or via public repo URLs. Production code-sync against private repos forces DD-001 sooner — out of prototype scope.

## Approach

Five review milestones. Each is independently reviewable, ends with a reflection (per the new CLAUDE.md guidance), and includes a session-resumable summary so handoff stays current. TDD throughout: tests first (red), minimum impl (green), refactor.

Code-sync responsibilities **fold into `atlas-intake` initially** rather than spawning a fourth Spring Boot module. Splits to its own module if and when M3+ adds enough surface to justify it. Recorded as a judgment call to revisit at M3.

## Milestones

### M1 — Provenance + OpenAPI ingestion (no rendering yet)

**Goal**: code-sync writes API rows to the DB from a remote OpenAPI spec; intake-written rows are untouched.

1. Flyway migration `V13__apis_source_and_openapi_url.sql`: add `apis.source TEXT NOT NULL DEFAULT 'intake'` with `CHECK (source IN ('intake','openapi','pom-xml','tests'))`; add `services.openapi_spec_url TEXT NULL`.
2. Update `ServiceRelationshipsRepository.insertApi` to accept and persist `source`. Existing intake callers pass `'intake'`.
3. Update `Service` entity with `openapiSpecUrl`.
4. New package `com.atlas.codesync` in `atlas-intake`. Components:
   - `OpenApiFetcher` — HTTPS GET, configurable timeout, returns raw spec text.
   - `OpenApiParser` — wraps `swagger-parser` (added as dep). Parses spec text into a list of endpoint records (method, path, description, auth, parameters, request/response schemas).
   - `CodeSyncCoordinator.refreshOpenApi(serviceId)` — orchestrates fetch + parse + upsert + audit. Upsert rule:
     - Insert/update `apis` rows with `source='openapi'` matching the spec.
     - Delete `apis` rows with `source='openapi'` no longer present in the spec.
     - **Never** touch `apis` rows with `source='intake'`.
     - Write a `service_changes` row with `change_type='updated'`, `changed_by='code-sync-openapi'`.
5. New REST endpoint `POST /api/code-sync/refresh/{serviceId}` for manual triggering (scheduled cadence deferred to M5+).
6. Tests (TDD; failing first):
   - `whenServiceHasNoOpenApiUrl_thenRefreshIsNoop`
   - `whenOpenApiSpecHasEndpoints_thenApisAreUpsertedWithOpenApiSource`
   - `whenIntakeApisExistAndOpenApiArrives_thenIntakeApisAreUntouched`
   - `whenOpenApiEndpointIsRemovedFromSpec_thenOpenApiSourceRowDeleted_butIntakeRowsRemain`
   - `whenSpecUrlReturns404_thenRefreshFails_andRowsAreNotMutated`
   - `whenSpecIsMalformed_thenParseFails_andDbIsUntouched`
   - `whenOpenApiSpecIsRefreshedTwice_thenSecondRunIsIdempotent`
7. Live verification: add Springdoc to `atlas-intake`'s pom.xml, register itself with `openapi_spec_url=http://localhost:8080/v3/api-docs`, hit the manual endpoint, observe `apis` rows with `source='openapi'` matching the actual exposed endpoints.

**Reflection at M1 boundary** (mandatory per CLAUDE.md §6).

### M2 — Per-endpoint Confluence pages

**Goal**: each API endpoint gets its own Confluence page with full schema-level detail.

1. New `ApiEndpointPageRenderer` in `atlas-confluence-sync` — emits storage-format with sections for parameters, request body, responses, auth, examples. Falls back gracefully when fields are absent.
2. `SyncCoordinator` extension: for each `Api` with `source='openapi'`, render a Confluence page titled `"{svc.name} — {METHOD} {path}"`, parented under the service page. Idempotent: existing page → update; new endpoint → create; removed endpoint → cleanup pass deletes orphan page (mirrors ADR-014 pattern).
3. Update the service-page renderer's APIs section: each row becomes a hyperlink to the per-endpoint page, with `source` badge in the table.
4. Per-endpoint page includes a back-link to the service page.
5. New `confluence_page_id` storage on `apis` table (Flyway `V14`) so endpoint pages are addressable across syncs.
6. Tests:
   - Renderer output for endpoint with full schema, minimal schema, no schema
   - Sync orchestration: creates new pages, updates existing, removes orphans
   - Service page links to endpoint pages with correct anchors
7. Live verification: `atlas-intake`'s endpoints render as separate Confluence pages parented under its service page.

**Reflection at M2 boundary.**

### M3 — Test-method extraction → "What this service guarantees" page

**Goal**: tests as living spec, surfaced as a Confluence page per service.

1. New `RepoFileFetcher` — uses GitHub Contents API (`/repos/{owner}/{repo}/contents/{path}?ref={branch}`) to recursively list `src/test/java/**/*.java` and fetch each file. Public repos only at this stage.
2. New `JavaTestExtractor` — parses Java source with `JavaParser` (no compilation needed, AST-only). Extracts `@Test`-annotated methods and their containing class, organized by package.
3. New table `service_test_scenarios` (Flyway `V15`): `(id, service_id, package_name, class_name, method_name, source, created_at, updated_at)` with unique `(service_id, package_name, class_name, method_name)`.
4. `CodeSyncCoordinator.refreshTests(serviceId)` — fetches test files via `repo_url`, extracts, upserts.
5. New `TestScenariosPageRenderer` + sync coordinator extension. Page title: `"{svc.name} — Tests"`. Per-class section with method names rendered as bullet points (the project rule: test names read as specs).
6. Tests:
   - Extractor on a real-test-directory fixture (use `atlas-intake`'s own tests)
   - Fetcher behavior on missing dirs and rate-limit headers
   - Renderer output ordered by class then method
   - Sync orchestration creates/updates/removes
7. Live verification: a "Tests" page appears under each dogfood service.

Decision point at end of M3: code-sync responsibilities are now substantial — does it stay folded in `atlas-intake` or get extracted into `atlas-code-sync`? Decide based on how `atlas-intake`'s tests + dependencies feel after M3 lands.

**Reflection at M3 boundary.**

### M4 — pom.xml → framework metadata + external dependencies

**Goal**: drop-in service registration shrinks dramatically because pom.xml fills in language, framework, and most external deps.

1. New `PomFetcher` — same HTTPS-GET pattern as `OpenApiFetcher`.
2. New `PomParser` — uses Maven Model API (`org.apache.maven:maven-model`).
3. Code-sync extension: refresh `services.language` (= `'Java'` if pom is Maven), `services.framework` (= `'Spring Boot {version}'` if `spring-boot-dependencies` is the parent or in dependency-management).
4. Upsert `service_external_deps` rows from declared dependencies, `source='pom-xml'`.
5. Heuristic for which deps count as "external": exclude any dep whose groupId starts with the org's prefix. For dogfood, exclude `com.atlas`. Configurable via `atlas.code-sync.org-group-prefix`.
6. Tests:
   - Parser handles simple poms, parent inheritance, dependency-management resolution
   - Refresh updates services.language/framework
   - external-deps upsert respects source rules
   - Collisions: intake-recorded external dep + pom-recorded same dep — both rows kept side-by-side, audit logged, page renderer dedups for display
7. Live verification: dogfood pages reflect Spring Boot 4.0.x and Java 21 automatically; external-deps section auto-populates.

**Reflection at M4 boundary.**

### M5 — Phase reflection + interview shrinkage

**Goal**: measure the change, slim the interview, decide what's next.

1. Count intake-prompted fields at start of phase (today: ~17) vs end of phase. Document delta.
2. Audit existing intake stages in `InterviewService.java`. For each, decide: kept, removed (now auto-derived), or made-optional (auto-derived but still askable as override).
3. Implement the interview slimming: remove or make-optional the now-auto-derived stages.
4. Re-run the dogfood: register a fresh service via the slim interview + a `repo_url` and `openapi_spec_url`, observe code-sync filling in the rest, observe richer Confluence pages than before.
5. Tests: interview state-machine tests updated; code-sync fills the gap on first sync after intake.
6. **Phase-level reflection** (per CLAUDE.md §6): are we still on track for the project's stated goals? What's surfaced? Where next — AI-narrated walkthroughs (a layer 3 we deliberately deferred), production-readiness (open DDs), or something else?

**End-of-phase reflection.**

## Open questions

- **Q1** (M1, judgment call default): fold code-sync into `atlas-intake` or split as `atlas-code-sync`? Default: fold; revisit at M3.
- **Q2** (M1): do `atlas-mcp` and `atlas-confluence-sync` get Springdoc too? They expose minimal REST. Worth it for dogfood completeness; cheap to add. Default: yes, in M1.
- **Q3** (M3): public-repo-only test-fetching is limiting for production. Capture as a new DD entry when M3 lands; for dogfood the atlas repo is public.
- **Q4** (M4): collision policy for intake-vs-pom external deps — keep both, prefer pom, prefer intake? Default: keep both, audit log, dedup at render. Revisit if it produces visibly ugly pages.

## Status log

- 2026-04-29 — plan committed.
- 2026-04-29 — M1 closed.

## Reflections

### M1 — Provenance + OpenAPI ingestion

**What's working**

- **Per-source provenance is the right hinge.** The `apis.source` column with a CHECK constraint plus a "code-sync only touches its own source rows" rule kept intake-source rows safe through every code path, including the live round-trip. Every behavior we cared about (skip on collision, delete-when-removed, idempotent re-run) reduces to a query on `source`.
- **Behaviour-focused tests caught the easy bugs; the live round-trip caught the hard ones.** The seven Testcontainers tests passed first try, but pointing the system at atlas-intake's own DB exposed two design gaps the unit tests had blind spots on: (a) the unique constraint on `(service_id, method, path)` blocks parallel intake- and openapi-source rows, and (b) per-statement auto-commit means a partial-failure mid-loop leaves orphan rows. Both fixes (skip-when-intake-owns + `@Transactional`) plus two new tests landed in the same milestone.
- **WireMock as the only mock works.** ADR-006's "mock at architectural seams only" pays off: the parser, repository, and JPA all run real against Testcontainers, and the only fake is the remote OpenAPI host. Every test exercises the production code path end-to-end below the HTTP boundary.
- **Springdoc + the JAXB shim is a reliable dogfood input.** The `javax.xml.bind` gap on Spring Boot 4 is annoying but documented; the one-line shim resolves it.

**What's not — and what to do about it**

- **TDD discipline drifted toward "tests-and-code-together," not "tests-first."** I drafted the coordinator skeleton, then wrote tests, then ran them green on the first compile. The behavior coupling is correct (tests don't pin internals) but the red→green discipline ADR-006 calls for got cut to half. Next milestone: write at least one test per behavior *before* the corresponding impl line exists, and only then implement.
- **Dogfood data is already drifting.** The live test exposed `/api/smoke/anthropic` (intake row) versus `/api/smoke/llm` (real path post-ADR-013). Intake never updates the row when code changes. This is exactly the staleness code-driven docs is meant to fix and gives M5's "interview shrinkage" a concrete first target: stop asking about APIs at intake time, let code-sync own them, retire stale intake rows on re-sync.
- **Spec descriptions are blank from springdoc unless controllers carry `@Operation`.** Today every openapi row in the dogfood has a null description. M2's per-endpoint Confluence pages will look thin without these — worth either annotating Atlas's controllers or accepting the gap as a "production team adds annotations to their controllers" handoff item.
- **Skip-on-collision is the conservative choice and probably wrong long-term.** Today, intake's row stays authoritative because we don't want to surprise-overwrite human work. By M5 the model flips: intake stops asking about APIs, openapi becomes the only source, and the skip becomes dead code. Worth reading the skip behavior as transitional rather than permanent.
- **Code-sync lives inside `atlas-intake` for now.** Fine for one writer, will feel cramped by M3 when test extraction adds more surface. Plan-stated trigger: revisit at M3.

**Resumable summary** *(propagated to `docs/handoff/current-state.md`)*

Branch `main` at commit pending — this commit lands M1 of the code-driven-documentation plan. 168 tests pass across four modules (atlas-domain 33, atlas-intake 50, atlas-mcp 24, atlas-confluence-sync 61). New: `apis.source` provenance column + `services.openapi_spec_url` (V13 migration); `OpenApiFetcher` / `SwaggerOpenApiParser` / `CodeSyncCoordinator` / `CodeSyncController` (in `atlas-intake/src/main/java/com/atlas/codesync/`); springdoc 2.8.13 + jaxb-api 2.3.1 deps so atlas-intake exposes its own `/v3/api-docs`. Live-verified: hitting `POST /api/code-sync/refresh/{atlas-intake-id}` produced 3 openapi-source rows, 1 skip on intake-owned, intake rows untouched. Next milestone: M2 — per-endpoint Confluence pages parented under the service page, with cleanup-on-removal mirroring ADR-014. Open carry-overs: stale intake row at `/api/smoke/anthropic` (predates ADR-013 rename) — not cleaned; springdoc-generated descriptions are null without `@Operation` annotations; code-sync still inside `atlas-intake` (revisit at M3).
