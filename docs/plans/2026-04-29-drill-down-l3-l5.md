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

**Resumable summary**

Phase 5.6 M1 (L3 schema-level API detail) lands. Persistence shape: `apis.openapi_snapshot TEXT NULL` (Flyway V21, portable across Postgres + MariaDB). `EndpointRecord` and `ApiSummary` gain an `openapiSnapshot` field; `SwaggerOpenApiParser` produces the snapshot via `ParseOptions.setResolveFully(true)` + Jackson `valueToTree` with `NON_NULL` inclusion; `CodeSyncCoordinator.refreshOpenApi` persists the snapshot on every observation and treats snapshot changes as content changes (new observation appended). `ApiEndpointPageRenderer` reads the snapshot and renders Parameters / Request Body / Responses / Examples sections one level deep, with nested objects collapsing to their type name. Dogfood live-verify deferred to M4. **Test counts**: atlas-domain 41 (+2), atlas-intake 95 (+7), atlas-mcp 24 (=), atlas-confluence-sync 103 (+7). Total **263** (+16). All green under `mvn verify`. Branch: `main`, 11 → 12 commits ahead of origin (M1 commit pending). **Next**: M2 — Maven module tree (L4). Will need to extend `PomParser` for `<modules>` traversal, add a new `service_modules` table (V22), new `ModulePageRenderer`, and lifecycle the per-module pages in `SyncCoordinator`.
