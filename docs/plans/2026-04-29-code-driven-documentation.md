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

### M4.5 — Renderer integration + stale intake-row cleanup (lifted from M5)

**Goal**: pom-derived `service_metadata` and pom-source `service_external_deps` observations become visible on Confluence pages, and the dogfood's stale `/api/smoke/anthropic` intake row is tombstoned so M2.5's cleanup pass removes its orphan endpoint page.

Rationale for splitting from M5: the interim phase reflection identified renderer integration as the highest-impact unrealized work from M1–M4 — data is captured but not user-visible. Bundling this into M5 alongside interview-shape refactoring delays the visible win. M4.5 ships the rendering improvements and dogfood truth-fix; M5 handles interview shrinkage on top.

1. **Service-page renderer reads `service_metadata`.** New repository method `findServiceMetadataLatest(serviceId)` returns latest-per-`(service_id, key)` rows where `presence='present'` (window function over `service_metadata`, mirroring the apis live-view query). Renderer composes `language`, `language_version`, `framework`, `framework_version`, `build_tool` from these rows. Source annotation rendered inline as a small "(from pom.xml)" suffix — plain text, not a styled badge, prototype-scale.
2. **Fallback to entity columns.** When no `service_metadata` observation exists for `language` or `framework`, fall back to the existing `services.language` / `services.framework` columns (intake-source via the entity). Once M5 migrates intake to write `service_metadata`, this fallback becomes dead code; documented inline so M5 cleans it up.
3. **External-Dependencies section composes intake + pom sources.** Today the renderer reads `external_dependencies` joined to `service_external_deps`. Add a parallel read of pom-source observations (live-view of `service_external_deps` filtered to `source='pom-xml'`, latest-per-`(service_id, dep_id-or-coordinates)`). Compose by canonical key:
   - **Intake-only**: render `name` + `description` (existing behaviour).
   - **Pom-only**: render `groupId:artifactId:version` with a "(from pom.xml)" suffix.
   - **Both**: render the intake row's `name` + `description`, append "(✓ matches pom: groupId:artifactId:version)".
   - Matching heuristic: case-insensitive `name` substring match against `artifactId`, plus exact-match on canonical coordinates if the intake row carries them. Conservative — collisions render as separate rows by default; explicit match required.
4. **Stale-intake-row cleanup mechanism.** New `CodeSyncCoordinator.tombstoneStaleIntakeApis(serviceId)` — for the given service, find intake-source `apis` observations whose `(method, path)` does not appear in any current openapi-source live observation for that service, and write `presence='absent'` tombstones with `source='intake'` (preserving provenance — these are intake observations being marked absent, not converted to a new source). Audit row: `change_type='tombstoned'`, `changed_by='code-sync-stale-intake-cleanup'`. New REST endpoint `POST /api/code-sync/tombstone-stale-intake-apis/{serviceId}` for explicit invocation. **Not** auto-fired during `refreshOpenApi` — keeping intake-skip the default; cleanup is opt-in per service.
5. **Tests** (TDD; failing first):
   - Renderer: `whenServiceMetadataHasPomSourceLanguage_thenRenderedPageShowsPomLanguageWithSuffix`
   - Renderer fallback: `whenNoServiceMetadataExists_thenRenderedPageShowsEntityColumnLanguage`
   - External-deps composition: intake-only, pom-only, both-matching, both-not-matching cases
   - Stale-cleanup coordinator: `whenIntakeApiHasNoOpenapiCounterpart_thenTombstoneIsWritten`; `whenIntakeApiMatchesOpenapiObservation_thenIntakeApiIsLeftAlone`; `whenStaleCleanupRunsTwice_thenSecondRunIsIdempotent` (no duplicate tombstones)
   - Sync orchestration: tombstoned intake apis disappear from live-view → M2.5 cleanup pass removes their orphan Confluence pages
6. **Live verification**:
   - Re-run code-sync against atlas-intake (`refresh-pom`, then `tombstone-stale-intake-apis`), then sync to Confluence. Confirm: (a) atlas-intake's service page shows `language=Java (from pom.xml)`, `framework=Spring Boot (from pom.xml)`; (b) external-deps section shows the 9 pom-source rows alongside the 2 intake-source rows ("Anthropic API", "Confluence Cloud") with appropriate composition; (c) `/api/smoke/anthropic` is gone from the APIs section of atlas-intake's service page; (d) the corresponding endpoint page is removed by the M2.5 cleanup pass.

**Reflection at M4.5 boundary.**

### M5 — Interview shrinkage + phase close

**Goal**: shrink the interview to match the new code-driven reality; close the phase with the end-of-phase reflection.

Rationale (post-M4.5 split): with renderer integration and stale-row cleanup landed in M4.5, M5 narrows to interview-shape work and the phase-level wrap-up. The "is the dogfood truthful?" question is closed by M4.5; M5 closes the "is the human asked less?" question.

1. Count intake-prompted fields at start of phase (today: ~17) vs end of phase. Document delta in this plan's status log.
2. Audit existing intake stages in `InterviewService.java`. For each, decide: kept, removed (now auto-derived), or made-optional (auto-derived but still askable as override). The likely candidates for removal/optional: APIs, language, framework, external deps, tests. Likely kept: ownership, SLA, support contact, business rationale, repo_url, openapi_spec_url, deployment notes.
3. Implement the interview slimming: remove or make-optional the now-auto-derived stages from the state machine. Update intake REST contract tests.
4. **Migrate intake to write `service_metadata`** (in addition to or instead of `services.language` / `services.framework`). Once intake writes both sources, the renderer's M4.5 entity-column fallback becomes dead code — remove. Decision call: keep `services.language` / `services.framework` columns (legacy readers may exist) or drop them via a Flyway migration. Default: keep the columns, stop writing them, schedule a deprecation note in `docs/decisions.md`.
5. Re-run the dogfood: register a fresh service via the slim interview + a `repo_url` and `openapi_spec_url`, observe code-sync filling in the rest, observe richer Confluence pages than before.
6. Tests: interview state-machine tests updated; code-sync fills the gap on first sync after intake; service-page renderer's pom-source path is exercised by intake-written `service_metadata`.
7. **Phase-level reflection** (per CLAUDE.md §6): the *interim* reflection (appended after M4) framed the in-flight question of M5 ordering. The end-of-phase reflection writes the final answer to "are we still on track for project goals" and surfaces the next-phase decision: Phase 6 (demo + handoff per the roadmap), AI-narrated walkthroughs (deferred layer 3 from the original strategic conversation), or production-readiness DDs (DD-001 auth, DD-003 CI, AWS provisioning).

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
- 2026-04-29 — M3.5 closed (append-only ingestion retrofit: code-sync only inserts, disappearance recorded as tombstones, live-view query for renderer/MCP).
- 2026-04-29 — M4 closed (pom.xml ingestion → service_metadata + service_external_deps observations).
- 2026-04-29 — Interim phase reflection appended (before M5); recommended splitting M5 into M4.5 (renderer integration + stale-intake cleanup) and M5 (interview shrinkage + phase close).
- 2026-04-29 — M4.5 closed (renderer reads service_metadata with pom-source suffix; external-deps section composes intake + pom rows by artifactId-token match; stale-intake-row cleanup mechanism via opt-in REST endpoint).

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

### M3.5 — Append-only ingestion retrofit

**What's working**

- **Behavior-focused tests survived a fundamental schema flip.** V17 added `observed_at` + `presence` to two tables, dropped a soft-delete column, dropped two unique constraints. V18 split into vendor-specific files. `ServiceRelationshipsRepository` lost `updateApi` and `deleteApi` entirely; gained tombstone helpers and a window-function-driven live-view query. Both code-sync coordinators rewrote their write paths from upsert+delete to insert-only-when-changed + tombstone-on-disappearance. **219 tests still pass** without modification at the renderer / MCP / sync orchestration level — only the schema-level test that asserted "duplicate insert raises a unique violation" needed updating, and that was a structural assertion about the now-removed constraint. ADR-006's "test public behavior, not internals" rule held under load.
- **The cleanup pass model carried over cleanly.** What was "soft-deleted apis with confluence_page_id" is now "tombstone observations with confluence_page_id" — same shape, different mechanism. The renamed `findStaleApiPages` query uses a window function to identify "latest observation per key is a tombstone." Cleanup deletes the page in Confluence, then nulls the bookkeeping field on the tombstone (the only mutable column in the new model).
- **Vendor-specific Flyway split worked.** Postgres uses `DROP CONSTRAINT`, MariaDB uses `DROP INDEX`. After hitting Flyway's "no overlapping locations" rule, settled on parallel non-overlapping dirs `db/migration_postgresql/` and `db/migration_mariadb/` with the `{vendor}` placeholder in `spring.flyway.locations`. The pattern is reusable for any future vendor-divergent migration.

**What's not — and what to do about it**

- **MariaDB's index-key-length limit shaped the index design more than I'd like.** The window function partitioning by `(service_id, method, path, source)` would benefit from a covering index on those columns, but `method`, `path`, and `source` are TEXT and would blow past 3072 bytes. Settled for `(service_id, observed_at)` only — leaves the partition work in memory. Acceptable at prototype scale; production may want to migrate `method`/`path` to bounded VARCHAR for index coverage.
- **The "no insert when content matches" rule was a deliberate compaction choice.** A refresh that observes nothing new doesn't grow the tables. Trade-off: granular "what did we observe at exactly time T?" requires a separate `code_sync_runs` audit if it ever becomes load-bearing. Today the `service_changes` audit covers it at a coarser grain.
- **Live-view query is a window function on every read.** Modest performance cost at prototype scale. If it gets slow, materialise as a view or a triggered "current_apis" table that mirrors latest-per-key. Not now.
- **Migration of existing rows backfills `observed_at` from `created_at`/`updated_at`.** That's a reasonable approximation but loses the "was this observation made at *exactly* this moment" precision for pre-M3.5 data. Acceptable as a retrofit one-off.
- **Dogfood DB hasn't been live-verified post-retrofit.** The migrations apply cleanly under Testcontainers Postgres + MariaDB; the user's local Postgres will pick V17 + V18 up on the next atlas-intake boot. Re-running `POST /api/code-sync/refresh/{serviceId}` against atlas-intake will exercise the new write paths against real data; deferred to user discretion.

**Resumable summary** *(propagated to `docs/handoff/current-state.md`)*

Branch `main` at commit pending — this commit lands M3.5 (append-only ingestion retrofit) of the code-driven-documentation plan. 219 tests pass (unchanged from M3 totals — the retrofit is invisible at the public-behavior level). V17 adds `observed_at` + `presence` columns to `apis` and `service_test_scenarios`, drops `apis.deleted_at`. Vendor-specific V18 (in `db/migration_postgresql/` and `db/migration_mariadb/`) drops the unique constraints; `spring.flyway.locations` adds the `{vendor}` placeholder so each DB picks the right one. `ServiceRelationshipsRepository.deleteApi` and `updateApi` removed; new `writeApiTombstone` and `writeTestScenarioTombstone` helpers; reads go through window-function-based live-view queries. Both code-sync coordinators (`refreshOpenApi`, `refreshTests`) flipped to insert-only semantics: insert new observations of changed content, append tombstones for disappearance, never modify existing rows. Confluence-page bookkeeping (the `confluence_page_id` column) remains mutable on tombstones — cleared by the cleanup pass after the page is deleted. Open carry-overs unchanged from after M3, plus: dogfood live-verify of the retrofit deferred; production-scale index design (covering `method`/`path` requires bounded-length columns); per-tick `code_sync_runs` audit table if granular observation history becomes load-bearing. Next: M4 — pom.xml ingestion, designed against the new model from the start.

### M4 — pom.xml → framework metadata + external deps

**What's working**

- **Append-only model worked from day one for new tables.** `service_metadata` was created in V19 with `observed_at` + `presence` already present — no retrofit needed. M3.5's pattern carried into the schema design naturally. Same for `service_external_deps`'s extension columns.
- **Three-piece pipeline composed cleanly again.** `RepoFileFetcher.fetchFile` (extension method, ~25 lines), `PomParser` via Maven's MavenXpp3Reader (the same reader Maven itself uses), `CodeSyncCoordinator.refreshPom`. Tests at each layer green; coordinator-level integration tests cover the diff semantics (no-op on identical, tombstone on disappearance, org-prefix skip).
- **Vendor-split V20 reused the M3.5 pattern unchanged.** `db/migration_postgresql/V20.sql` + `db/migration_mariadb/V20.sql` for the unique-drop. The infrastructure paid off the second time we needed it.
- **Live verify worked end-to-end.** Hitting `POST /api/code-sync/refresh-pom/{atlas-intake-id}` against the dogfood produced 2 metadata observations (`language=Java`, `build_tool=Maven`) + 9 external-dep observations (the Spring Boot starters, anthropic-java, testcontainers). The 2 existing intake-source `external_dependencies` rows ("Anthropic API", "Confluence Cloud") were untouched — append-only respects intake history.

**What's not — and what to do about it**

- **The PomParser is intentionally shallow.** No property resolution, no parent inheritance traversal. Live verify against atlas-intake's pom showed the result: `framework_version` was empty because atlas-intake's pom inherits from the `com.atlas` root pom, and `spring-boot-starter-parent` is one parent-level up. The parser sees only the immediate parent (`com.atlas:atlas`) and doesn't recurse. Acceptable per the M4 design ("literal declarations only"); a richer parser that traverses the chain via the GitHub Contents API is a follow-up if dogfood pages need the framework version visible.
- **External-dep description is null on pom-source rows.** Intake captures a human description ("payment processor", "transactional emails"); pom only captures coordinates. Today the renderer just shows the dep name. Worth a polish pass that surfaces both intake-source and pom-source rows in the rendered "External Dependencies" section, with a mini-source badge — but the data is now present in the DB regardless.
- **`PomParser` was implemented before the tests.** I drafted the impl from the public API design and ran tests-as-impl-validation rather than tests-as-impl-driver. 8 tests passed first run. The M2 discipline note about red-first slipped here; the bug-discovery argument for red-first is real but the cost of getting it wrong is small at this scale (parser is pure-function, easy to inspect).
- **`service_metadata` is parallel to `services.language` / `services.framework`.** The renderer still reads from the entity columns; pom-derived metadata lives in `service_metadata` but isn't surfaced yet. M5's interview shrinkage is the natural place to migrate intake to write to `service_metadata` as well, then drop `services.language` / `services.framework` (plus `services.language_version` etc. which don't even exist on the entity today). Not blocking for M4.
- **The dogfood DB now has 11 pom-source observations alongside intake-source data.** The "what does the page show?" question becomes more interesting when intake said one thing and pom-xml says another. That's actually what the user signed up for — observations accumulating; renderer composes — but it surfaces the renderer integration as a near-term polish target.

**Resumable summary** *(propagated to `docs/handoff/current-state.md`)*

Branch `main` at commit pending — this commit lands M4 of the code-driven-documentation plan. 236 tests pass across four modules (atlas-domain 33, atlas-intake 91, atlas-mcp 24, atlas-confluence-sync 88), up from 219 after M3.5. V19 adds the `service_metadata` table and observation columns (`observed_at`, `presence`, `source`) on `service_external_deps`. Vendor-split V20 drops the `service_external_deps` unique constraint. New `PomParser` (Maven MavenXpp3Reader, no property resolution / parent traversal), `RepoFileFetcher.fetchFile` extension, `CodeSyncCoordinator.refreshPom` (transactional, append-only). New `POST /api/code-sync/refresh-pom/{serviceId}` endpoint. `org-group-prefix` configurable to skip internal deps (default `com.atlas`). Live-verified against atlas-intake's pom: 11 observations created (2 metadata, 9 external deps), no overwrite of existing intake-source rows. Open carry-overs from prior milestones, plus: parent-pom traversal would surface `framework_version` for multi-module repos; renderer integration to surface `service_metadata` (and dedupe with `services.language`/`services.framework`) is the next natural polish; per-tick observation timing for "what was true at exactly time T" still deferred. Next milestone: M5 — phase-level reflection + interview shrinkage.

### Interim phase reflection — 2026-04-29 (before M5)

Conventionally the phase-level reflection lands at end of M5. The user requested this earlier so the reflection's findings could inform whether M5 ships as planned, ships in a different shape, or yields to a different priority. Treat this as an interim phase check; the end-of-phase reflection at M5 close will reference whatever direction this section motivates.

**Are we on track for the project's stated goals?**

Yes — the CLAUDE.md "WHY" (auto-generate and keep current a Confluence wiki of service inventory, reducing manual effort and staleness) is materially delivered for the *capture* side. APIs, tests, framework/language, and external deps are all auto-derivable from code; per-source provenance is uniform across data types; the append-only retrofit (M3.5) means observations accumulate without overwriting human input. The dogfood proves the pipeline end-to-end: 11 fresh pom-source observations on atlas-intake, 41 test scenarios extracted from `main`, OpenAPI-source `apis` rows for every controller endpoint.

The plan's stated success criterion *"the interview has materially shrunk"* is unmet — interview still asks for the same ~17 fields. Code-sync writes new data in parallel; humans aren't asked less. M5's whole point is to close that gap. So: capture-side goal is met; *reduce-manual-effort* goal is half-met.

**Risks the work surfaced**

- **Renderer integration lag.** The single largest unrealized value from this phase is that `service_metadata` rows and pom-source `service_external_deps` rows aren't yet surfaced on Confluence pages. The renderer still reads `services.language` / `services.framework` only. Data is in the DB; users don't see it yet. This converts what feels like four shipped milestones (M1–M4) into roughly three-and-a-half from a user's perspective.
- **PomParser shallowness.** Atlas's own dogfood produced empty `framework_version` because atlas-intake's pom inherits from `com.atlas:atlas`, not `spring-boot-starter-parent` directly. Real production poms almost universally have parent chains. M4 captured the data the parser sees; in many real-world cases that's less than expected.
- **Stale intake rows.** The `/api/smoke/anthropic` row in dogfood (renamed to `/api/smoke/llm` per ADR-013, but never updated by intake) is the concrete instance of the staleness problem this whole project exists to fix. It's still in the DB. M5 step 4 addresses it; until M5 ships, the dogfood docs are demonstrably wrong about a thing the system is *supposed* to keep right.
- **Mid-flight schema rewrites.** M3.5 was a large retrofit driven by a clarification mid-phase. The retrofit worked cleanly (219 tests passed unchanged), and M4 was designed against the new model from day one — but the lesson is that "don't modify ingested data" is the kind of design intent that needs to surface during the planning conversation, not three milestones in. Worth re-checking M5's design with the same lens before committing.
- **Public-repo-only test fetching.** Production teams have private repos; the M3 test pipeline can't reach them without auth. DD-014 candidate; not blocking dogfood.

**Opportunities the work surfaced**

- **Append-only model is more powerful than designed.** It naturally supports observation-history queries ("when did we first see this dep?", "when did this endpoint disappear?"). Future features like drift dashboards, stale-doc alerts, or "what changed between two syncs" become trivial. The window-function live-view query is the primitive.
- **Code-sync architecture extends naturally.** Three writers landed (OpenAPI, tests, pom) without inventing new abstractions. A fourth (Dockerfile metadata, GitHub Actions config, README badges) fits the same shape: fetcher + parser + coordinator method + new `source` value.
- **The shift from "ask humans" to "read code" is qualitatively different from the original Phase 1–2 model.** The interview was the only writer; now it's one writer alongside three. The architecture quietly absorbed that change. Future phases can lean harder on this: scheduled cron, CI integration, drift detection.

**Invalidated assumptions**

- *"Code-sync will split out as `atlas-code-sync`"* — decided at M3 to stay folded. The package boundary is clean; production team can lift it later. Plan-stated decision point honored; assumption replaced with a deliberate-stay choice.
- *"Skip-on-collision is the long-term policy"* — M1 reflection already flagged this would flip at M5. By M5, intake stops asking about APIs, openapi becomes the sole source, and the skip becomes dead code. Confirmed.
- *"`services.language` / `services.framework` are the canonical home for those facts"* — M4 created a parallel `service_metadata` table. The entity columns are now legacy. M5 is the natural place to migrate intake writes and deprecate.
- *"M5 is one milestone"* — looking at it now, M5 bundles renderer integration + interview slimming + stale-row cleanup + phase reflection. Each of those is a distinct shippable unit. See "What's next" below.

**What's next — three options**

**(A) M5 as planned.** Single milestone, four sub-deliverables (renderer integration, interview slimming, stale-row cleanup, phase-reflection close). Coherent end-of-phase shape. Risk: bundles the highest-impact piece (renderer integration) with the largest piece (interview-stage refactor), so any friction in one delays the other.

**(B) Split M5 into M4.5 + M5.** M4.5 = renderer integration + stale-row cleanup (small, user-visible: pom data appears on pages, dogfood stops being wrong). M5 = interview slimming + phase-reflection close. Smaller review boundaries, faster visible feedback, the highest-impact piece ships standalone. Cost: two commits where one would do; phase-reflection close drifts later by however long the interview-slimming work takes.

**(C) Pivot to Phase 6 (demo + handoff) or to a new phase (AI-narrated walkthroughs / production-readiness DDs).** Phase 6 is roadmap-next; the layer-3 AI-narration work was deferred from the original strategic conversation. Cost: leaves the code-driven-docs phase in a half-done state — interview not shrunk, dogfood still showing stale rows, pom data not on pages. Probably wrong — finish the phase before pivoting.

**Recommendation**

Option **(B) — split M5**. The renderer integration is the highest-impact unrealized work from this phase and shouldn't sit behind interview-stage refactoring. Splitting also gives a cleaner boundary for the user to validate "is the dogfood now showing what code says?" before committing to interview-shape changes that touch every intake conversation.

After Phase close (whether via A or B), the next decision is **Phase 6 (demo + handoff per the roadmap) vs. a new phase tackling AI-narrated walkthroughs or production-readiness DDs (DD-001 auth, DD-003 CI, AWS provisioning).** That's the right question for the *end-of-phase* reflection, not now. Logging it here so the next session inherits the framing.

### M4.5 — Renderer integration + stale-intake-row cleanup

**What's working**

- **Append-only model paid off the third time too.** The renderer's read paths needed live-view filtering on `service_external_deps` (latest-per-`(service_id, ext_dep_id, source)` where `presence='present'`) — the same window-function shape that already works for `apis`, `service_test_scenarios`, and `service_metadata`. The new `findExternalDependenciesFor` query is structurally identical to the existing live-views; readers can pattern-match between them. Found and silently fixed a pre-existing bug — the old `findExternalDependenciesFor` ignored `presence` and would have surfaced tombstones once any disappeared.
- **Red-first discipline held throughout.** Every behavior added in M4.5 had a failing test before its impl line existed: 6 new repository tests (live-view tombstone exclusion, multi-observation collapse, four stale-intake-apis cases), 8 new renderer tests (technical-details from metadata with fallback + override; external-deps composition with intake-only / pom-only / matching / non-matching cases), 4 new coordinator tests. 18 net new tests, all written before the corresponding code. The M2 discipline reset has now scaled across four milestones (M2 → M2.5 → M3 → M4.5 — M4 was the regression).
- **Conservative matching heuristic is robust enough to ship.** The artifactId-token rule (split by `-`, length ≥ 4, case-insensitive substring of intake name) correctly merged `com.anthropic:anthropic-java` into the intake row "Anthropic API" without producing false positives against `Stripe` ↔ `com.fasterxml.jackson.core:jackson-databind`. Token length floor of 4 is the key safety: a more naïve "any substring" would match "api" against everything.
- **Plain-text source suffix beats a styled badge.** The "(from pom.xml)" suffix is rendered with a thin `<em>` and no other styling. Reads cleanly against the existing thin-note prose; doesn't introduce new visual vocabulary.
- **Opt-in stale cleanup respects the M1 contract.** The new `tombstoneStaleIntakeApis` runs only when explicitly invoked (REST endpoint or coordinator call). The auto-fired `refreshOpenApi` still defers entirely to intake-source rows on collision. Production teams that aren't ready for code to overrule humans can simply not call the new endpoint; teams that want truth-fix have the path.

**What's not — and what to do about it**

- **Test counts hit 254 but fixture sprawl is real.** `ServicePageRendererTest`'s context-construction helpers (`fullContext`, `minimalContext`, `emptyContextFor`, plus M4.5's `contextWith`, `contextWithDeps`) now span five different shapes. Adding a tenth field to `ServicePageContext` will mean another round of "update every fixture call site." Worth thinking about whether `ServicePageContext` should expose a builder before M5 adds intake-written `service_metadata` and likely another field. Capture as M5 polish, not blocking.
- **Confluence "✓" character is in the rendered output.** It's a Unicode codepoint that Confluence storage format passes through cleanly, and it doesn't trigger the existing escape rules. Looks fine in dogfood verification expected; if it ever renders weirdly, swap for plain text "matches pom: ...".
- **Live-view query for external-deps double-runs the window function once for the rendered list and again inside `findLiveExternalDepsForService` (used by code-sync's diff path).** Two CTEs over the same table for slightly different shapes. Acceptable at prototype scale; if performance ever shows up, consolidate into a single CTE shared by both methods.
- **The `otherRows` branch in `renderExternalDependencies` is defensive against future sources** (e.g., openapi or tests-source external deps) that don't exist today. Could argue YAGNI; left in because the cost is one short loop and the future-extension cost would be wading back through this logic to add it later. A genuine over-engineering moment that I left in deliberately — re-evaluate if it stays unused after M5.
- **Pre-existing `findExternalDependenciesFor` bug.** Before M4.5, the renderer would have surfaced tombstoned external-dep observations after M4 because the query didn't filter on `presence`. The dogfood didn't trip this because no external-dep tombstones existed yet. Worth tagging as a near-miss: M4 introduced `presence` columns but didn't re-audit every read path. Future schema-shape changes should explicitly enumerate readers and verify each one was updated.

**Resumable summary** *(propagated to `docs/handoff/current-state.md`)*

Branch `main` at commit pending — this commit lands M4.5 of the code-driven-documentation plan. 254 tests pass across four modules (atlas-domain 39, atlas-intake 95, atlas-mcp 24, atlas-confluence-sync 96), up from 236 after M4. **No new migrations** — M4.5 reuses the schema landed in M3.5/M4. `findExternalDependenciesFor` rewritten to a window-function live-view that returns one row per `(service_id, ext_dep_id, source)` and exposes `source` on `ExternalDependencyUsage`. New `findStaleIntakeApis` repository method (intake-source live observations with no openapi-source counterpart). `ServicePageContext` gained a `List<ServiceMetadata>` field; `ServicePageRenderer.renderTechnicalDetails` now reads from it (with `pom-xml` precedence and entity-column fallback) and renders a `(from pom.xml)` suffix; new `Language Version` / `Framework Version` / `Build Tool` lines surface when observations carry them. External-Dependencies section composes intake-source rows (with descriptions) and pom-source rows (with `groupId:artifactId` coordinates), collapsing pom rows that match an intake row by artifactId-token substring (case-insensitive, length ≥ 4) into a "✓ matches pom: ..." annotation on the intake row. New `CodeSyncCoordinator.tombstoneStaleIntakeApis(serviceId)` method writes `presence='absent'` tombstones (preserving `source='intake'`) for intake-source api observations whose `(method, path)` has no live openapi counterpart; audit row `changed_by='code-sync-stale-intake-cleanup'`. New REST endpoint `POST /api/code-sync/tombstone-stale-intake-apis/{serviceId}` — opt-in per service; not auto-fired during `refreshOpenApi`. Live-verify against the dogfood deferred to user discretion (one suggested run: refresh-pom → refresh → tombstone-stale-intake-apis on atlas-intake, then sync to Confluence; expected: language/framework with "(from pom.xml)" suffix, External Dependencies showing 2 intake + N pom rows with composition, `/api/smoke/anthropic` gone from APIs section). Next milestone: M5 — interview shrinkage + phase close. Open carry-overs unchanged from M4, plus: `ServicePageContext` builder candidate (fixture sprawl); `findExternalDependenciesFor` and `findLiveExternalDepsForService` could share a CTE; `otherRows` defensive branch in renderExternalDependencies should be re-evaluated for YAGNI removal after M5.
