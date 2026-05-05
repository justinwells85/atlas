# Plan — Phase 5.9 — Configuration extraction

Created: 2026-05-05, immediately after Phase 5.8 close. Sequenced before the (paused) Phase 5.7 Spring Integration drill-down resumes, so the SI work lands on top of full per-service config visibility.

## Why this phase exists

The ownership-analysis mission's biggest remaining blind spot is **what a service actually runs with**. Atlas today renders APIs, dependencies, classes, modules, tests, and architecture. It does not render:

- What property keys the service reads (from `application.properties` / `application.yml` / profile-specific overrides).
- Where in the source those properties are consumed (`@Value("${...}")` injection sites, `@ConfigurationProperties`-backed records).
- Which `@Enable*` annotations the service activates and what subsystems each one turns on.

For a service that's mostly off-the-shelf Spring controllers, this gap is small. For a service that leans heavily on in-house `@Enable*` annotations, profile-specific config, or external config sources, **the documentation Atlas produces today doesn't answer the questions an ownership transition actually needs answered**: "what knobs exist, what their defaults are, what overrides apply per profile, and which subsystems turn on in production." Closing this is a hard prerequisite for Phase 5.7's SI drill-down — flow-graph documentation without configuration context is half the picture.

## Goal

Extract a service's configuration surface (declared keys + consumption sites + `@Enable*` activations) and render it as a per-service Configuration page in the same shape as the existing Tests / Beans / Modules pages. After this phase, opening any service's vault answers "what runs with what" without leaving Atlas.

## Success criteria

1. **Property keys captured from `application.properties` and `application.yml`** including profile-specific overrides (`application-dev.properties`, `application-prod.yml`, etc.). Each key recorded with: key path (e.g. `spring.datasource.url`), default value (if set in the file), source file (relative path), profile (or `default`), and `observed_at` / `presence` for append-only semantics.
2. **`@Value` injection sites captured**: every constructor parameter / field / method parameter annotated `@Value("${key:default}")`. Recorded with: SpEL expression (raw), resolved key, default-from-SpEL (if any), enclosing class, and member name.
3. **`@ConfigurationProperties` types captured**: every class / record annotated `@ConfigurationProperties(prefix=...)`. Recorded with prefix, enclosing class, and the list of declared property components (record components or bean fields with their declared types).
4. **`@Enable*` annotation use sites captured**: every annotation usage on `@Configuration` / `@SpringBootApplication` classes whose simple name starts with `Enable`. Recorded with: annotation simple name, fully-qualified import path, enclosing class, and (when the annotation type is in the same repo) its javadoc first sentence.
5. **Per-service Configuration page renders all four sets** — one Confluence page + one Markdown page per service (sink-neutral via the existing `WikiSink` + parallel-renderer pattern). Headings: Properties (grouped by profile), `@Value` injections (grouped by enclosing class), `@ConfigurationProperties` types, `@Enable*` activations.
6. **Cross-link from `@Value` / `@ConfigurationProperties` rows to the matching key row** when the property is also declared in a properties/yml file. Confluence: hyperlink within the page; Markdown: anchor link.
7. **L2 Section 8 "Internals" gains a Configuration sub-bullet** linking the new page; thin note when the service has no configuration data captured (e.g. extraction never ran).
8. **Append-only-with-presence semantics** carry over. Three new tables (one per data type that needs lifecycle: `service_config_properties`, `service_value_injections`, `service_configuration_properties_types`, `service_enable_annotations`) follow the `service_modules` / `service_beans` shape: `observed_at`, `presence`, `source='source-tree'` (or `source='properties-file'` for V26), latest-per-key live-view queries.
9. **MariaDB portability** — every new migration applies cleanly under both Postgres + MariaDB. Vendor splits used only when truly needed.
10. **Dogfood** — Atlas's own three modules render Configuration pages with real data (Atlas itself uses `@Value` for the Anthropic API key, has profile-specific config now via M4's `confluence-dogfood`, and uses several `@Enable*` annotations that Spring Boot adds implicitly via `@SpringBootApplication`).

## Assumptions

- **AST-only extraction**, same constraint as `JavaBeanExtractor` / `JavaTestExtractor`. No compilation, no runtime classpath, no reflection. JavaParser pinned to `JAVA_21` is sufficient for Atlas's own code; per-service language-level override remains available.
- **YAML parsing via SnakeYAML** (already on the classpath; logback uses it). `application.yml` parsing produces a flat key tree; nested maps flatten with dot-joined keys per Spring Boot conventions.
- **Properties file location follows Spring Boot conventions**: `src/main/resources/application*.{properties,yml,yaml}`. Multi-document YAML files (`---` separators) are split before parse. `spring.config.import` is **out of scope** — circular / chained imports are not followed.
- **`@Enable*` annotation discovery is lexical, not semantic.** "Annotation simple name starts with `Enable`" is the filter; we record every match without verifying it's a Spring meta-annotation. Org-internal annotations following the same naming convention are captured as a side benefit.
- **Same-repo javadoc resolution for `@Enable*`** uses the existing `RepoFileFetcher`. If the annotation's source file is reachable from the configured repo URL, pull javadoc; otherwise record `null`. External annotations (`@EnableJpaRepositories`, etc.) get a name + import path only.
- **Cross-link resolution between `@Value` and properties files** is best-effort: if a `@Value("${foo.bar}")` matches a `foo.bar` key declared in any of the service's properties files (any profile), emit the link. Mismatched keys (declared but not consumed, or vice versa) appear in their respective sections without a link — the asymmetry is itself useful information for an ownership transition.
- **Profile-specific properties files are detected by filename** (`application-{profile}.properties` / `.yml` / `.yaml`). The `default` profile is everything in plain `application.properties` / `application.yml`.

## Approach

Five architectural decisions worth surfacing before stepwise breakdown:

1. **Single intake-side coordinator method (`refreshConfiguration`)** runs all four extractions in one pass per service, mirroring `refreshTests` / `refreshBeans` / `refreshPom`. Append-only writes per data type. One new REST endpoint: `POST /api/code-sync/refresh-configuration/{serviceId}`.
2. **Three append-only tables, not four.** Properties, `@Value` injections, and `@ConfigurationProperties` types each get their own table. `@Enable*` annotations also get their own. (Trying to share a table across data types would couple unrelated lifecycles; this matches the precedent set by `service_modules` / `service_beans` / `apis` / `service_test_scenarios`.) Migrations: V26 (properties), V27 (value injections + configuration-properties types — these are sibling source-tree extractions that always co-evolve), V28 (enable-annotations), V29 (services.configuration_page_id mirror of `services.beans_page_id`). Allocates four V-numbers; Phase 5.7's eventual migrations shift to V30+.
3. **Page rendering follows the Beans-page mold exactly.** New `ConfigurationPageContext` + `ConfigurationPageRenderer` (Confluence) + `ConfigurationMarkdownRenderer` (Markdown) + new sync method on `SyncCoordinator` + new `services.configuration_page_id` + per-sink `local_markdown_path` mirror via the M3 dual-column pattern. M4 wiring is mechanical at that point.
4. **Cross-link resolution lives in the renderer, not the extractor.** The four extractors emit independent rows; the renderer's `ConfigurationPageContext` builder runs a key-match join in memory. Keeps the extractors single-purpose and the cross-link logic testable in isolation.
5. **Defer two clearly-out-of-scope items.** (a) `spring.config.import` chained imports — semantic complexity that doesn't pay back at prototype scale; deferred-decision candidate **DD-016**. (b) Spring Boot's relaxed-binding aliases (`my.app.url` ≡ `my.app.URL` ≡ `MY_APP_URL`) — record keys verbatim, do not normalise. Deferred-decision candidate **DD-017**.

## Steps

Grouped into four review milestones; each ends at a green test bar with new behavior covered.

### M1 — Properties / YAML parsing (V26 + extractor + write path + REST endpoint)

1. V26 migration: `service_config_properties` table — columns `id`, `service_id`, `key_path`, `value`, `source_file`, `profile`, `source` (default `'properties-file'`), `observed_at`, `presence`. Indexes per Spring Integration plan precedent.
2. `ServiceConfigProperty` JPA entity in `atlas-domain`; repository with `findCurrentForService` (latest-per-key live view) + `markAbsent` writer.
3. New `PropertiesFileParser` in `atlas-intake/src/main/java/com/atlas/codesync/`. Inputs: properties-file content + filename. Outputs: list of `(keyPath, value, profile)` tuples. Handles `application.properties`, `application-{profile}.properties`, `application.yml`, `application-{profile}.yml`, `application-{profile}.yaml`. Multi-document YAML split on `---`. Nested YAML maps flatten with dot-join.
4. `CodeSyncCoordinator.refreshConfiguration(serviceId)` method — fetches files from configured repo via existing `RepoFileFetcher`, runs `PropertiesFileParser` over each, writes append-only.
5. REST endpoint `POST /api/code-sync/refresh-configuration/{serviceId}` mirrors the existing refresh-* endpoints.
6. Tests (TDD, in this order): parser unit tests (properties + YAML + multi-doc + nested-map flatten + missing-file tolerance); coordinator integration test (fresh service → refresh → rows present; refresh again with file changes → new observation rows + tombstones); REST controller test.

**M1 success criteria**: success criterion 1 + criterion 8 (for properties) + criterion 9. ~12-15 new tests.

### M2 — `@Value` + `@ConfigurationProperties` extraction (V27 + extractor + write path + REST extension)

7. V27 migration: two tables — `service_value_injections` (key_path, raw_spel, default_value, enclosing_class, member_name, member_kind, …) and `service_configuration_properties_types` (prefix, enclosing_class, …) plus a child table `service_configuration_properties_components` for the declared components. All append-only with the same shape.
8. JPA entities + repositories in `atlas-domain`.
9. New `JavaConfigurationExtractor` in `atlas-intake/src/main/java/com/atlas/codesync/`. AST visitor: locates `@Value` on fields / parameters / setter methods; locates `@ConfigurationProperties` on classes / records; emits the appropriate row shapes.
10. Extend `CodeSyncCoordinator.refreshConfiguration` to also walk the source tree (existing pattern from `refreshBeans`) and run `JavaConfigurationExtractor`. Same REST endpoint covers it.
11. Tests: extractor unit tests on synthetic Java sources covering field / constructor-parameter / setter-method `@Value`, record `@ConfigurationProperties`, class `@ConfigurationProperties`, default-from-SpEL parsing, malformed-SpEL tolerance; coordinator extension covered by the M1 integration test plus new assertions.

**M2 success criteria**: criteria 2 + 3 + 8 (for the two new tables) + 9. ~15-20 new tests.

### M3 — `@Enable*` annotation extraction (V28 + extractor + write path + REST extension + same-repo javadoc resolution)

12. V28 migration: `service_enable_annotations` table — columns include annotation_simple_name, annotation_fqn, enclosing_class, javadoc_first_sentence (nullable), …
13. JPA entity + repository.
14. New `JavaEnableAnnotationExtractor`. AST visitor: locates `@Enable*`-named annotations on `@Configuration` / `@SpringBootApplication`-annotated classes. Resolves FQN via the file's import statements; if the FQN points to a same-repo path (heuristic: starts with the service's root package), runs `RepoFileFetcher` for that source and extracts the type's javadoc first sentence.
15. Extend `CodeSyncCoordinator.refreshConfiguration` further. Same endpoint.
16. Tests: extractor unit tests on synthetic sources (single `@Enable*`, multiple, on `@SpringBootApplication` classes, FQN resolution from imports vs star imports, same-repo source-resolution path); coordinator extension; same-repo javadoc resolution test using fixture file content.

**M3 success criteria**: criterion 4 + criterion 8 (for the new table) + criterion 9. ~10-14 new tests.

### M4 — Configuration page renderers + sync coordinator wiring + L2 cross-link + dogfood (V29 + renderers + page lifecycle)

17. V29 migration: `services.configuration_page_id TEXT NULL` + `services.local_markdown_configuration_path TEXT NULL` (mirror of the dual-column pattern landed in M3 of Phase 5.8).
18. New `ConfigurationPageContext` record (atlas-confluence-sync). New `ConfigurationPageRenderer` (Confluence) and `ConfigurationMarkdownRenderer` (Markdown). Both consume the same context. Confluence: anchor `id="key-{key}"` on each properties row, hyperlinked from the `@Value` rows. Markdown: equivalent via Obsidian heading anchors / WikiLink fragment refs.
19. Cross-link key-match logic in the page-context builder: in-memory join between properties rows and `@Value` rows on `keyPath`.
20. New `SyncCoordinator.syncConfigurationPage(...)` mirrors `syncBeansPage`. Plumbed through `applyUpserts` per the M3 dispatch shape; populates both `configuration_page_id` and `local_markdown_configuration_path` independently.
21. `ServicePageRenderer` Section 8 "Internals" gains a Configuration sub-bullet (Markdown path uses the resolver's `wikiLinkTargetFor`).
22. Add `MarkdownPagePathResolver` rule for the new title pattern (`Service: <svc> — Configuration` → `services/<svc>/configuration.md`).
23. Tests: renderer unit tests (full / partial / minimal config) for both Confluence and Markdown; sync-coordinator integration test for the new page lifecycle; dual-sink test extension to assert the configuration page lands on both sinks.
24. Dogfood: re-register Atlas's three modules' configurations via the new endpoint; confirm Confluence Configuration pages render in the live ATLAS space; confirm Markdown vault contains `services/*/configuration.md` with cross-links resolving in Obsidian.
25. End-of-phase reflection in this plan doc + `current-state.md` refresh + `roadmap.md` close-marker for Phase 5.9.

**M4 success criteria**: criteria 5 + 6 + 7 + 8 (for V29 mirror columns) + 10. ~20-25 new tests.

## Tests

All tests are behavior-focused (CLAUDE.md TDD rule). Architectural seams that get mocks: `RepoFileFetcher` (test fixtures stand in for the GitHub Contents API). Architectural seams that get real implementations: every parser, every extractor, every renderer, every JPA path (Testcontainers).

Per-milestone test counts above are estimates; the floor is "every observable behavior in the success criteria is asserted at least once." Net Phase 5.9 add: ~60-75 new tests, bringing project total to ~540-555.

## Open questions

1. **Migration numbering vs Phase 5.7.** The Phase 5.7 plan reserves V26-V29 (per its own session note). Phase 5.9 also wants V26-V29. Since 5.9 lands first, **5.9 takes V26-V29 and the 5.7 plan shifts to V30-V33** when 5.7 resumes. Worth confirming: the 5.7 plan's "V26-V29" line was written assuming 5.7 was next; 5.8 + 5.9 both supersede that. Will update the 5.7 plan when this plan gets approved.
2. **Configuration page on the L2 Section 8** — should it appear above or below the existing Modules / Code index / Tests / Endpoints sub-bullets? Proposing below "Tests" (separates "what does it do" data above from "what does it run with" data below). Open to swap.
3. **`@Value` cross-link to Markdown anchors.** Obsidian supports `[[file#heading]]` and `[[file#^block-id]]`. Heading anchors are clean but require the heading text to be unique-ish; `^block-id` is robust but visually noisy. Proposing heading anchors with the key path as the heading text, fall back to verbatim text if the key path contains characters Obsidian's heading-anchor matcher rejects.
4. **`spring.config.import` and relaxed binding** — capturing as **DD-016** + **DD-017** in `deferred-decisions.md` as part of M1 close. Confirm this is the right call for prototype scale.
5. **Dogfood scope — RESOLVED.** Atlas's three modules have minimal config surfaces, sufficient to prove the renderer but not to exercise it under realistic load. Resolution: M4 closes against the in-repo dogfood as planned. The richer real-target dogfood is the **SI ownership-analysis target** (a confidential Spring Integration application with `spring-integration-aws` direct dependency), which still requires Phase 5.7 work to render its core structure (flows / channels / gateways / annotation-driven endpoints) before the configuration page is meaningful in context. Sequencing: this phase (5.9) → Phase 5.7-trimmed (see addendum) → re-point Atlas at the SI target as the combined dogfood for both phases. Atlas is then ownership-grade for the SI target.

## Sequencing addendum — Phase 5.7 trim (decided 2026-05-05)

The original Phase 5.7 plan has four milestones (M0 discovery + RepoSourceFetcher; M1 annotation endpoints + gateways; M2 channel beans + IntegrationFlow DSL parser; M3 per-service Flows page with mermaid graphs; M4 SI-target dogfood). When 5.7 resumes after 5.9 closes, **M3 will be cut** and the renderer will emit flows as ordered text tables instead of mermaid graphs. Rationale:

- The DSL parser (M2) does the structural extraction — every flow's ordered list of nodes (`from` / `transform` / `filter` / `handle` / `route` / etc.) with channel + handler references — and that data is the load-bearing piece for ownership analysis.
- The mermaid graphs (M3) visualize the data but add nontrivial rendering complexity (per-flow node positioning, channel-cross-flow rendering, mermaid-syntax escaping for handler signatures) without changing the underlying facts the documentation conveys.
- A text-table rendering of the same node list is still readable for ownership transition: each flow becomes a numbered list of "step N: handler X consumes from channel Y, emits to channel Z." That's the form the demo + SI-target ownership doc actually need.
- Mermaid graphs can land later as a Phase 6 polish if the demo audience asks for them. This decision is reversible.

The 5.7 plan doc will be updated in-place when 5.7 resumes (not now — 5.7 is paused and the plan's V-numbers + scope already need a rewrite from the V25 conflict). Migration numbering: 5.9 takes V26-V29; 5.7-trimmed takes V30-V32 (one fewer migration since the M3 page-id column moves into 5.7 M2's per-service Flows page lifecycle).
