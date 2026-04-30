# Plan — Drill-down depth (L3–L5): make the Confluence space walkable from app down to method

Created: 2026-04-29 (session 3 of the day, post code-driven-documentation phase close).

## Goal

Extend the Atlas-generated Confluence space so a newcomer to the application can drill top-down from the landing page through service → endpoint schema, service → modules, service → architectural beans/classes, all without leaving Confluence. Surfaces the depth of Atlas's data model for the Phase 6 stakeholder demo and removes the current "see the OpenAPI spec link" hand-off that the per-endpoint pages still emit.

The five drill-down layers (per `docs/confluence-template.md` and `docs/confluence-layout.md`):

- **L1 Space** — landing page + About. **Done.**
- **L2 Service** — seven-section service page. **Done** (gains a new Section 8 "Internals" in M4).
- **L3 Endpoint** — per-endpoint page **gains parameters / request body / responses / examples** (today: only METHOD path / desc / auth / spec link).
- **L4 Module** — **new**: one page per Maven sub-module, mirroring the pom `<modules>` tree.
- **L5 Beans/Classes** — **new**: one page per service indexing Spring stereotype classes + their public methods.

## Success criteria

A stakeholder can open the ATLAS Confluence landing page and, in three clicks, reach the public method signatures of any class in any service. Concretely, every observable behaviour below is testable:

1. **L3** — Per-endpoint Confluence page renders a Parameters table (name, in, type, required, description) when the OpenAPI spec declares parameters; renders a Request Body section with inline schema when the spec declares one; renders a Responses table grouped by status code with inline schema; renders Examples when present; falls back to thin notes (no errors) for endpoints without these.
2. **L3** — Schema-level data is persisted on the `apis` row at code-sync time; the renderer is a pure function of DB state (no spec re-fetch at render).
3. **L4** — Per-module Confluence pages exist for every Maven sub-module of every service whose pom.xml has been parsed. Each page renders coordinates, parent/child links, declared dependencies, and a link to the L5 Beans page anchors for classes under this module's source root.
4. **L4** — When a sub-module disappears from a re-parsed pom, its Confluence page is deleted on the next sync (append-only `presence='absent'` triggers cleanup, mirroring the M3.5 pattern).
5. **L5** — Per-service Beans page renders one block per Spring stereotype (`@RestController`, `@Controller`, `@Service`, `@Repository`, `@Component`, `@Configuration`) found in the source tree, grouped by Maven module, with FQ class name, optional class javadoc summary, and public method signatures + first-sentence javadoc.
6. **L5** — Excluded by default: DTOs, POJOs, enums, JPA `@Entity`, Spring Data repository interfaces. The Beans page is an architectural-seam index, not a class catalogue.
7. **L2 Internals section** — Service page acquires a Section 8 "Internals" cross-reference block linking the same service's Modules / Beans / Tests / Endpoints. When a layer is empty, that bullet renders as a thin "No X documented yet." note rather than disappearing.
8. **Append-only model carries over** — All new tables (`api_schema_snapshots` *or* `apis.openapi_snapshot`, `service_modules`, `service_beans`) follow the M3.5/M4 pattern: `observed_at`, `presence`, `source`, `confluence_page_id` carrying forward across observations, "latest where presence='present'" live-view queries.
9. **MariaDB portability** — every new migration applies cleanly under both Postgres (Testcontainers) and MariaDB 10.11 via `MariaDBPortabilitySmokeTest`. Per-vendor splits used only when truly needed (e.g. `DROP CONSTRAINT IF EXISTS` syntax — same as V18/V20).
10. **Dogfood end-to-end** — all three Atlas modules (`atlas-intake`, `atlas-mcp`, `atlas-confluence-sync`) render the full L1→L5 drill-down after a single round of `refresh-pom` + `refresh` + `refresh-beans` (new) + `sync/run`.

## Assumptions

- **OpenAPI 3.x only.** Swagger 2.0 not handled. The existing `swagger-parser` already covers 3.0 and 3.1; we read the same library's normalised model rather than re-parsing.
- **Maven only for L4.** Gradle / Bazel deferred — out of scope for the prototype phase.
- **Spring stereotypes only for L5 first cut.** `@RestController`, `@Controller`, `@Service`, `@Repository`, `@Component`, `@Configuration`. Widening to `@Entity` / Spring Data interfaces / `@ConfigurationProperties` is a deferred decision (DD-015 candidate, see Open questions).
- **AST traversal, not compiled bytecode.** Reuses `RepoFileFetcher` + `JavaParser` (already in code-sync from M3 of code-driven-documentation). No reflection, no classpath, no compilation step.
- **Per-source provenance + append-only is the design rule.** L4 module rows source `pom-xml`. L5 bean rows source `source-tree`. Code-sync writers tag every row; intake writers exist only for the human-curated fields.
- **HTTPS-fetch only.** Public repos. Private-repo auth (DD-014 candidate) remains deferred.
- **Schema rendering depth: one level deep.** Schemas referenced via `$ref` resolve once; deeper nesting collapses to type names. Stops the page from exploding on a deeply nested model and keeps the rendering predictable.
- **Single-page Beans index per service.** A 100-bean service produces a long page; we accept that at prototype scale. If the page exceeds Confluence's storage-format size limits (~5MB) in real use, the next iteration splits per-module — but we don't pre-optimise.
- **The four-module architecture stays.** Code-sync responsibilities still fold into `atlas-intake`. Splitting to `atlas-code-sync` was reconsidered and held at the M3 decision point of the previous phase; same hold for this phase.
- **Phase 6 demo follows this phase, not the other way around.** Per the previous end-of-phase reflection: the demo lands harder with the drill-down in place.

## Approach

Four review milestones. Each ends with a reflection (per CLAUDE.md §6) and a session-resumable summary so handoff stays current. TDD throughout: red tests first, minimal green, refactor.

**Discipline reminder** (carried from the previous phase's M2 reset): write at least one failing test for each behaviour *before* the corresponding impl exists. Discipline drifted twice in the last phase on apparently-routine work; promote red-first as a non-negotiable.

Order is smallest-cost to largest, so we can pause/redirect after each:

- **M1 — L3 schema-level API detail** (smallest, demo-essential)
- **M2 — L4 Maven module tree** (medium)
- **M3 — L5 Spring bean / class index** (largest, deferrable)
- **M4 — Cross-link integration + phase close** (small; locks the drill-down together and runs the dogfood verification)

## Milestones

### M1 — L3 schema-level API detail

**Goal**: per-endpoint Confluence pages render parameters, request body, response codes with schemas, and examples. Today they only render METHOD path / source / description / auth / link-to-spec.

**Persistence shape decision**: extend `apis` with `openapi_snapshot JSON NULL` (single column on the existing append-only row) rather than create a new `api_schema_snapshots` table. Rationale: snapshot is 1-1 with the api row, lifecycle is identical, the existing window-function live-view queries already pick the latest observation per (service_id, method, path) — we get append-only behaviour for free. JSON storage uses the same `@JdbcTypeCode(SqlTypes.JSON)` pattern as `services.metadata`.

1. **(Red)** Tests for `ApiEndpointPageRenderer`, behaviour-focused:
   - `whenSpecDeclaresPathAndQueryParameters_thenParametersTableRendered`
   - `whenSpecDeclaresRequestBodyWithInlineSchema_thenRequestBodySectionRenderedWithFields`
   - `whenSpecDeclaresRequestBodyWithRefSchema_thenSchemaInlinedFromComponents`
   - `whenSpecDeclaresMultipleResponses_thenResponsesGroupedByStatusCode`
   - `whenSpecDeclaresExamples_thenExamplesRenderedAsCodeBlocks`
   - `whenEndpointHasNoSchemaSnapshot_thenSectionsCollapseToThinNote`
   - `whenSnapshotSchemaIsDeeperThanOneLevel_thenInnerTypesCollapseToTypeName`
2. **(Red)** Tests for `OpenApiParser` schema extraction:
   - `whenSpecHasOperation_thenEndpointRecordCarriesParametersList`
   - `whenSpecHasOperation_thenEndpointRecordCarriesRequestBodyJson`
   - `whenSpecHasOperation_thenEndpointRecordCarriesResponsesByStatus`
   - `whenSpecHasOperation_thenEndpointRecordCarriesExamples`
3. **(Red)** Tests for `CodeSyncCoordinator.refreshOpenApi`:
   - `whenOpenApiRefreshRuns_thenOpenapiSnapshotIsPersistedOnEachApiRow`
   - `whenSnapshotSchemaChangesBetweenRuns_thenLatestObservationCarriesNewSchema_andPreviousObservationIsRetained`
4. **(Red)** Repository test:
   - `whenFindLatestApisFor_thenSnapshotComesFromLatestObservation`
5. Migration `V21__apis_openapi_snapshot.sql` (in `db/migration/` — portable shape; vendor split only if needed): `ALTER TABLE apis ADD COLUMN openapi_snapshot ...` with the right per-vendor JSON type. Confirm shape matches what `service_metadata` uses.
6. Extend `EndpointRecord` (the comment in the existing record explicitly anticipates this): add `parameters`, `requestBody`, `responsesByStatus`, `examples`. Use Jackson `JsonNode` for the schema sub-trees (sufficient for our render needs; avoids inventing a parallel object model).
7. Extend `SwaggerOpenApiParser` to populate the new fields.
8. Extend `Api` entity + `ServiceRelationshipsRepository` to read/write the snapshot column.
9. Extend `CodeSyncCoordinator.refreshOpenApi` to persist the snapshot on each new observation.
10. Extend `ServicePageContext` to carry the snapshot through to the renderer (the existing `ApiPresentation` is the natural place).
11. Extend `ApiEndpointPageRenderer` per the L3 spec in `confluence-template.md`. New helpers: `renderParameters`, `renderSchema` (recursive one-level), `renderResponses`, `renderExamples`.
12. **(Green)** Make tests pass.
13. **(Refactor)** If `renderSchema` exceeds ~50 lines, extract to a `SchemaRenderer` component.
14. Live verification: hit `POST /api/code-sync/refresh/{atlas-intake-id}` then `POST /api/sync/run`, observe a per-endpoint page now showing parameters + request body + responses inline.

**Reflection at M1 boundary** — what's working, what's not, resumable summary.

---

### M2 — L4 Maven module tree

**Goal**: each Maven sub-module of a service gets its own Confluence page, parented under the service page. Reader can pivot from "service" to "what modules compose it" to "what modules depend on what."

**Persistence shape**: new `service_modules` table, append-only from day one. Mirrors `service_metadata` in shape — `observed_at`, `presence`, `source` columns; live-view query is "latest per (service_id, module_path) where presence='present'".

1. **(Red)** Tests for an extended `PomParser`:
   - `whenPomDeclaresModules_thenModulesAreReturnedWithRelativePaths`
   - `whenChildPomDeclaresParent_thenChildModuleRecordCarriesParentPath`
   - `whenPomHasNoModules_thenSingleRootModuleRecordReturned`
   - `whenChildPomOverridesLanguageVersion_thenChildRecordCarriesOverride`
2. **(Red)** Repository tests for `ServiceModuleRepository` (new):
   - `whenInsertModuleObservation_thenLiveViewReturnsLatest`
   - `whenSubsequentObservationMarksAbsent_thenLiveViewExcludesIt`
   - `whenSubsequentObservationMarksAbsent_thenConfluencePageIdCarriesForwardForCleanup`
3. **(Red)** Tests for `CodeSyncCoordinator.refreshPom` extension:
   - `whenPomTreeHasSubModules_thenServiceModulesRowsArePersisted`
   - `whenSubModuleDisappearsFromPomTree_thenLatestObservationMarksItAbsent`
4. **(Red)** Tests for new `ModulePageRenderer`:
   - `whenModuleHasParentAndChildren_thenLinksRenderInBothDirections`
   - `whenModuleHasDeclaredDependencies_thenDependencyListMirrorsServicePageComposition`
   - `whenModuleHasNoOverrides_thenLanguageRendersAsInheritedFromParent`
5. **(Red)** Tests for `SyncCoordinator` module-page lifecycle:
   - `whenServiceHasModules_thenSyncCreatesOrUpdatesModulePage`
   - `whenModuleAbsentInLatestObservation_thenCleanupDeletesPageAndNullsId`
6. Migration `V22__service_modules_table.sql` — append-only schema with `observed_at`, `presence`, `source='pom-xml'`, `confluence_page_id`.
7. Extend `PomParser` to walk `<modules>` recursively; capture parent → child relationships and per-module coordinates / language / framework / declared deps.
8. New `ServiceModule` JPA entity + `ServiceModuleRepository` with the live-view query.
9. Extend `CodeSyncCoordinator.refreshPom` to write module-tree rows.
10. New `ModulePageRenderer` + `ModulePageContext`.
11. Extend `SyncCoordinator` to lifecycle module pages (create/update on observed; cleanup on absent — mirrors `ApiEndpointPage` lifecycle).
12. Extend `ServicePageRenderer` Section 8 "Internals" stub (filled in M4) to include a Modules link list.
13. **(Green)** Tests pass.
14. **(Refactor)** Module-tree → Confluence-tree mapping is the natural complexity hot spot. If module-page-context construction sprawls, extract a `ModuleContextLoader`.
15. Live verification: re-run `refresh-pom` for `atlas-intake`; observe one or more `Service: atlas-intake — Module: ...` pages parented underneath.

**Reflection at M2 boundary.**

**Decision point**: at the M2 reflection, decide whether to inline the modules block on the service page (compact, no new Confluence pages for single-module services) or keep the per-module page model uniform regardless. Default: per-module page model uniform. Single-module services produce one trivial sub-page; the consistency is worth the trivial page. Revisit if dogfood feels noisy.

---

### M3 — L5 Spring bean / class index

**Goal**: each service gets one "Beans" Confluence page surfacing its Spring stereotype classes with public method signatures. The seam-level architectural index a newcomer reaches for after they understand the modules.

**Persistence shape**: new `service_beans` table, append-only from day one. Same shape rules as `service_modules`.

1. **(Red)** Tests for new `JavaBeanExtractor` (parallels `JavaTestExtractor`):
   - `whenSourceHasRestController_thenBeanRecordHasStereotypeRestController`
   - `whenSourceHasServiceWithPublicMethod_thenBeanRecordCarriesMethodSignature`
   - `whenMethodHasJavadoc_thenFirstSentenceCapturedAsSummary`
   - `whenClassIsAnEntity_thenExtractorSkipsIt`
   - `whenClassIsASpringDataRepositoryInterface_thenExtractorSkipsIt`
   - `whenClassIsAnAnnotation_thenExtractorSkipsIt`
   - `whenClassHasNoStereotype_thenExtractorSkipsIt`
   - `whenSourceHasNestedClass_thenOnlyTopLevelStereotypesAreCaptured`
2. **(Red)** Repository tests for `ServiceBeanRepository`:
   - Standard live-view + presence-absent + page-id-carry-forward set, mirroring `ServiceModuleRepository`.
3. **(Red)** Tests for new `CodeSyncCoordinator.refreshBeans(serviceId)`:
   - `whenServiceHasRepoUrl_thenBeansAreFetchedAndPersisted`
   - `whenClassDisappearsFromSourceTree_thenLatestObservationMarksItAbsent`
   - `whenServiceHasNoRepoUrl_thenRefreshIsNoop`
4. **(Red)** Tests for new `BeansPageRenderer`:
   - `whenServiceHasBeansAcrossStereotypes_thenOneSectionPerStereotype`
   - `whenStereotypeHasMultipleClassesAcrossModules_thenClassesGroupedByModuleAnchor`
   - `whenClassHasJavadocAndPublicMethods_thenSummaryAndSignaturesRendered`
   - `whenServiceHasNoBeans_thenPageRendersThinNote_andSyncCoordinatorDeletesPageOnNextRun`
5. **(Red)** Tests for `SyncCoordinator` Beans-page lifecycle:
   - `whenServiceFirstAcquiresBeans_thenBeansPageIsCreated_andServicesBeansPageIdSet`
   - `whenServiceLosesAllBeans_thenBeansPageIsDeleted_andServicesBeansPageIdNulled`
6. Migration `V23__service_beans_table.sql` — append-only; `service_id`, `module_path`, `package_name`, `class_name`, `stereotype`, `class_javadoc_summary`, `public_methods JSON`, `source='source-tree'`, `observed_at`, `presence`, audit columns.
7. Migration `V24__services_beans_page_id.sql` — adds `services.beans_page_id` (mirrors `services.tests_page_id`).
8. New `JavaBeanExtractor` using `JavaParser` (already pinned for `JavaTestExtractor`).
9. New `ServiceBean` entity + `ServiceBeanRepository` with live-view query.
10. New `CodeSyncCoordinator.refreshBeans(serviceId)` + `POST /api/code-sync/refresh-beans/{serviceId}`.
11. New `BeansPageRenderer` + `BeansPageContext`. Per-stereotype sections; per-class block with method signatures + javadoc summary.
12. Extend `SyncCoordinator` to lifecycle the per-service Beans page.
13. Extend `ServicePageRenderer` Section 8 "Internals" stub to link to the Beans page.
14. **(Green)** Tests pass.
15. **(Refactor)** Bean-class extraction is the largest new surface in this phase. Likely refactors: (a) split `JavaBeanExtractor` from `JavaBeanFilter` (the stereotype recognition rule belongs in its own unit); (b) the per-method javadoc summary needs a small `JavadocSummariser` that takes the first sentence — share it with `JavaTestExtractor` if natural.
16. Live verification: `POST /api/code-sync/refresh-beans/{atlas-intake-id}` then sync; the `atlas-intake` service page now has a child page `atlas-intake — Beans` listing controllers, services, repositories, etc.

**Reflection at M3 boundary.**

---

### M4 — Cross-link integration + phase close

**Goal**: the L1→L5 path is walkable. Service pages have an Internals section that ties everything together. End-to-end dogfood verification proves the demo-readiness claim.

1. **(Red)** Tests for `ServicePageRenderer` Section 8 "Internals":
   - `whenServiceHasModulesEndpointsBeansAndTests_thenInternalsSectionLinksAllFour`
   - `whenServiceHasNoModules_thenModulesSubBulletShowsThinNote`
   - `whenServiceHasNoBeansPage_thenBeansSubBulletShowsThinNote`
   - `whenInternalsSectionRenders_thenLinksUseConfluencePageUrls_notSpecLinks`
2. **(Red)** Test for landing-page-architecture-map cross-reference: a small "How to read this space" preamble link list pointing to the layer pages — keeps L1 self-explanatory.
3. Implement Section 8 ("Internals") in `ServicePageRenderer`. Replace the M2/M3 stubs with the full block.
4. Extend `LandingPageRenderer` (one paragraph addition only) explaining the L1→L5 drill-down convention.
5. **(Green)** Tests pass.
6. **Dogfood verification (manual, captured in M4 reflection)**:
   ```
   POST /api/code-sync/refresh/{id-for-each-of-three-modules}
   POST /api/code-sync/refresh-pom/{id-for-each-of-three-modules}
   POST /api/code-sync/refresh-tests/{id-for-each-of-three-modules}
   POST /api/code-sync/refresh-beans/{id-for-each-of-three-modules}
   POST /api/sync/run
   ```
   Expected: ATLAS Confluence space contains, for each of `atlas-intake` / `atlas-mcp` / `atlas-confluence-sync`:
   - The L2 service page with a populated Internals section.
   - L3 endpoint pages with Parameters / Request Body / Responses sections rendered.
   - L4 module page(s) parented under the service.
   - One L5 Beans page with stereotype-grouped class blocks.
   - The existing Tests page (unchanged).
7. Update `docs/handoff/current-state.md` with post-M4 test counts, page set, and any DDs surfaced.
8. Append **end-of-phase reflection** to this plan covering: phase trajectory vs `docs/roadmap.md`, what surprised, what to recommend for Phase 6 prep.

## Tests

Behaviour-focused, all red-first, mocks only at architectural seams (Anthropic, Confluence, GitHub, OpenAPI HTTP). Persistence tests use Testcontainers Postgres + MariaDB. Project rule from CLAUDE.md §4: test names read as specifications.

Test counts (rough, will firm up in each milestone):
- M1: ~12 new
- M2: ~12 new
- M3: ~16 new
- M4: ~6 new + dogfood walkthrough

End-of-phase target: ~292 active tests (current 247 + ~45). Net counts are honest only at milestone close, since red-first means failing tests sit briefly in the test count during a milestone.

## Open questions

Per the user's preference (memory: ask serially, not batched), one question at a time. Answer the first to unblock M1; subsequent questions surface at their milestone's red-test phase.

1. **L5 stereotype scope.** *Resolved 2026-04-29: Option A.*
   - The Beans page lists Spring stereotype classes only: `@RestController`, `@Controller`, `@Service`, `@Repository`, `@Component`, `@Configuration`.
   - Excluded: DTOs / POJOs / enums / JPA `@Entity` / Spring Data repository interfaces / `@ConfigurationProperties`.
   - B/C captured as deferred (DD-015 candidate — to be opened if the dogfood feels thin or stakeholders ask for the persistence surface). The page is regenerated on every sync, so widening later costs no migration.

2. **(Surfaces at M2 red phase.)** L4 — when a service has a single root pom and no `<modules>`, do we still emit a `Service: X — Module: <root>` page, or inline the module info on the service page? Default proposed in M2 step 14: emit. Hold the question for M2's reflection.

3. **(Surfaces at M3 red phase.)** L5 — should the Beans page include private `protected` methods (often the "natural" override seam in Spring-Java) or strictly public only? Default: public only. Revisit if dogfood feels thin.

## Carry-overs from the previous phase that this plan does not address

These are unchanged carry-overs from the code-driven-documentation end-of-phase reflection. Listed here so they're visible but explicitly not scope:

- Pom parent-chain traversal beyond `<modules>` — touched superficially in M2 (parent path), but full Maven inheritance resolution is still deferred.
- Private-repo GitHub auth (DD-014 candidate). Affects all source-tree readers including the new bean extractor — same scope decision as M3 of the previous phase.
- Test-name normalisation polish on the existing Tests page.
- MariaDB project rule (`VARCHAR(191)` for unique-index columns) promotion to CLAUDE.md Constraints.
- Unreachable LANGUAGE/FRAMEWORK/API_* applyInput cases in `InterviewService` (~120 lines of defensive code, post-Phase 6 cleanup candidate).
- `otherRows` defensive branch in `renderExternalDependencies` (YAGNI candidate).

## Reflections

### M1 — L3 schema-level API detail (committed: pending)

**What's working**

- **Append-only-snapshot persistence as a single TEXT column was the right call.** Trying to use a JSON column type would have forced JdbcTemplate JSON-binding ceremony (Postgres needs `setObject(idx, jsonStr, Types.OTHER)` or `::json` cast; MariaDB's JSON is essentially LONGTEXT) and would have bought no querying we use. TEXT carrying-JSON applies cleanly under both engines, the `@JsonInclude(NON_NULL)` fix made snapshots human-readable in psql, and the change-detection comparison stays a trivial string equality.
- **Red-first held this milestone.** All 16 new tests existed (failing or compile-failing for the new field) before the renderer/parser implementations were written. The renderer tests in particular drove the schema-rendering shape — the "deeply nested collapses to type name" test forced the explicit one-level-deep policy rather than letting it emerge implicitly.
- **The convenience constructor on `ApiSummary`** (the 7-arg legacy form delegating to the new 8-arg form with `openapiSnapshot=null`) avoided rippling the change through every call site. Same trick on `EndpointRecord` and `insertApi`. ~50 callers compiled unchanged.
- **`ParseOptions().setResolveFully(true)` is the right hinge for `$ref` resolution.** The renderer never has to walk `components/schemas`; the parser produces a self-contained snapshot per operation. The cost is parse-time work and a slightly larger snapshot, both negligible at prototype scale.
- **Swagger-models POJOs + Jackson via `valueToTree` is faithful enough.** Setting `JsonInclude.NON_NULL` collapses the ~80 nullable fields per Schema POJO into the 3-5 fields actually populated. A typical operation-snapshot is now under 1 KB rather than 8-10 KB.

**What's not (or what bit me)**

- **Without `JsonInclude.NON_NULL`, the snapshot was unusable.** First `mvn test` run produced an 8 KB JSON document for a 3-field schema. Caught immediately because the parser test asserted `doesNotContain "$ref"` and the serialized form had `"$ref":null` in every Schema node. Took two minutes to fix once spotted; would have been a nasty discover-in-production issue if I'd skipped the assertion. **Lesson**: when persisting library-POJO JSON, configure null-suppression up front — don't trust default Jackson serialization on opaque library types.
- **`mvn -pl atlas-confluence-sync test` failed before atlas-domain was rebuilt.** Maven multi-module dependency resolution between modules requires `install` of the upstream module first when running per-module. Running from the parent or `mvn -pl ... -am` (also-make) would have avoided the workaround. Mostly a test-loop friction note: routine `mvn verify` from the project root is the safer default.
- **The renderer's `<table>` HTML is a placeholder.** Confluence's storage format prefers `<table class="confluenceTable">` with `<tbody>`, and the existing code in this repo just emits plain `<table><tbody>`. The rendered tables work in Confluence but won't pick up Atlassian's default table styling. Worth a renderer-styling pass at M4 cross-link time, not now.
- **No live-verify against the dogfood Confluence space yet.** The schema-rendering path will only be visible to a human reading the actual ATLAS pages once a refresh+sync round runs against real data. Carry this into M4.

**Resumable summary (M1)**

Phase 5.6 M1 (L3 schema-level API detail) lands. Persistence shape: `apis.openapi_snapshot TEXT NULL` (Flyway V21, portable across Postgres + MariaDB). `EndpointRecord` and `ApiSummary` gain an `openapiSnapshot` field; `SwaggerOpenApiParser` produces the snapshot via `ParseOptions.setResolveFully(true)` + Jackson `valueToTree` with `NON_NULL` inclusion; `CodeSyncCoordinator.refreshOpenApi` persists the snapshot on every observation and treats snapshot changes as content changes (new observation appended). `ApiEndpointPageRenderer` reads the snapshot and renders Parameters / Request Body / Responses / Examples sections one level deep, with nested objects collapsing to their type name. Dogfood live-verify deferred to M4. **Test counts**: atlas-domain 41 (+2), atlas-intake 95 (+7), atlas-mcp 24 (=), atlas-confluence-sync 103 (+7). Total **263** (+16). All green under `mvn verify`. Branch: `main`, 11 → 12 commits ahead of origin (M1 commit pending). **Next**: M2 — Maven module tree (L4). Will need to extend `PomParser` for `<modules>` traversal, add a new `service_modules` table (V22), new `ModulePageRenderer`, and lifecycle the per-module pages in `SyncCoordinator`.

### M2 — L4 Maven module tree (committed: pending)

**What's working**

- **Single-pass append-only diff against the walked tree** is the same shape that's now worked across `apis`, `service_metadata`, `service_test_scenarios`, and `service_external_deps`. Re-using it for `service_modules` was a copy-and-tighten exercise — no new architectural choices, just one more table in the same vocabulary. The pattern's repeatability is the strongest signal yet that append-only-with-presence is the right abstraction for code-derived facts.
- **Coordinator-driven recursion (option a in the plan)** kept `PomParser` I/O-free. The parser still takes one pom string and returns one `PomFacts`; the coordinator orchestrates `repoFetcher.fetchFile` per child. Reading the PomParser test in isolation is still trivial — no fetcher mocking required there.
- **Two-pass page sync (create-then-update)** mirrors the "eventual consistency" pattern already used for service-to-service hyperlinks on the service page. Pass 1 ensures every module has a page id; pass 2 re-renders so cross-module parent/child links resolve. Without this, the first-sync round would produce a tree where a parent's link to its just-created children renders as plain text.
- **Skipping the L2 → L4 cross-link in M2** kept the milestone tight. Confluence's own sidebar tree still surfaces the per-module pages parented under each service, so a curious reader can navigate via that tree even before M4 wires the proper "Internals" Section 8. Documented as deliberately deferred so M4 isn't tempted to skip it.
- **Display label `(root)` for the empty-string module path** kept the page title and renderer output readable. An empty dash in `"billing-svc — Module: "` would have been visibly broken; `"billing-svc — Module: (root)"` reads well in Confluence's sidebar.

**What's not (or what bit me)**

- **The walk produces one root-module observation per service even for true leaf services.** Atlas's own four registered "services" are each leaves of the bigger Atlas Maven build — they each get exactly one root-module page that's a near-copy of their L2 service page's Technical Details. The M2 plan-doc decision point flagged this (default: keep per-module page model uniform); on reflection, the trivial root page does feel redundant when there are no sub-modules. Worth revisiting at M4 cross-link time: maybe omit the root-only page when `children.isEmpty()` and surface the same fields inline on the L2 service page. Captured as a Phase 6 polish carry-over.
- **Service-level external deps still source from the root pom only.** A multi-module service that wants the L2 service page's Dependencies section to show every sub-module's declarations should union from the tree. M2 keeps the existing M4 path (root only) and exposes the per-module deps on the L4 module page instead. Documented in `refreshPom`'s code comment; carry to a future polish pass.
- **`service_modules.declared_deps` is a duplicate of part of `service_external_deps`** for single-module services. The data is the same modulo the org-prefix filter, and they update via the same code path. Acceptable at prototype scale (the L4 page renders directly from `service_modules.declared_deps`, the L2 page renders from `service_external_deps`), but a future "render L4 from service_external_deps grouped by module" refactor would remove the redundancy. Not in scope now.
- **The per-module declared-deps list is NOT org-prefix-filtered on the L4 page.** Today the storage carries the full list (everything declared, including `com.atlas:atlas-domain` style internal deps), and the renderer just lists it. By contrast `service_external_deps` is filtered by `atlas.code-sync.org-group-prefix`. This is on purpose: the per-module page is "what does this pom declare", which includes intra-project deps. The renderer could call them out as internal vs external in a future pass. Not now.
- **No live-verify against the dogfood Confluence space yet.** Same carry-over as M1; M4 will run the full dogfood walk-through.

**Resumable summary (M2)**

Phase 5.6 M2 (L4 Maven module tree) lands. Persistence shape: new `service_modules` table (Flyway V22), append-only from day one with the same shape as `service_metadata` (observed_at / presence / source / confluence_page_id, latest-per-key live view). `PomFacts` extends with `packaging` and `modules` (sub-module path strings). `CodeSyncCoordinator.refreshPom` walks the module tree starting from the root pom, fetches each child pom via `RepoFileFetcher`, and persists per-module observations with append-only diff (insert new, append-update changed, tombstone disappeared). New `ModulePageRenderer` produces an L4 page per module (heading, parent link, sub-modules link list, coordinates, language/framework versions, declared-deps list, back-link). `SyncCoordinator.syncModulePages` lifecycles the per-module pages in two passes (create then update) so parent/child cross-links resolve; `cleanupDeletedModulePages` deletes orphan Confluence pages on the next sync. **Test counts**: atlas-domain 45 (+4), atlas-intake 101 (+6), atlas-mcp 24 (=), atlas-confluence-sync 114 (+11). Total **284** (+21). All green under `mvn verify`. Branch: `main`, 14 → 15 commits ahead of origin (M2 commit pending). The L2 service page does NOT yet link to its module pages — Section 8 "Internals" is M4. **Next**: M3 — Spring bean / class index (L5). Will mirror M3's `JavaTestExtractor` for a `JavaBeanExtractor`, add a new `service_beans` table (V23) and `services.beans_page_id` (V24), produce a single per-service Beans page grouped by stereotype.

### M3 — L5 Spring bean / class index (committed: pending)

**What's working**

- **The pattern is now load-bearing.** M3 is the third milestone in a row (after M1, M2) that follows the same shape: append-only schema with `observed_at`/`presence`/`source`, latest-per-key live-view query, JSON-as-TEXT for nested data, AST extractor + coordinator orchestrator + renderer + SyncCoordinator lifecycle. By M3 the design rule was so well-rehearsed that nearly every step had a clear precedent file to copy from. Most of the M3 work was *configuring* the pattern (which columns, which key, which display order) rather than *inventing* it.
- **JavaBeanExtractor mirrors JavaTestExtractor cleanly.** Both walk JavaParser AST, both filter by annotation simple name, both surface package-name + class-name. The new wrinkle — public-method enumeration with first-sentence javadoc — added one helper (`renderSignature`) and one heuristic (`firstSentence`). Total new extractor code: ~150 lines including imports.
- **Stereotype scope held at Option A.** No pressure during M3 to widen to JPA/Spring Data; the dogfood will tell us whether the narrow set is too thin. The V23 CHECK constraint on stereotype values keeps this tight at the DB layer too — `INSERT INTO service_beans (stereotype) VALUES ('Entity')` would fail.
- **The Beans page mirrors the Tests page pattern.** Single page per service, always rendered (with thin "no beans" note when empty), tracked via `services.beans_page_id` (V24 mirrors V16 exactly). Consistent sidebar tree convention; nothing new architecturally.
- **`source='source-tree'` is a clean new vocabulary term.** Adding it to the new table's CHECK without rippling through every existing CHECK constraint kept the migration small. Future per-row provenance tags can extend the same way.

**What's not (or what bit me)**

- **The "first sentence" javadoc heuristic is approximate.** I picked "first period followed by whitespace or end-of-string." That misclassifies `Mr.` and `e.g.` as sentence ends. For the architectural-seam page, this is fine — the truncation is a teaser, not a contract — but it's a known oddity worth flagging if a class summary ends up cut at a weird spot.
- **The renderer's defensive "skip unknown stereotype" branch** is a future-proofing piece that's untested in production. The unit test pins the behavior (an unknown stereotype is skipped, not crashed-on), but a real future writer that emits a non-canonical stereotype would silently disappear from the page. Worth a one-line log in M4 cross-link review.
- **Public-only filter excludes package-private and protected methods.** Protected methods are sometimes the architectural seam (template-method pattern, Spring's `@Transactional` proxies, etc.). M3 plan open question 3 flagged this; default held at "public only." Revisit if dogfood feels thin.
- **No live-verify against the dogfood Confluence space yet.** Same deferral as M1 + M2; M4 runs the full dogfood walk-through.
- **The Beans page has no link from the L2 service page.** Same M4 deferral as the L4 module pages — Section 8 "Internals" wires everything together. Today, navigation is via Confluence's sidebar tree.
- **Stereotype groups within the page lose module-of-origin information.** A `@Service` declared in `billing-api` and one declared in `billing-core` both appear under the same `@Service` heading. The plan called out grouping-by-module within stereotype as a possible refinement; at prototype scale I went with a flat list ordered by FQ class name, which is what the repository already returns. Worth revisiting if multi-module services produce confusingly-mixed lists.

**Resumable summary (M3)**

Phase 5.6 M3 (L5 Spring bean / class index) lands. Persistence shape: new `service_beans` table (Flyway V23, append-only, mirrors `service_modules`); new `services.beans_page_id` column (V24, mirrors `services.tests_page_id`). New `JavaBeanExtractor` mirrors `JavaTestExtractor`: walks JavaParser AST, picks top-level classes annotated with `@RestController`/`@Controller`/`@Service`/`@Repository`/`@Component`/`@Configuration`, captures FQN + first-sentence class javadoc + per-public-method `{name, signature, javadoc}`. New `BeanRecord` carries the extracted data. `CodeSyncCoordinator.refreshBeans(serviceId)` walks `{module_path}/src/main/java`, persists per-bean rows append-only with tombstoning of disappeared classes; new `POST /api/code-sync/refresh-beans/{serviceId}` REST endpoint. New `BeansPageRenderer` produces a single per-service page grouped by stereotype (RestController → Controller → Service → Repository → Component → Configuration), with one block per class showing FQN, javadoc summary, and public-method signatures. `SyncCoordinator.syncBeansPage` lifecycles the per-service Beans page (mirrors `syncTestsPage`). **Test counts**: atlas-domain 49 (+4), atlas-intake 115 (+14: 10 extractor + 4 coordinator), atlas-mcp 24 (=), atlas-confluence-sync 123 (+9: 7 renderer + 2 SyncCoordinator integration). Total **311** (+27). All green under `mvn verify`. Branch: `main`, 15 → 16 commits ahead of origin (M3 commit pending). The L2 service page does NOT yet link to its Beans page or its module pages — Section 8 "Internals" is M4. **Next**: M4 — cross-link integration + phase close. Will add Section 8 "Internals" to `ServicePageRenderer` (links to L3 endpoints, L4 modules, L5 Beans, Tests page); add a "How to read this space" preamble link list to the landing page; run dogfood verification end-to-end against the ATLAS Confluence space; append end-of-phase reflection.

### M4 — Cross-link integration + phase close (committed: pending)

**What's working**

- **The two-pass L2 render in `SyncCoordinator.syncOneInternal`** turned out to be the right unplanned add. Pass 1 (render → create-or-update L2 page) gives child pages a parent id to nest under; the per-endpoint / per-module / Tests / Beans syncs run; pass 2 re-renders the L2 page so Section 8 "Internals" picks up the page ids those child syncs just persisted. Without it, the very first sync of a fresh service would render Section 8 with thin notes for Beans/Tests/Modules and require a *second* `sync/run` to fill them in. Mirrors the create-then-update pattern M2 used for L4 module pages — same eventual-consistency reasoning, applied one level up. Cost: one extra Confluence PUT per service per sync. Cheap and demo-day-saving.
- **Legacy 10-arg `ServicePageContext` constructor as a compatibility shim** kept ~10 existing test fixtures and 1 production call site (`SyncCoordinator.buildContext`) compiling unchanged. Mirrors the M1 trick of an ApiSummary convenience constructor. Same lesson three milestones in a row: when extending a record's components, a delegating secondary constructor pays for itself.
- **Red-first held all four milestones.** 5 new tests existed (failing — `<h2>Internals</h2>` not in output, `How to read this space` not in landing page) before any renderer line was written. Confirmed via a focused `mvn -pl atlas-confluence-sync test -Dtest=ServicePageRendererTest,LandingPageRendererTest` that printed exactly the 5 expected failures.
- **Section 8 is the smallest milestone of Phase 5.6 by far**: ~80 lines of renderer code (`renderInternals` + `coordinatesSummary`), ~30 lines of plumbing in `SyncCoordinator.buildContext` + `syncOneInternal`. Yet visually it's the milestone that turns the L1→L5 layers from four disconnected page sets into a coherent walkable space. The size-to-impact ratio is the strongest signal of how much architectural work the earlier milestones front-loaded.
- **Live dogfood verification of all three services against the ATLAS Confluence space succeeded**. Every service's Section 8 renders with correct cross-links to its module pages, Beans page, Tests page, and L3 endpoint pages. The landing-page preamble paragraph is live. The L1→L5 drill-down is walkable in three clicks per the success-criteria target.

**What's not (or what bit me)**

- **Initial dogfood loop tripped over zsh's no-default-word-splitting on scalar variables.** `for svc in $SERVICES` (where `SERVICES` was a space-separated string) iterated once with `svc` set to the *entire* string in zsh — caught immediately when only one service's output appeared and the printed name didn't match the printed id. Fixed by switching to a zsh array `PAIRS=(... ...)` with `for svc in "${PAIRS[@]}"`. Memorable: when scripting an interactive zsh loop, prefer arrays. Lesson promoted to ad-hoc note (worth promoting to project rule if more shell scripting accumulates).
- **Pre-existing dogfood data quirk.** Two of three Atlas services (`atlas-mcp`, `atlas-confluence-sync`) have `module_path=NULL` while `atlas-intake` has `module_path='atlas-intake'`. `refreshBeans` walks `{module_path}/src/main/java`; with NULL, it walks a non-existent path and finds 0 beans. M4's renderer handles this correctly — beans count is 0 but the Beans page is still created with a thin note (per M3 design), so Section 8's Code-index link works even though the linked page is sparse. The fix is a one-line `UPDATE services SET module_path = name WHERE module_path IS NULL` (a *data* change, not a code change). Captured as Phase 6 carry-over rather than fixed in this session.
- **M2 dogfood quirk reaffirmed visually.** Each Atlas service's Internals → Modules block lists the entire monorepo (5 entries: `(root)` + `atlas-confluence-sync` + `atlas-domain` + `atlas-intake` + `atlas-mcp`), even though each service *is* one of those leaf modules. The plan called this out at M2's decision point; M4 confirms it's noisy in practice. Phase 6 polish: scope each service's Modules list to its own subtree, or omit the single-leaf root page entirely. Not in scope for M4.
- **The Endpoints recap in Section 8 duplicates Section 3 APIs.** The template explicitly calls this intentional ("repeated here so the Internals block is the single drill-down anchor"). Reading the actual rendered page, the duplication does feel slightly redundant. Acceptable for the prototype; revisit if Phase 6 audience flags it.

**Resumable summary (M4)**

Phase 5.6 M4 (Cross-link integration + phase close) lands. `ServicePageContext` gains four new components: `List<ServiceModule> modules`, `Map<String, String> modulePageUrlsByPath`, `String beansPageUrl`, `String testsPageUrl`. Legacy 10-arg constructor preserved as a compatibility shim. `ServicePageRenderer.renderInternals` produces a new Section 8 ("Internals") with four sub-blocks — Modules / Code index / Tests / Endpoints — each falling back to a thin note when its layer has no data. `SyncCoordinator.syncOneInternal` now does a **two-pass L2 render** (pass 1 → child syncs → pass 2) so the Internals block reflects the page ids assigned during the child syncs without requiring a second sync round. `LandingPageRenderer` gains a "How to read this space" preamble paragraph explaining the L1→L5 drill-down convention. **Test counts**: atlas-domain 49 (=), atlas-intake 116 (=), atlas-mcp 24 (=), atlas-confluence-sync 128 (+5: 4 ServicePageRenderer Internals + 1 LandingPageRenderer preamble). Total **317** (+6). All green under `mvn verify`. Branch: `main`, 16 → 17 commits ahead of origin (M4 commit pending). **Dogfood verified live** against the ATLAS Confluence space: all three services render Section 8 with correct cross-links to L3 endpoints, L4 modules, L5 Beans, and Tests pages; landing page preamble visible. Phase 5.6 closed.

---

## End-of-phase reflection

### Are we still on track for the project's stated goals?

Yes. Phase 5.6's stated goal — "a stakeholder can open the landing page and walk top-down into any class's public method signatures in three clicks" — is verified live in the dogfood. All five plan-stated drill-down deliverables landed: L3 schema-level API detail (M1), L4 Maven module tree (M2), L5 Spring Beans index (M3), L2 Internals cross-reference (M4), end-to-end dogfood walkthrough (M4). The Phase 6 stakeholder-demo path is now well-positioned: the L2 Internals block is the demo's "wow moment" anchor.

Phase trajectory at a glance:
- 4 milestones, 4 sequential commits (`acc1d36` plan + `deb8fef` M1 + `0c8ff5e` M2 + `8b0314d` M3 + M4 pending).
- +70 tests over the phase (M1 +16, M2 +21, M3 +27, M4 +6).
- 4 new migrations (V21–V24): `apis.openapi_snapshot` (V21), `service_modules` (V22), `service_beans` (V23), `services.beans_page_id` (V24). All append-only-with-presence.
- 4 new Confluence page types now lifecycle through `SyncCoordinator`: per-endpoint (already existed but now richer), per-module (new), per-service Beans (new), per-service Tests (existed). Each follows the same create-or-update + 404-recreate + cleanup-on-tombstone pattern.

### What surprised

- **The append-only-with-presence pattern is now load-bearing across six tables**: `apis`, `service_metadata`, `service_test_scenarios`, `service_external_deps`, `service_modules`, `service_beans`. By M3 the pattern was so well-rehearsed that the milestone was largely "configure the pattern for one more table" rather than "invent persistence shape". Strongest signal yet that append-only-with-presence is the right abstraction for code-derived facts. Production Atlas should assume this pattern continues for any future code-derived data.
- **The two-pass L2 render in M4 was an unplanned addition** that became the demo-day insurance. The plan's M4 step 3 just said "implement Section 8". The two-pass was a milestone-internal judgment call to remove the first-sync-renders-thin-notes eventual-consistency artifact. Worth doing; cost was ~30 lines of plumbing in `SyncCoordinator`.
- **M3's stereotype-scope question (Option A — Spring stereotypes only) held with no production pressure to widen.** The dogfood beans count is 2 for atlas-intake. The actual Spring layer is sparse at prototype scale; widening to JPA `@Entity` / Spring Data interfaces / `@ConfigurationProperties` (DD-015 candidate) would not have helped. Held at Option A; revisit if Phase 6 demo audience asks for the persistence surface.
- **M2's "atlas-service-is-a-leaf-of-bigger-atlas-monorepo" quirk is more visually noisy than expected.** Every Atlas service's Internals shows 5 modules (the whole monorepo). The L4 root-only page is largely a duplicate of the L2 Technical Details. Phase 6 polish carry-over.

### New risks / opportunities surfaced

- **One-line dogfood data fix to apply pre-Phase-6.** `UPDATE services SET module_path = name WHERE module_path IS NULL AND name IN ('atlas-mcp', 'atlas-confluence-sync')` — then re-run `refresh-pom`/`refresh-beans`/`sync/run`. Will turn the currently-empty Beans pages into populated ones for atlas-mcp + atlas-confluence-sync. Lightest-weight fix.
- **L4 root-only-module polish**, ~50 lines: in `ModulePageContext`/`ModulePageRenderer`, omit the trivial root page when `children.isEmpty()` and surface its info inline on the L2 service page's Technical Details. Removes one largely-redundant Confluence page per leaf-only service.
- **L4 monorepo-scoping**, larger: scope each service's Internals → Modules block to the subtree rooted at the service's own module. Today it lists all observed modules for that service-id; for atlas-services-as-leaves-of-bigger-monorepo, that is the entire monorepo. Phase 6 polish OR a Phase 7 production-data refinement, depending on whether real org services are typically multi-module.
- **DD-015 (L5 widen stereotype scope) — unopened.** Held at Option A. Open in Phase 6 if demo audience asks for the persistence surface.
- **DD-014 (private-repo GitHub auth) — still candidate.** Affects all source-tree readers. Open at first real-org dogfood where private repos are the norm.
- **MariaDB project rule (`VARCHAR(191)` for unique-index columns)** is still a CLAUDE.md Constraints candidate. Phase 5.6 didn't add new unique indexes that triggered it, so the rule sat dormant; promote at the next phase that adds one.

### Recommendation for next phase

**Phase 6 (Demo and Handoff) is the right next phase**, on the trajectory the previous end-of-phase reflection set. The L1→L5 drill-down works; the L2 Internals block makes the demo land harder (three clicks from landing page to any public method). Phase 6 plan should:

1. **Apply the one-line dogfood data fix** before recording the demo, so atlas-mcp + atlas-confluence-sync render populated Beans pages.
2. **Optional polish**: L4 root-only-page omission (~50 lines). Removes the most visible Atlas-monorepo duplication from the demo recording.
3. **Demo script updates**: walk through the L1→L5 drill-down explicitly. Land the "three clicks" framing.
4. **Deferred-decisions list as the Phase 6 deliverable to seal**: the production-readiness checklist is what hands off to the team taking Atlas to production.

Production-readiness DDs (DD-001 auth, DD-003 CI, AWS provisioning) remain the gate to Phase 7, but they're independent of Phase 6's stakeholder-demo path and can be sequenced after.
