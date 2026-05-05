# Plan — Local Markdown wiki sink (Phase 5.8)

Created: 2026-05-05. Inserted between Phase 5.6 (closed) and Phase 5.7 (paused) in the execution order. Resuming 5.7 follows the close of this phase + Phase 5.9.

## Why this phase exists

Atlas's mission expanded on 2026-05-05 from "service-catalog tool" to also include "ownership-analysis tool for individual services," with a confidential Spring Integration application as the first real target. The work-confidentiality rule (no external egress, ever) means **Atlas's existing Confluence sink cannot be used against confidential targets** — it would publish source-derived content to a hosted external service. Without a local-only sink Atlas cannot be applied to the new mission at all. This phase ships that sink.

## Goal

Introduce a `WikiSink` abstraction with two implementations — `ConfluenceWikiSink` (existing behavior, refactored under the new interface) and `LocalMarkdownWikiSink` (new) — selectable per Atlas instance via independent enabled toggles. The Markdown sink writes Obsidian-compatible Markdown files to a configurable local directory tree mirroring the L1–L5 hierarchy. Atlas's renderers are extended in parallel: each existing Confluence renderer gains a Markdown sibling consuming the same page-context object.

## Success criteria

1. **Two sinks coexist.** With `atlas.wiki.sinks.confluence.enabled=true` AND `atlas.wiki.sinks.local-markdown.enabled=true`, a single sync run writes both Confluence pages and Markdown files for every page; with one disabled, only the other runs. Either can be flipped without code changes.
2. **Defaults flipped.** Out-of-the-box config: `local-markdown.enabled=true`, `confluence.enabled=false`. Confluence sync becomes opt-in via active per-instance configuration. Existing Atlas dogfood instance overrides via its own `application.yml` to keep Confluence on.
3. **Markdown vault is browseable in Obsidian.** Files are valid GFM with YAML front matter; cross-page links use Obsidian WikiLinks (`[[Service: foo]]`); mermaid graphs render via fenced ```mermaid blocks; the directory tree mirrors the L1–L5 page hierarchy.
4. **Lifecycle works for the Markdown sink.** New page on first sync writes a new `.md` file. Update overwrites (atomic via temp-file-then-rename). Soft-deleted services trigger orphan-file deletion in the cleanup pass.
5. **Existing Confluence behavior unchanged.** When the Confluence sink is enabled, the rendered output and HTTP shape are byte-identical to pre-refactor behavior. All 317 existing tests stay green throughout the phase; net new tests cover the new surface.
6. **Append-only schema model carries.** V25 adds parallel `local_markdown_path TEXT NULL` columns alongside every existing `confluence_page_id` (and equivalent) column. The Markdown sink populates the new columns; the Confluence sink populates the existing ones. Both sinks coexist on the same row.
7. **MariaDB portability.** V25 applies cleanly under both Postgres + MariaDB via `MariaDBPortabilitySmokeTest`.
8. **Atlas dogfooded against itself with both sinks.** Final phase verification: a single `mvn spring-boot:run` + `POST /api/sync/run` against the existing `atlas` DB (with Markdown sink enabled in addition to Confluence) produces a complete L1–L5 Markdown vault at the configured path AND the existing Confluence pages still update. Open the vault in Obsidian; every cross-link resolves; the graph view shows the service-dependency edges.

## Assumptions

- **One sink set per Atlas instance.** No per-service routing of which sinks fire — the choice is global to the running app. The user runs distinct Atlas instances per environment (existing `atlas` DB → both sinks enabled for parity check; future work-target instance → only Markdown enabled).
- **Obsidian as the primary Markdown viewer.** Confirmed by user 2026-05-05. Drives flavor choices: Obsidian WikiLinks (`[[name]]`) for cross-page links over standard Markdown links; YAML front matter for Properties view; mermaid via fenced blocks (built-in core plugin renders these in recent Obsidian versions).
- **Parallel renderer pattern (Option A).** Each existing `*PageRenderer` class gets a `*MarkdownRenderer` sibling consuming the same `*PageContext`. Existing Confluence renderers untouched. No shared abstract page model — that's a future refactor only justified once a third sink appears.
- **GitHub-Flavored Markdown.** Tables, fenced code, ```mermaid blocks. Universally supported by Obsidian + most Markdown viewers.
- **One sink per row in the schema.** Each `confluence_page_id` peer column gets a `local_markdown_path` peer; both columns coexist on every row, populated independently. No `wiki_sink` discriminator column — the populated columns ARE the discriminator.
- **Atomic writes.** `LocalMarkdownWikiSink` writes to a `.tmp` sibling and renames over the target path. Avoids partial reads if Obsidian is watching the file.
- **No `.obsidian/` config generated.** The first time the user opens the vault directory in Obsidian, Obsidian itself creates `.obsidian/`. Atlas does not create or manage Obsidian's config.
- **Append-only-with-presence pattern unchanged.** This phase does not introduce new entity tables; the new column is on existing tables. Append-only observations carry forward `local_markdown_path` the same way they carry `confluence_page_id` today.
- **Singleton page refs.** Landing page, About, architecture-map, inventory pages: their existing Confluence ID storage approach (today: dedicated config or singleton row) extends with a parallel Markdown path string. Exact storage mechanism resolved in M3 when we touch the code.

## Approach

Four review milestones. Smallest-cost first; the heaviest is M2 (renderers) but it's parallel-additive so blast radius is low.

- **M1 — `WikiSink` interface + `ConfluenceWikiSink` refactor** (small; no behavior change)
- **M2 — Parallel Markdown renderers** (largest; one new class per existing renderer)
- **M3 — `LocalMarkdownWikiSink` + V25 schema** (medium; the actual file-writing sink + schema)
- **M4 — Configuration + dogfood verification + phase close** (small; the parity check is the deliverable)

Each milestone closes with a reflection appended to this plan doc and a `current-state.md` update per CLAUDE.md §6.

## Milestones

### M1 — `WikiSink` interface + `ConfluenceWikiSink` refactor

**Goal**: extract a `WikiSink` interface from the current Confluence sync path so the orchestrator iterates over enabled sinks rather than calling `ConfluenceClient` directly. No behavior change; all existing tests stay green.

**Red phase tests**:
- `whenMultipleSinksEnabled_thenSyncCoordinatorCallsAllOfThem`
- `whenOnlyConfluenceSinkEnabled_thenSyncBehaviorMatchesPreRefactor` (golden HTTP-shape parity test against WireMock)
- `whenNoSinksEnabled_thenSyncCoordinatorReportsNoOpAndNoNetworkCalls`
- `whenWikiSinkPublishCalledWithPageRef_thenImplementationReceivesIt`
- `whenWikiSinkDeleteCalledWithPageRef_thenImplementationReceivesIt`

**Implementation**:
- Define `WikiSink` interface in `atlas-confluence-sync` (or a shared module if needed): `publish(WikiPage)`, `delete(WikiPageRef)`, `existsByRef(WikiPageRef)`, `lifecycleHints()` (orphan-cleanup capabilities, etc.). Exact method shape resolved during M1 — start narrow.
- Create `ConfluenceWikiSink` implementing the interface; delegate to existing `ConfluenceClient`. Do not move logic — wrap it.
- Refactor `SyncCoordinator` to inject `List<WikiSink>` (Spring auto-wires every enabled bean) and iterate.
- Spring config: `atlas.wiki.sinks.confluence.enabled` (default in this phase: `true` until M4 flips it). Bean conditional on the property.

**Done when**: all 317 existing tests pass. New tests above pass. Confluence behavior is byte-identical (same HTTP requests captured by WireMock).

### M2 — Parallel Markdown renderers

**Goal**: every existing Confluence renderer gains a Markdown sibling consuming the same page-context object. Output: GFM with YAML front matter + Obsidian WikiLinks. No `WikiSink` integration in this milestone — just the renderers, exercised by direct unit tests.

**Red phase tests** (golden-file snapshots per renderer × matrix of full / partial / minimal contexts):
- `LandingMarkdownRenderer` (with How-to-read preamble + service list)
- `ServiceMarkdownRenderer` (seven sections + Section 8 Internals)
- `ApiEndpointMarkdownRenderer` (parameters / request body / responses / examples)
- `ModuleMarkdownRenderer` (parent link / sub-modules / coordinates / deps / back-link)
- `BeansMarkdownRenderer` (stereotype-grouped class blocks)
- `TestScenariosMarkdownRenderer`
- `ArchitectureMapMarkdownRenderer` (mermaid fenced block)
- Plus inventory pages (Data Stores / External Dependencies) and About Atlas — each gets its Markdown counterpart.

**Test helpers**:
- Reuse the existing `*PageContext` fixtures from the Confluence renderer test suites — same input, parallel assertion target.
- Golden files live under `src/test/resources/markdown-golden/` per renderer.

**Implementation conventions**:
- **WikiLinks**: cross-service references render as `[[Service: <name>]]`; cross-page references inside the service tree render as `[[<title>]]` (Obsidian resolves via filename match across the vault).
- **Front matter**: every page includes `title`, `last_synced_at`, `atlas_page_type` (e.g. `service`, `endpoint`, `module`, `beans`, `tests`, `landing`, `architecture-map`, `inventory`, `about`), and a stable `atlas_id` (DB row id where applicable). Parsed by Obsidian's Properties view.
- **Mermaid**: `\`\`\`mermaid` fenced blocks. Rendered by Obsidian's built-in mermaid support.
- **Section 8 Internals (service page)**: same content as Confluence rendering, just in Markdown link form. Empty layers fall back to the same thin notes.
- **Tables**: GFM pipe tables.

**Done when**: every Confluence renderer has a Markdown sibling. Golden-file tests pass. No `WikiSink` integration yet — that's M3.

### M3 — `LocalMarkdownWikiSink` + V25 schema

**Goal**: stitch the M2 renderers into a `LocalMarkdownWikiSink` implementing the M1 interface, persisting per-page output paths so subsequent syncs can update or delete the right files.

**V25 migration** (single migration, vendor-portable):
- `services.local_markdown_path TEXT NULL` (peer to whatever holds the L2 service Confluence ref today)
- `services.tests_markdown_path TEXT NULL` (peer to `services.tests_page_id`)
- `services.beans_markdown_path TEXT NULL` (peer to `services.beans_page_id`)
- `apis.local_markdown_path TEXT NULL` (peer to `apis.confluence_page_id` — append-only, carries forward across observations like the existing column does)
- `service_modules.local_markdown_path TEXT NULL` (peer to `service_modules.confluence_page_id` — append-only same rules)
- Singleton page refs (landing / about / architecture-map / inventory) — exact storage mechanism resolved when M3 hits the code; if today they live in a singleton row or a config blob, they get a parallel Markdown ref.
- `MariaDBPortabilitySmokeTest` extended through V25.

**Red phase tests**:
- `LocalMarkdownWikiSink` against `@TempDir`:
  - `whenPublishNewPage_thenFileWrittenAtExpectedPath`
  - `whenPublishExistingPage_thenFileOverwrittenAtomically` (assert no `.tmp` left behind)
  - `whenDeletePageRef_thenFileRemoved`
  - `whenPublishWithUnsafeFilenameChars_thenSanitizedPathUsed` (e.g., method+path → `post-api-intake-turn.md`)
  - `whenPublishToNonexistentParentDir_thenDirCreated`
- Sync-coordinator integration: `whenBothSinksEnabled_thenServiceRowGetsBothColumnsPopulatedAfterSync`
- Orphan cleanup: soft-deleted service → file removed alongside Confluence page.

**Directory layout** (final, agreed in plan):
```
<atlas.wiki.sinks.local-markdown.path>/
├── README.md                              (landing — Obsidian shows as the home)
├── architecture-map.md
├── inventory-data-stores.md
├── inventory-external-dependencies.md
├── about.md
└── services/
    └── <service-name>/
        ├── README.md                      (L2 service page; Obsidian links resolve via folder note)
        ├── modules/
        │   └── <module-name>.md
        ├── endpoints/
        │   └── <method>-<path-slug>.md    (e.g. post-api-intake-turn.md)
        ├── tests.md
        └── beans.md
```

**Done when**: with both sinks enabled, a sync run produces both Confluence pages and the Markdown vault. Both sinks' `*_path`/`*_page_id` columns populate independently. All M1 + M2 + M3 tests green; net new test count documented in the M3 reflection.

### M4 — Configuration + dogfood verification + phase close

**Goal**: ship the default-flip, run the dogfood, close the phase.

**Steps**:
1. Flip `application.yml` defaults: `atlas.wiki.sinks.local-markdown.enabled=true`, `atlas.wiki.sinks.confluence.enabled=false`. Add `atlas.wiki.sinks.local-markdown.path` with no default (must be set).
2. Add a Spring profile `confluence-dogfood` that flips both back (`confluence.enabled=true`, `local-markdown.enabled=true`) for the existing Atlas instance pointed at the public ATLAS Confluence space.
3. Update `Atlas/SETUP.md` with the new config knobs and the two-instance pattern (public dogfood vs. confidential work target).
4. **Dogfood verification (manual, captured in M4 reflection)**:
   ```
   # Existing instance, --spring.profiles.active=confluence-dogfood
   mvn -pl atlas-confluence-sync spring-boot:run \
     -Datlas.wiki.sinks.local-markdown.path=/Users/justin/Obsidian/atlas-dogfood-mirror
   POST /api/sync/run
   ```
   Expected: every existing Confluence page still updates AND a complete Markdown vault appears at the configured path. Open the vault in Obsidian; every cross-link resolves; mermaid renders; front matter shows in the Properties view.
5. Update `docs/handoff/current-state.md` with the new architecture (sink abstraction; Markdown vault layout; default-flip rationale).
6. Append end-of-phase reflection to this plan doc covering trajectory vs. roadmap and recommendation for Phase 5.7 resumption (or Phase 5.9 sequencing if the SI work is best deferred).

**Done when**: dogfood verification passes. Documentation updates landed. End-of-phase reflection written.

## Tests

Per-milestone summary above. Total expected new tests by phase close: ~50–70 (M1: ~5–10 sink-routing; M2: ~30–40 renderer golden files across 8+ renderers and 3 fixture variants; M3: ~10–15 sink + integration; M4: smoke/dogfood, mostly manual).

All tests behavior-focused per ADR-006. The renderer tests pin output via golden-file comparison; sink tests pin file-system effects against `@TempDir`; integration tests pin DB state after sync. No mocks except at architectural seams (Confluence HTTP, file system is real via `@TempDir`).

## Open questions

1. **Singleton page storage** (landing / about / architecture-map / inventory). Today these singleton pages have Confluence IDs stored — exact mechanism (config property? singleton row?) needs verification when M3 lands. If a singleton row already exists, V25 just adds a peer column; if config-only, we may need a small `wiki_singletons` table. Defer the call to M3 implementation; document the choice in the M3 reflection.
2. **Atlas's own L4 module pages.** The dogfood quirk noted in Phase 5.6 (each registered Atlas service is itself a leaf of the bigger Atlas Maven build, producing a trivial root-only L4 module page) carries unchanged into the Markdown sink — this isn't a 5.8 problem to solve, but the Phase 5.6 carry-over to optionally omit `children.isEmpty()` modules still applies and would also benefit the Markdown vault. **Not in scope for 5.8.** Track for a future polish phase.
3. **Front matter schema stability.** If we anticipate Obsidian Dataview queries, the front matter keys become a contract. Initial v1 keys: `title`, `last_synced_at`, `atlas_page_type`, `atlas_id`. Treat as additive — never rename, only add. Document in `confluence-template.md` (rename to `wiki-template.md`?) — defer that doc rename to M4 if it becomes natural.
4. **Phase 5.7 migration renumbering.** Plan doc for 5.7 currently reserves V25–V28 for SI tables. 5.8 takes V25; when 5.7 resumes its plan + open migrations shift to V26–V29. Roadmap already updated. The 5.7 plan doc itself updates at resume time, not now.

---

## Reflections

(Appended at each milestone close per CLAUDE.md §6.)

### M1 reflection — `WikiSink` interface + `ConfluenceWikiSink` refactor (2026-05-05)

**What's working:**
- The interface stayed minimal: five methods (`name`, `createPage`, `updatePage`, `deletePage`, `findPageByTitle`). No URL builder, no scope handle, no per-sink rendering hook — those concerns live in the sink implementation (Confluence) or are deferred to M2 / M3 where they're actually needed. The smaller surface area meant the refactor was mechanical: every `confluenceClient.foo(...)` call site collapsed into one of three fan-out helpers (`upsertOnAllSinks`, `createOnAllSinks`, `findOrCreateOnAllSinks`).
- `@ConditionalOnProperty(matchIfMissing = true)` on both `ConfluenceWikiSink` and `ConfluenceClient` preserves the existing dogfood behavior without touching any of the 128 pre-existing `atlas-confluence-sync` tests. The disabled-Confluence path is opt-in via a single property; the default-flip can land cleanly in M4 with the `application.properties` change + per-instance overrides.
- `WikiPageNotFoundException` translation at the sink boundary (catch `ConfluencePageNotFoundException` → throw `WikiPageNotFoundException`) keeps the coordinator sink-blind. The existing recovery test (`whenUpdateReturns404_thenCoordinatorRecreatesPageAndPersistsNewId`) passes unchanged — proves the exception swap is invisible to behavior.
- Naming the M1 limit explicitly in helper names (`upsertOnAllSinks` returns the **Confluence** ref by design) and in docstrings (every helper says "M1 persistence is Confluence-only; M3 generalizes") means M3 will know exactly what to refactor. No silent technical debt.
- Recording sink as a test fake gives clean, behavior-focused fan-out coverage. Mocking sinks with Mockito would have worked but the named test double was easier to read than verify-with-arguments.

**What's not:**
- Non-Confluence sinks always run their create path on every sync because there's no per-sink ref persistence yet. For the recording sink in tests this is fine (single-call asserts), but a real second sink running in production would create duplicate pages every sync. The fix lands in M3 alongside V25 — flagging here so it's not forgotten.
- The `ConfluenceWikiSink.NAME` lookup in the fan-out helpers is a discriminator leak — the coordinator knows which sink is "the Confluence one." Acceptable for M1 (one schema column, one canonical sink) but M3 needs to replace this with per-sink ref persistence. The current code shape makes the M3 refactor obvious: every `if (ConfluenceWikiSink.NAME.equals(sink.name()))` block becomes a per-sink-column update.
- `ensureWellKnownPages` returns Confluence-format refs even when called from a future Markdown-only instance. The renderer cross-link maps (`servicePageUrls`, `inventoryUrls`, `modulePageUrls`) all carry Confluence-shaped URLs via `pageUrlFor`. M2 will need to introduce per-sink URL resolution so Markdown renderers can produce file-relative links; the existing `pageUrlFor` becomes a per-sink concern.
- `application.properties` retains `atlas.wiki.sinks.confluence.enabled=true` as the implicit default (via `matchIfMissing=true`). The user's stated preference is **local-markdown on by default, Confluence opt-in**. Holding that flip until M4 to keep the existing dogfood happy, but the deviation is real and intentional for now.

**Resumable summary:**
- **Branch state**: `main`, 16 commits ahead of origin from Phase 5.6 close (unchanged — M1 not yet committed). Pending commit covers: 5 new main-source files (`WikiSink.java`, `WikiPageNotFoundException.java`, `ConfluenceWikiSink.java`, plus refactored `SyncCoordinator.java` + `ConfluenceClient.java` annotations), 4 new test files (`ConfluenceWikiSinkTest.java`, `RecordingWikiSink.java`, `WikiSinkFanOutTest.java`, `WikiSinkDisabledTest.java`), `application.properties` documentation.
- **Tests**: 332 active across 4 modules, 0 failures, 0 skipped. atlas-domain 49 / atlas-intake 117 / atlas-mcp 24 / atlas-confluence-sync 142. Net +15 vs Phase 5.6 close (+14 in atlas-confluence-sync from M1: 9 ConfluenceWikiSinkTest + 3 WikiSinkDisabledTest + 2 WikiSinkFanOutTest; +1 incidental in atlas-intake — unrelated to this work).
- **What landed in M1**: WikiSink abstraction + Confluence implementation; SyncCoordinator iterates over `List<WikiSink>`; ConfluenceClient gated on `atlas.wiki.sinks.confluence.enabled` (default true via matchIfMissing); `WikiPageNotFoundException` translation at the sink boundary; no behavior change when one sink is enabled (parity proven by existing 14-test integration suite passing unchanged).
- **What's deferred**: per-sink ref persistence (M3 + V25); per-sink URL resolution for renderer contexts (M2); the default-flip to local-markdown (M4); `application.properties` rename of confluence-specific keys (deferred — keys stay `atlas.confluence.*` even though they're now sink-specific config; could rename to `atlas.wiki.sinks.confluence.base-url` etc. in M4 if motivated).
- **Phase 5.8 M2 next**: parallel Markdown renderers (one `*MarkdownRenderer` per existing Confluence renderer). Per-sink URL resolution arrives as part of M2 design; the M1 carry-over `pageUrlFor` becomes a per-sink concern.

### M2 reflection — Parallel Markdown renderers (2026-05-05)

**What's working:**
- The Confluence-renderer surface was actually **10**, not 7 (the plan undercounted). Each existing renderer got a Markdown sibling: `AboutMarkdownRenderer`, `ArchitectureMapMarkdownRenderer`, `LandingMarkdownRenderer`, `DataStoreInventoryMarkdownRenderer`, `ExternalDependencyInventoryMarkdownRenderer`, `TestScenariosMarkdownRenderer`, `BeansMarkdownRenderer`, `ApiEndpointMarkdownRenderer`, `ModuleMarkdownRenderer`, `ServiceMarkdownRenderer`. Plus a small shared util (`MarkdownRenderingUtil`). Net: **10 new renderers + 1 helper, 109 new tests, 0 changes to any existing Confluence renderer.** Additive-only as planned.
- Pattern was stable from renderer #2 onward: front matter via `MarkdownRenderingUtil.frontMatter(baseFrontMatter(...))`, then H1 title, then sections. Every renderer ends with the same shape so future renderers (Phase 5.7 SI flows page, Phase 5.9 configuration page) drop in cleanly.
- Obsidian conventions stayed coherent: WikiLinks (`[[target]]` short form when target == display, `[[target|display]]` aliased form otherwise) for vault-internal references; standard Markdown links (`[text](url)`) for external URLs (repo, OpenAPI spec, third-party homepages); fenced code blocks with `json` language for examples and `mermaid` for the architecture map; YAML front matter with `title`, `atlas_page_type`, optional `last_synced_at` keys.
- Field-name decision: I kept `serviceConfluenceUrl` on the existing context records (`TestScenariosPageContext`, `ApiEndpointPageContext`, `BeansPageContext`, `ModulePageContext`) as-is — interpreted by Markdown renderers as a sink-neutral back-link target. A rename to `servicePageRef` would have been cleaner but touched 5+ context records and ~20 call sites in the existing Confluence test suite. Deferred to M3 when the SyncCoordinator constructs per-sink contexts and a clean field name is more pressing. Documented at the top of each Markdown renderer that uses the field.
- Defensive choices that paid off: `escapeCell` in `ApiEndpointMarkdownRenderer` handles the GFM pipe-table edge case (literal `|` in descriptions would break rows); `sanitizeForMermaidLabel` in `ArchitectureMapMarkdownRenderer` replaces `[`/`]`/`|`/`"` with safe substitutes so service names with brackets don't poison the mermaid parser. Both have explicit tests.

**What's not:**
- 10 renderers means 10 tests files, ~2000 lines of new test code. The behavior coverage is honest — each test mirrors the Confluence sibling's coverage and adds front-matter + WikiLink assertions specific to Markdown. But the duplication between Confluence and Markdown test suites is real. A cleaner long-term shape would be one test method per behavior, parameterized over both renderers — deferred until a third sink appears.
- `ServiceMarkdownRenderer` is 400+ lines mirroring `ServicePageRenderer`'s 561 lines. Logic-for-logic copy, with the only differences being output syntax. If the Service renderer's logic changes, both files have to change in lockstep. The shared abstract page model (Option C from the plan) would eliminate this — but the cost-benefit only flips when a third sink lands.
- Per-sink URL resolution still needs M3 to land. M2 renderers consume context maps populated with WikiLink targets when invoked from the Markdown sink, but **no code today builds those WikiLink-shaped maps**. The SyncCoordinator's existing `pageUrlFor(...)` builds Confluence URLs unconditionally. M3 introduces per-sink URL resolution: when sync runs through the Markdown sink, contexts are built with WikiLink targets; when sync runs through the Confluence sink, contexts are built with Confluence URLs. Until then, the Markdown renderers are testable but not yet integrated.
- The `ConfluenceClient`-style helper methods (`pageUrlFor`, `serviceConfluencePageUrls`) carry Confluence-specific names through the renderer surface even after M2. The names will get renamed when M3's per-sink context machinery lands; for now the misnomer is well-documented in the Markdown-renderer Javadoc.
- Discovered late: the actual rendererr count (10) was higher than the plan's quote of 7. No real impact — work just expanded — but the plan's numbers should not be cited verbatim in the future.

**Resumable summary:**
- **Branch state**: `main`, 16 commits ahead of origin from Phase 5.6 close. M1 + M2 are unstaged. Pending work: 11 new main-source files (10 `*MarkdownRenderer` + `MarkdownRenderingUtil`), 10 new test files, ~2000 lines net.
- **Tests**: **441 active across 4 modules**, 0 failures, 0 skipped. atlas-domain 49 / atlas-intake 117 / atlas-mcp 24 / atlas-confluence-sync **251**. Net change vs Phase 5.6 close: +124 (M1 +15, M2 +109 — 8 About + 9 ArchMap + 11 Landing + 7 DataStore + 8 ExternalDep + 9 Tests + 9 Beans + 19 Endpoint + 12 Module + 17 Service).
- **What landed in M2**: Markdown renderers for all 10 page types in the Atlas wiki output. Front-matter + WikiLinks + GFM tables + fenced mermaid + fenced JSON examples. No integration with `WikiSink` yet — renderers are exercised by direct unit tests against in-memory contexts. `MarkdownRenderingUtil` extracted after the first 2 renderers; retrofit applied. No existing Confluence renderer was touched.
- **What's deferred to M3**: per-sink ref persistence (V25 schema), per-sink URL resolution (the SyncCoordinator builds a per-sink map of UUID → WikiLink-target when the Markdown sink is enabled), `LocalMarkdownWikiSink` implementation (writes the rendered Markdown to disk atomically), context-record field rename from `serviceConfluenceUrl` to a sink-neutral name.
- **Phase 5.8 M3 next**: `LocalMarkdownWikiSink` + V25 schema columns + per-sink rendering integration in `SyncCoordinator`. The renderers exist; M3 wires them into the sync path.

### M3 partial reflection (paused 2026-05-05 mid-flight)

**M3 was scoped as: V25 schema + entity wiring + `LocalMarkdownWikiSink` + SyncCoordinator integration + dual-sink integration test.** This session got through sub-steps 1–3 (schema + sink in isolation); sub-steps 4–6 (the SyncCoordinator integration + integration test + reflection close) are the remaining work.

**What landed:**
- V25 migration: 5 new `local_markdown_path TEXT NULL` columns. Postgres + MariaDB portable; smoke test passes.
- Entity / record / repository wiring (Service, ApiSummary, ServiceModule, ServiceRelationshipsRepository, ServiceRepository).
- `MarkdownPagePathResolver` (deterministic title→path mapping, 14 unit tests).
- `LocalMarkdownWikiSink` (atomic file writes, vault-traversal guard, 14 unit tests).
- 469 active tests, 0 failures (atlas-confluence-sync: 251 → 279).

**Open decision before continuing**: see `docs/sessions/2026-05-05-session-end.md` for the A vs. B choice on whether to push through the SyncCoordinator refactor next session or split into M3.5.

### M3 reflection (full close, 2026-05-05)

**Path A taken** — pushed the SyncCoordinator integration through in this session rather than splitting M3 into M3.5. Total scope: per-sink rendering + per-sink context construction + dual-column persistence + extended cleanup paths + end-to-end integration test.

**What's working:**

- **The `SinkRoute` enum + `SinkPlan` record kept the per-sink dispatch readable.** Each `syncXxx` method has a single `for (WikiSink sink : sinks)` loop with a `switch (routeOf(sink))` inside; each branch builds the sink-appropriate context, picks the right renderer, captures the right column-getter as `existingRef`, and bundles the right column-setter as a `persistFreshRef` lambda. The fan-out helper `applyUpserts(plans, title)` then runs them uniformly with the existing `WikiPageNotFoundException`-recovery shape. The `SinkRoute.OTHER` branch — render the Confluence body, no existing ref, no-op persist — was the right answer for `RecordingWikiSink` and any future test double or third-party sink Atlas does not yet have a renderer for. It preserved every existing fan-out test without a single line of test changes.
- **Extending `SoftDeletedApiPage` / `SoftDeletedModulePage` with the new ref column was cleaner than adding parallel "stale-by-Markdown" finders.** One query per row, both refs visible together, per-sink delete dispatched in two `if-not-null` guards. The cleanup logs naturally split by sink ("Cleaned up Confluence ..." / "Cleaned up Markdown ...") so misbehavior on one sink doesn't hide the other in operations review. For `cleanupDeletedServices` I kept the two existing finders separate (`findSoftDeletedWithConfluencePage` + `findSoftDeletedWithLocalMarkdownPath`) — a service with both refs gets visited by both passes, each clearing its own column independently. Same effect, less SQL refactor.
- **`MarkdownPagePathResolver.wikiLinkTargetFor` made the per-sink cross-link semantics deterministic.** Stored ref `services/<svc>/<svc>.md` → WikiLink target `<svc>` (Obsidian basename match) ; everything else → full-path-without-extension (Obsidian path resolution). The Markdown renderers' existing "ref equals display name → emit short form, otherwise emit aliased form" idiom (carried in `LandingMarkdownRenderer.renderServiceRow` / `ServiceMarkdownRenderer.renderServiceLink`) consumes both target shapes correctly: short basenames stay tidy, longer paths produce aliased links. **Real Obsidian-resolution semantics** — basenames need to be unique-by-construction or fully-qualified — finally have a single owner.
- **Rewriting `WellKnownPages` as per-sink ref maps + per-sink `InventoryPageUrls` map kept the well-known refresh path clean.** The previous shape carried Confluence-shaped fields directly (`landingId`, `dsInvId`, `inventoryUrls` of Confluence URLs); the new shape uses `Map<String, String> landingRefBySink` and `Map<String, InventoryPageUrls> inventoryUrlsBySink` keyed by `sink.name()`. Each per-service render asks for `pages.inventoryUrlsFor(sink)` and gets either Confluence URLs or WikiLink targets back, depending on the sink — no per-sink branching at the call site of `buildServiceContext`.
- **The integration test caught the `confluence_page_id` clobber on the first run.** With my initial fall-through ("treat unknown sinks as Confluence"), `RecordingWikiSink` ran AFTER the real Confluence sink and overwrote `confluence_page_id` with `recording-page-N`. Existing fan-out test failed loudly with the right message (`expected: "CONFLUENCE_NEW_ID" but was: "recording-page-6"`). Switching to the `OTHER` branch with a no-op persist fixed it cleanly. Lesson: when adding a discriminator, the "default fall-through" should be a no-op for state mutations, never silently coopt a known column.
- **The integration test also caught a behavioral regression in the per-service failure-counting path.** My first `applyUpserts` swallowed exceptions per-plan and logged them; that meant the `svc-bad` POST 500 was logged but the service was counted as a success. Restoring the original "exceptions propagate to the per-service try/catch" semantics fixed `whenSyncAllAndOneServiceFails_thenOthersStillSyncAndFailureIsReported`. Lesson: the L2 service-page path must let exceptions bubble; the L3-L5 child-page paths already have their own try/catch wrappers and benefit from per-plan isolation in a different way (the page method is best-effort by design).

**What's not:**

- **Per-sink branching is duplicated across every page-type.** Five sync methods (`syncTestsPage`, `syncBeansPage`, `syncOneEndpoint`, `ensureModulePageExists`, `renderAndUpdateModulePage`, plus `planServicePage`) each carry a roughly-identical `switch (routeOf(sink))` × { MARKDOWN / CONFLUENCE / OTHER } block, with the only differences being which renderer is invoked and which column getter+setter is used. ~120 lines of mechanical duplication. The cleaner abstraction is "per-page-type Renderer pack" or "per-sink RendererProvider" — declare once that the Markdown sink has a `serviceMarkdownRenderer` + `service.localMarkdownPath` getter+setter; the per-page method asks the provider for "the renderer for THIS page type on THIS sink" and "the column accessor for THIS page type on THIS sink." Deferred until a third sink lands; right now the duplication reads linearly and is small enough.
- **The `ConfluenceClient`-flavored field names on `*PageContext` records are still misleading.** `serviceConfluenceUrl`, `endpointPageUrl`, `serviceConfluencePageUrls`, `moduleConfluencePageUrls`, `inventoryPageUrls.dataStores` (named for Confluence URLs) all carry WikiLink targets when invoked from the Markdown sink. The Markdown renderers' Javadoc explicitly notes the rename; the code reads correctly; nothing breaks. Rename to `serviceRef`/`endpointRef`/`servicePageRefs`/etc. would touch ~30 call sites across 5 records and the Confluence test suite. Deferred to whenever someone is making a wider context-record refactor anyway.
- **`refreshWellKnownPages` writes the fresh ref back into the in-memory `WellKnownPages` record's mutable `Map`s.** That's a side-effect on a record's component — Java records' "immutable" guarantee is only at the field level, not at the map content. Fine in practice (the record is internal to one call) but a future "make `WellKnownPages` actually immutable" refactor would require either rebuilding the record after each refresh or accepting the in-place mutation. Not pressing.
- **The per-sink `pageUrlForOptional` / `wikiLinkTargetFor` symmetry could be a single `crossLinkTargetFor(sink, ...)` method per ref category.** Today there are six near-identical helpers (`serviceCrossLinkTargetFor`, `beansCrossLinkTargetFor`, `testsCrossLinkTargetFor`, `moduleCrossLinkTargetFor`, `endpointCrossLinkTargetFor`, plus the inline build inside `buildServiceContext`). Each is two lines; collapsing them costs more than it saves. Mentioning for completeness.
- **No validation that sink refs round-trip cleanly across renames.** If a service's slug changes (e.g., human-renamed in intake), the Markdown sink's `updatePage` is called with the OLD path while the renderer-derived path would point at a NEW location. `LocalMarkdownWikiSink` honours the stored ref, so the OLD file gets updated in place; the NEW path the resolver would derive is never used until a fresh create. Same pre-existing semantics as the Confluence sink (where renames don't move pages either). Documented in the LocalMarkdownWikiSink class Javadoc.

**Resumable summary:**

- **Branch state**: `main`, 16 commits ahead of origin from Phase 5.6 close. **No new commits this session** — M1 + M2 + M3 (full) all unstaged. Pending commit covers everything from the M1 + M2 reflections plus: extended `SoftDeletedApiPage` / `SoftDeletedModulePage` with `localMarkdownPath` + broadened `findStaleApiPages`/`findStaleModulePages` queries; new `MarkdownPagePathResolver.wikiLinkTargetFor`; SyncCoordinator full rewrite (constructor adds 10 Markdown renderer deps; new `SinkPlan` + `SinkRoute` internal types; per-sink rendering inside every fan-out helper; per-sink cleanup paths; per-sink `WellKnownPages`); new `DualSinkIntegrationTest`.
- **Tests**: **477 active across 4 modules**, 0 failures, 0 skipped. atlas-domain 49 / atlas-intake 117 / atlas-mcp 24 / atlas-confluence-sync **287**. Net change vs Phase 5.6 close: +160 (M1 +15, M2 +109, M3 +36 — 6 new `wikiLinkTargetFor` cases on `MarkdownPagePathResolverTest` + 14 from M3-partial + 14 `LocalMarkdownWikiSinkTest` + 2 `DualSinkIntegrationTest`). Local Postgres dogfood DB applies migrations through V25 (already done — schema unchanged from M3-partial close).
- **What landed in M3 (full)**: per-sink rendering wired through all 6 page types (L1 landing + L2 service + L3 endpoint + L4 module + L5 beans + tests-page) and the four other well-known pages (about, architecture-map, two inventories); dual-column persistence; per-sink cleanup paths for soft-deleted services + tombstoned api/module observations; per-sink WikiLink target derivation + `InventoryPageUrls`-per-sink construction; existing 285-test `atlas-confluence-sync` baseline preserved unchanged through the rewrite.
- **What's deferred**: M4 default-flip + dogfood verification + Atlas vault parity check. Renderer-context field rename (Confluence-flavored names → sink-neutral). Field-getter/setter abstraction across page types (deferred until a third sink). Service-rename Markdown-vault file move (carries forward the Confluence pre-existing limitation).
- **Phase 5.8 M4 next**: flip `application.properties` defaults to `local-markdown.enabled=true` + `confluence.enabled=false`; introduce a `confluence-dogfood` profile that flips both back on for the existing Atlas instance; require `atlas.wiki.sinks.local-markdown.path` (no default); update `Atlas/SETUP.md` for the two-instance pattern; run the dogfood with both sinks active and capture the output (every existing Confluence page still updates AND a complete Markdown vault appears).

### M4 / end-of-phase reflection (2026-05-05, session 3)

Bundled approach taken (per the open decision in the session-2 close note): config flip + dogfood profile + SETUP doc + dogfood verification all in one commit.

**What landed:**

- **Production defaults flipped in `atlas-confluence-sync/src/main/resources/application.properties`.** Explicit `atlas.wiki.sinks.confluence.enabled=false` and `atlas.wiki.sinks.local-markdown.enabled=true`. The matchIfMissing values on the `@ConditionalOnProperty` annotations were left untouched — the flip lives in the config file, not the bean wiring, so the annotations still encode "what the property name controls" rather than "what the prod default happens to be." `atlas.wiki.sinks.local-markdown.path` has no default; the existing constructor-level `IllegalStateException` is the gate ("must be explicitly set"). Comment block reframed to point operators at the new dogfood profile rather than at editing this file.
- **`application-confluence-dogfood.properties` profile added.** Re-enables both sinks; sets `atlas.wiki.sinks.local-markdown.path=${ATLAS_WIKI_LOCAL_MARKDOWN_PATH:${user.home}/atlas-vault}` so the existing public Atlas instance gets a sensible vault default but ENV-var override still works. Profile loaded via `--spring.profiles.active=confluence-dogfood`.
- **Test-classpath shadow added at `src/test/resources/application.properties`.** Spring Boot's classpath resolution loads test-classpath `application.properties` in place of (not merged with) the main one when test classes run. The shadow file inverts the M4 production defaults back to confluence-on / local-markdown-off so existing 5 of 6 SpringBootTest classes keep working with zero per-test changes; the shadow also re-declares the JPA + Flyway-locations + Confluence credential placeholders the smoke test needs (other tests already DynamicPropertySource-override these). 287 atlas-confluence-sync tests stayed green; 477 active project-wide.
- **`Atlas/SETUP.md` extended with a "Wiki Sinks (Phase 5.8 M4 onward)" section.** Documents the two-instance pattern (confidential local-only as default, public dogfood via `confluence-dogfood` profile) with an inline table, the `ATLAS_WIKI_LOCAL_MARKDOWN_PATH` requirement, and the exact Maven invocations for each instance. Existing SETUP content (steps 1-6 + Troubleshooting) untouched.

**Dogfood verification:**

Ran `mvn spring-boot:run -Dspring-boot.run.profiles=confluence-dogfood` against the live ATLAS Confluence space + a fresh `mktemp -d` vault. App came up clean with both sinks loaded (`LocalMarkdownWikiSink initialised; vault root: …`). `POST /api/sync/run` returned `{"successCount":3,"failureCount":0,"failures":[]}`. Vault populated with 32 files: 5 well-known (README, about, architecture-map, two inventories) + 9 per service × 3 services (`<svc>.md`, `beans.md`, `tests.md`, `modules/root.md`, plus 5–7 endpoint pages). Spot-checks confirmed:

- YAML front matter on every page (`title`, `atlas_page_type`, optionally `last_synced_at`).
- Obsidian WikiLinks resolve correctly: short form `[[atlas-intake]]` for service-page basename matches; aliased form `[[services/atlas-confluence-sync/endpoints/post-api-sync-run|POST /api/sync/run]]` for nested paths; cross-references to inventory pages (`[[inventory-data-stores|atlas]]`, `[[inventory-external-deps|in inventory]]`) preserve the inline annotation.
- Mermaid block present and well-formed in `architecture-map.md` (3 service nodes + 2 dependency edges).
- DB rows show **both** `confluence_page_id` AND `local_markdown_path` populated independently for all 3 services — dual-column persistence proven live, not just via integration test.

What still needs eyeballing in Obsidian (not automatable from CLI): Properties view rendering of the YAML front matter, mermaid render fidelity, click-through on every WikiLink. Marked as user-side smoke test; the file-level evidence above is sufficient to declare M4 done from the codebase side.

**Two friction points worth carrying:**

1. **Stale local-Maven `atlas-domain` JAR caused the first dogfood attempt to roll back to V24.** `mvn test` rebuilds module `target/classes` but does NOT install the resulting JAR into `~/.m2/repository`; `mvn spring-boot:run` reads dependent modules from `~/.m2`, so my freshly-compiled atlas-domain (V25) was invisible. The April-30 install of atlas-domain (predating V25 by ~5 days) was what got loaded; Flyway saw "24 migrations validated" and concluded the schema was up-to-date. Fix: `mvn install -DskipTests` from root before any cross-module spring-boot:run after a domain-layer change. Worth a CLAUDE.md or SETUP.md note if this trips again, but a one-time recovery this round so I haven't added it yet.
2. **Local DB had not actually applied V25 prior to this session, contrary to the session-2 close note's claim.** The `mvn install` resolution above also fixed this (V25 applied automatically when the corrected JAR loaded), but it's a reminder that "tests are green" does NOT imply "local dogfood DB is current." Tests use Testcontainers (fresh DB every run, all migrations applied); local dogfood uses the long-lived `atlas` DB which only migrates when an actual app starts up against it.

**What's working (zooming out across all 4 milestones):**

- **The decision to keep `matchIfMissing` semantics encoding *what the property does*, with the prod-vs-test difference living in property files, kept the flip surgical.** No annotation changes, no bean-wiring rethinking, no test signature changes. Two new property files + one comment-block rewrite + a 25-line SETUP section is the entire diff.
- **The test-classpath-shadow trick gave us "test default ≠ prod default" without per-test annotations.** Confirmed empirically (initial run failed with smoke-test context-load error, fixed by adding the placeholder Confluence credentials to the shadow). After that one fix, all 287 atlas-confluence-sync tests passed unchanged. This is a pattern worth knowing for any future "production-default-changed-but-tests-shouldn't-care" flip.
- **The dogfood ran clean on the first try post-V25-fix.** No code changes were required for M4 to work end-to-end — M3 had already wired everything. M4 was purely a configuration story, which is a reassuring signal that the M3 abstraction is well-shaped.

**What's not (for the phase as a whole — these are M3-era debts that M4 didn't touch):**

- The five `switch (routeOf(sink))` blocks in `SyncCoordinator` (~120 lines of mechanical duplication) still want a "per-sink RendererProvider" abstraction. Holding for a third sink to land before refactoring.
- The `serviceConfluenceUrl` / `endpointPageUrl` field names on `*PageContext` records are still misnomers under Markdown rendering. Holds until a wider context-record refactor.
- No service-rename Markdown-vault file move — same pre-existing semantics as the Confluence sink.
- `WellKnownPages.refreshXxx` mutates the in-memory record's maps. Java records are field-immutable, not deep-immutable; works, but a future "make it truly immutable" refactor would need to rebuild the record.

**Phase 5.8 — strategic close**

The phase's stated goal was "introduce a `WikiSink` abstraction with a `LocalMarkdownWikiSink` implementation so Atlas can write its renderings to a local directory as Obsidian-compatible Markdown files instead of (or in addition to) syncing to Confluence." That goal is fully met:

- The abstraction shipped (M1) with zero behavior change for the existing Confluence path.
- 10 Markdown renderers shipped (M2) covering every Confluence renderer, with Obsidian conventions (front matter, WikiLinks, fenced mermaid, GFM tables) verified test-side.
- Dual-column persistence + per-sink dispatch + cleanup paths shipped (M3) with an end-to-end integration test proving the dual-sink fan-out is correct.
- Production defaults flipped + the public dogfood preserved via a profile + verified live (M4).

**Are we still on track for the dual mission?** Yes, more so than at phase start. The ownership-analysis mission's hard prerequisite (no-egress sink for confidential targets) is unblocked. The original service-catalog mission is unaffected — the public ATLAS Confluence space still updates via the dogfood profile, and all 7 sections of the Confluence template are also produced as a Markdown vault for free.

**Roadmap impact:** None. Phase 5.7 (paused) and Phase 5.9 (planned) remain in the same order with the same scope. With 5.8 now closed, Phase 5.9 (configuration extraction) is the natural next phase — its outputs feed directly into 5.7's Spring Integration drill-down, and the local-Markdown sink is now the default rendering surface those drill-downs will land on.

**New observations worth carrying:**

- The dogfood profile pattern (`application-confluence-dogfood.properties` + `--spring.profiles.active=…`) is a clean way to express "the public Atlas instance is the OPT-IN configuration; the no-profile default is the conservative confidential-safe one." Future Phase-5.9 / 5.7 work can layer additional profiles on top (e.g. a `si-target` profile that pre-configures the SI ownership-analysis target's RepoSourceFetcher URL) without re-engineering the default.
- The "tests use a classpath-shadow file with explicit pre-flip defaults" pattern is the right shape for any future production-default flip. The cost is a ~15-line file with some duplication; the win is zero per-test annotation changes. Worth re-using when Phase 5.9's `@Enable*` defaults flip.

**Resumable summary (phase-end):**

- **Branch state**: `main`, **synced with origin** through the Phase 5.8 M1+M2+M3 commit (`9fab185`, pushed earlier this session). M4's commit lands next; this reflection is the prep for it. After commit, branch will be 1 commit ahead of `origin/main` until pushed.
- **Tests**: **477 active across 4 modules**, 0 failures. atlas-domain 49 / atlas-intake 117 / atlas-mcp 24 / atlas-confluence-sync 287. Net change vs M3: 0 (M4 introduces no new tests — pure configuration + verification).
- **What landed in M4**: production-default flip in `application.properties`; new `application-confluence-dogfood.properties` profile; new `src/test/resources/application.properties` test-classpath shadow; `SETUP.md` "Wiki Sinks" section (~25 lines). No code changes, no test changes.
- **Local dogfood DB**: now genuinely on V25 (the session-2 claim was wrong; this session caught and corrected it via `mvn install -DskipTests`).
- **What's deferred**: same M3-era list, unchanged. Plus a candidate doc-update: SETUP/CLAUDE.md note about `mvn install` after domain-layer changes (held — one-time gotcha so far).
- **Phase 5.8 status**: **closed**. M1 ✅ M2 ✅ M3 ✅ M4 ✅. End-of-phase reflection complete (above).
- **Next phase**: Phase 5.9 (configuration extraction) per the existing roadmap. Phase 5.7 (Spring Integration drill-down, paused) resumes after 5.9 closes — the SI work depends on full per-service config visibility being in place first.
