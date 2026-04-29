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

**Discipline reminder (from M1 reflection)**: in M1 the red→green discipline drifted toward writing tests and impl together. From M2 onward, write at least one failing test for each behavior *before* the corresponding impl exists.

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

   **Scope decision**: M2 handles create + update only. Orphan cleanup (when code-sync deletes an api row, its Confluence page lingers) is **deferred** — no soft-delete on `apis` yet, no list-children API on `ConfluenceClient`. Same accept-it-at-prototype-scale that the services table had pre-ADR-014. Captured as an M2 follow-up; will be addressed alongside M3 or as a one-off cleanup.
6. **Carry-over from M1**: springdoc emits null operation descriptions absent `@Operation` annotations on controllers. Decide one of: (a) annotate Atlas's controllers (`IntakeController`, `SmokeController`, `CodeSyncController`) with `@Operation(summary=...)` for dogfood polish — small, ~1 line per method; (b) accept null descriptions and document in the plan's open questions as "production teams annotate their own controllers"; (c) both. Default: (a) for the dogfood since the per-endpoint pages need *something* to render.
7. Tests:
   - Renderer output for endpoint with full schema, minimal schema, no schema (incl. null description)
   - Sync orchestration: creates new pages, updates existing, removes orphans
   - Service page links to endpoint pages with correct anchors
8. Live verification: `atlas-intake`'s endpoints render as separate Confluence pages parented under its service page.

**Reflection at M2 boundary.**

### M2.5 — Orphan-page cleanup for per-endpoint pages (carry-over from M2)

**Goal**: when code-sync drops an api row, its Confluence page disappears on the next sync — same shape as ADR-014 for services.

1. V15 migration: `apis.deleted_at TIMESTAMP NULL` + index.
2. `ServiceRelationshipsRepository`: existing reads filter to `deleted_at IS NULL`; `deleteApi(apiId)` rewrites to `UPDATE apis SET deleted_at = CURRENT_TIMESTAMP`. New: `findSoftDeletedApisWithConfluencePage()`, `clearApiConfluencePageId(apiId)`.
3. `SyncCoordinator.cleanupDeletedApiPages()` runs at the start of `syncAll()` (parallel to `cleanupDeletedServices()`): delete each page, null the id, log per-api errors.
4. Tests:
   - Cleanup deletes the Confluence page and nulls the api row's `confluence_page_id`
   - 404 on already-deleted page is treated as success
   - Soft-deleted api rows are invisible to the renderer via `findApisFor`
5. Known limitation: the unique constraint on `(service_id, method, path)` still fires on re-insert against a soft-deleted row. Acceptable at prototype scale; production fix is either a partial unique index (Postgres-only) or a reactivate-on-collision pattern in code-sync (mirrors ADR-014's intake reactivate). Documented in the V15 migration comment and called out here.

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
4. **Carry-over from M1**: once intake stops asking about APIs, the existing `source='intake'` rows that overlap a service's OpenAPI spec become migration debt. The dogfood already has a concrete instance — `/api/smoke/anthropic` (intake row) was renamed to `/api/smoke/llm` in code per ADR-013, but intake never updated the row, so code-sync's intake-collision skip can't help. Decide a one-time migration: (a) auto-delete `source='intake'` rows whose `(method, path)` no longer matches any current code-sync result for the same service, with audit `changed_by='m5-intake-shrinkage'`; (b) flag them for human review without auto-deleting; (c) leave them alone and call it intentional "human notes that survive code refactors". Default: (a) — the whole point of the phase is to make the docs not lie.
5. Re-run the dogfood: register a fresh service via the slim interview + a `repo_url` and `openapi_spec_url`, observe code-sync filling in the rest, observe richer Confluence pages than before. Confirm `/api/smoke/anthropic` is gone from atlas-intake's apis rows.
6. Tests: interview state-machine tests updated; code-sync fills the gap on first sync after intake; the M5 stale-intake-cleanup migration is exercised against a fixture.
7. **Phase-level reflection** (per CLAUDE.md §6): are we still on track for the project's stated goals? What's surfaced? Where next — AI-narrated walkthroughs (a layer 3 we deliberately deferred), production-readiness (open DDs), or something else?

**End-of-phase reflection.**

## Open questions

- **Q1** (M1, judgment call default): fold code-sync into `atlas-intake` or split as `atlas-code-sync`? Default: fold; revisit at M3.
- **Q2** (M1): do `atlas-mcp` and `atlas-confluence-sync` get Springdoc too? They expose minimal REST. Worth it for dogfood completeness; cheap to add. Default: yes, in M1.
- **Q3** (M3): public-repo-only test-fetching is limiting for production. Capture as a new DD entry when M3 lands; for dogfood the atlas repo is public.
- **Q4** (M4): collision policy for intake-vs-pom external deps — keep both, prefer pom, prefer intake? Default: keep both, audit log, dedup at render. Revisit if it produces visibly ugly pages.

## Status log

- 2026-04-29 — plan committed.
- 2026-04-29 — M1 closed.
- 2026-04-29 — M2 closed (create + update; orphan cleanup deferred per scope decision).
- 2026-04-29 — M2.5 closed (orphan cleanup via soft-delete + cleanup pass, mirroring ADR-014).
- 2026-04-29 — M3 closed (test extraction; code-sync stays folded into atlas-intake per the M3.8 decision).

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

### M2 — Per-endpoint Confluence pages

**What's working**

- **Discipline reset on TDD held.** Every behavior in M2 had a failing test before the impl line existed: ten ApiEndpointPageRendererTest cases (red on assertions with the renderer returning `""`), three SyncCoordinatorIntegrationTest cases (red on WireMock verifies), two ServicePageRendererTest cases (red on hyperlink absence). 15 new tests, all written before their corresponding code. The "red on compile" → "red on assertions" → "green" sequence shows up clearly in the test runs.
- **The existing rendering and sync patterns absorbed the new feature cleanly.** ApiEndpointPageRenderer mirrors the structure of ServicePageRenderer (pure function, escape helpers, thin-note pattern). SyncCoordinator's per-endpoint branch reuses the same create-or-update-with-404-fallback shape as the service-page branch. ApiPresentation gained one field; no new top-level types were needed except the renderer's context record.
- **The carry-over `@Operation` pass paid off twice.** Annotating IntakeController, SmokeController, and CodeSyncController gave the spec real summaries (visible in `/v3/api-docs` after restart), and the M1 round-trip re-ran on the dogfood produced 3 updated openapi rows whose descriptions are now populated. M2's per-endpoint pages will render with real text, not "(No description documented.)".
- **Scope boundary held.** I deliberately deferred orphan cleanup at the start, documented it inline in the plan, and resisted folding it back in mid-stream. M2 lands as a coherent shippable increment.

**What's not — and what to do about it**

- **Orphan cleanup is a real gap, not a nice-to-have.** A service whose code-sync drops an api row will leave a stale Confluence page until something cleans it up. The pattern from ADR-014 (soft-delete + cleanup pass on syncAll) is the right answer. Worth landing as M2.5 before M3 expands the surface area further; otherwise the dogfood accumulates orphan pages with each spec drift.
- **Live sync-side verification deferred.** The integration tests cover the WireMock contract end-to-end, but I didn't drive a real `atlas-confluence-sync` run against the dogfood ATLAS space — that would have created ~15 new endpoint pages without explicit consent. Worth doing before M3 starts so the page-tree shape is reviewable.
- **ApiPresentation grew one field; ApiSummary grew one field. ServicePageContext is at 9.** Each addition was small, but the trend is real. Worth watching at M3 — if test-method records also need a per-record context, consider whether a unified Pre-rendered shape would reduce the constructor friction.
- **The renderer's "spec link" is naïve.** It points at the raw `/v3/api-docs` JSON URL, which is functional but ugly. A future polish pass might link to a Swagger UI URL when one is configured. Capture as a follow-up; not blocking.

**Resumable summary** *(propagated to `docs/handoff/current-state.md`)*

Branch `main` at commit pending — this commit lands M2 of the code-driven-documentation plan. 183 tests pass across four modules (atlas-domain 33, atlas-intake 50, atlas-mcp 24, atlas-confluence-sync 76). New: V14 migration adds `apis.confluence_page_id`; `ApiEndpointPageRenderer` + `ApiEndpointPageContext` in `atlas-confluence-sync`; `SyncCoordinator.syncEndpointPages` creates/updates per-endpoint pages parented under the service page (with 404-fallback to recreate); `ServicePageRenderer` linkifies APIs section to per-endpoint pages when URL is known; `ApiPresentation` gained `endpointPageUrl`; `@Operation` annotations on atlas-intake's controllers (M1 carry-over). Live: spec at `/v3/api-docs` now carries real summaries; code-sync re-run updated 3 openapi rows with populated descriptions. Sync-side live-verify against real Confluence deferred. Next milestone: M3 — test-method extraction. Open carry-overs: orphan cleanup for endpoint pages (M2.5 candidate); stale `/api/smoke/anthropic` intake row in dogfood DB; code-sync still inside `atlas-intake` (decision point at end of M3).

### M2.5 — Orphan-page cleanup for per-endpoint pages

**What's working**

- **The ADR-014 pattern composed cleanly at the endpoint grain.** `apis.deleted_at` (V15), filter on read, soft-delete on write, cleanup pass on `syncAll()`. The new `SyncCoordinator.cleanupDeletedApiPages()` is the per-endpoint twin of `cleanupDeletedServices()` and reads almost identically — readers can pattern-match between them.
- **Existing tests stayed green for the right reasons.** The CodeSyncCoordinator's "endpoint removed from spec" test now exercises soft-delete instead of hard-delete and still asserts the same behaviour (live row count drops by one) because `findApisFor` filters. ADR-006's "test the public behaviour, not the internals" rule paid off — the implementation flipped under the test, not the test under the implementation.
- **`ConfluenceClient.deletePage` already swallows 404.** `cleanupDeletedApiPages` didn't need any 404-special-casing — the 404 path and the success path collapse to the same line. Less code than the equivalent service-level cleanup, which predates that change.

**What's not — and what to do about it**

- **The unique constraint on `(service_id, method, path)` still applies across soft-deleted rows.** If code-sync drops `/v1/foo` and the spec re-adds it before the next syncAll cleans up, the re-insert fails. Documented inline in the V15 migration and in the M2.5 plan steps. Fix is either a partial unique index (Postgres-only, breaks portability) or an intake-style reactivate-on-collision in `CodeSyncCoordinator`. Worth keeping in mind but not blocking — the failure mode is loud (constraint violation) and the workaround is "wait for the next syncAll". Capture as an explicit known-issue if production prep ever needs it.
- **The strengthened "hidden from findApisFor" test caught a passes-for-wrong-reason failure mode.** First version of that test passed under hard-delete because the row was simply gone. Adding the "row still in DB" assertion was the difference between testing what the code *does* vs what the user *wants*. Worth being more skeptical of pass-on-first-run tests in future milestones — the most useful tests are ones that change colour when the implementation flips.
- **Repository surface is growing.** Three new methods on `ServiceRelationshipsRepository` for M2.5 alone (deleteApi semantics changed, `findSoftDeletedApisWithConfluencePage`, `clearApiConfluencePageId`). The class is approaching 350 lines. M3 will add more; worth thinking about whether to split apis-specific reads into their own repository class as part of M3's "code-sync grew, time to split into atlas-code-sync" decision.

**Resumable summary** *(propagated to `docs/handoff/current-state.md`)*

Branch `main` at commit pending — this commit lands M2.5 of the code-driven-documentation plan. 186 tests pass across four modules (atlas-domain 33, atlas-intake 50, atlas-mcp 24, atlas-confluence-sync 79). V15 migration adds `apis.deleted_at`; `ServiceRelationshipsRepository.deleteApi` now soft-deletes and live reads filter to `deleted_at IS NULL`; new `findSoftDeletedApisWithConfluencePage` + `clearApiConfluencePageId` feed `SyncCoordinator.cleanupDeletedApiPages()`, which runs at the start of `syncAll()` parallel to `cleanupDeletedServices()`. New `SoftDeletedApiPage` record in `atlas-domain`. The cleanup is idempotent and 404-tolerant. Known limitation: the unique constraint on `(service_id, method, path)` still applies to soft-deleted rows, so re-inserting a recently-removed endpoint fails until the next `syncAll` cleanup or until reactivate-on-collision lands. Next milestone: M3 — test-method extraction. Open carry-overs: stale `/api/smoke/anthropic` intake row in dogfood DB (M5 cleanup); code-sync still inside `atlas-intake` (decision point at end of M3); reactivate-on-collision for re-added endpoints (production-readiness item).

### M3 — Test-method extraction → "What this service guarantees" page

**What's working**

- **The four-piece pipeline composed cleanly.** `RepoFileFetcher` (HTTPS + GitHub Contents API), `JavaTestExtractor` (JavaParser AST), `CodeSyncCoordinator.refreshTests` (orchestration + DB), `TestScenariosPageRenderer` (Confluence body) — each tested in isolation, then end-to-end via the live atlas-intake fetch (41 scenarios extracted from the actual `main` branch on GitHub). Each piece is the smallest thing that does its job.
- **AST-only parsing means zero coupling to the target's classpath.** JavaParser reads source-level structure with no compilation step, so the extractor works against any service's tree regardless of its own dependencies. That's the right shape for code-driven docs at scale: one tool, many targets.
- **Red-first discipline held throughout.** 33 new tests across four classes; every behavior asserted before the impl line existed. The scope ratchets up vs M1 — five distinct components, each with its own test suite — and the discipline still scaled. The bug discovered while writing tests (`package new.pkg` is a Java keyword collision) is exactly the kind of thing TDD is designed to surface during construction, not during dogfood.
- **Per-source provenance generalises.** The `source` column on `service_test_scenarios` reuses the same vocabulary as `apis.source` (intake / openapi / pom-xml / tests). Code-sync writes `tests`-source rows and never touches anything else; the policy is uniform across data types.

**What's not — and what to do about it**

- **MariaDB index-key limit caught V16 mid-merge.** The four-column unique index on `service_test_scenarios` exceeded MariaDB's 3072-byte limit because TEXT columns contribute their full max length to the prefix. Fixed by switching to `VARCHAR(191)` for the identifier columns. Worth recording as a project rule: any unique constraint on identifier-style strings should use `VARCHAR(191)` (or shorter), not `TEXT`. A note in `CLAUDE.md` Constraints section would help future migrations not rediscover this.
- **The dogfood DB now carries 41 scenarios from `main` — not the local M3 work.** The live verify hit GitHub's `main`, which doesn't include the JavaTestExtractor / RepoFileFetcher / etc. tests we just wrote. Once this commit is pushed, a re-run will balloon the count significantly. Worth re-running refresh-tests after push to validate against the latest tree.
- **Test-name normalisation is a follow-up.** Test method names like `whenSomethingHappens_thenSomethingElse` render as a code-styled blob; a polish pass could split on `_then` and produce a "Given X, then Y" two-line layout. Not blocking; capture as a renderer follow-up.
- **Public-repo-only is a real ceiling.** Production teams' repos are usually private, requiring a GitHub PAT or a GitHub App. Recording as a new deferred decision (call it DD-014) so the production team has a concrete work item.
- **`atlas-intake` is now ~2x more code than its name suggests.** The `com.atlas.codesync` package is up to 13 main-source classes plus 33 tests with three substantial third-party deps (swagger-parser, JavaParser, springdoc + jaxb shim). Per the plan-stated decision point, I considered splitting into a fourth `atlas-code-sync` Spring Boot app and decided **stay folded** for the prototype: the package is internally cohesive (one verb — "derive facts from code"), all writers target the same DB schema, and splitting would force RPC between intake and code-sync or duplicate repo-access code. Production team has a clean seam (the package boundary) to lift if they want clearer ownership later. Captured here so the next session inherits the rationale.

**Resumable summary** *(propagated to `docs/handoff/current-state.md`)*

Branch `main` at commit pending — this commit lands M3 of the code-driven-documentation plan. 219 tests pass across four modules (atlas-domain 33, atlas-intake 74, atlas-mcp 24, atlas-confluence-sync 88), up from 186 after M2.5. V16 adds `services.module_path`, `services.tests_page_id`, and the `service_test_scenarios` table (with `VARCHAR(191)` identifier columns to fit MariaDB's index-key limit). New `JavaTestExtractor` (JavaParser AST), `RepoFileFetcher` (GitHub Contents API; public repos only), `CodeSyncCoordinator.refreshTests` (transactional fetch + parse + upsert + delete), and `POST /api/code-sync/refresh-tests/{serviceId}` endpoint. New `TestScenariosPageRenderer` and `SyncCoordinator.syncTestsPage` create/update one "Tests" page per service, parented under the service page; lifecycle mirrors M2's per-endpoint pages. Live: refreshing atlas-intake's tests against `https://github.com/justinwells85/atlas` extracted 41 scenarios (matches `main`-branch state pre-this-commit; will increase post-push once the new tests are in the tree). Decision recorded: code-sync stays folded into `atlas-intake` for the prototype. Next milestone: M4 — pom.xml ingestion. Open carry-overs: stale `/api/smoke/anthropic` intake row in dogfood DB (M5 cleanup); reactivate-on-collision for re-added endpoints; test-name normalisation for prettier rendering; private-repo PAT support (new DD-014 candidate); MariaDB project rule about VARCHAR(191) for index columns; sync-side live-verify of tests pages against the real ATLAS space deferred.
