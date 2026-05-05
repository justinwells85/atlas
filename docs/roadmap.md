# Atlas — Roadmap

Phased delivery plan. The goal of the prototype phase is to prove the full pipeline works end-to-end with real data, then sell the idea internally for production migration.

## Phase 0 — Foundation (current)

- [x] Define Confluence page template
- [x] Define data inventory fields
- [x] Choose tech stack (Java 21 + Spring Boot 4 + Spring AI 1.1.x + Maven)
- [x] Choose DB strategy (local Postgres → AWS MySQL/MariaDB)
- [x] Design `services` table schema (V1 migration)
- [x] Set up project structure and CLAUDE.md for Claude Code
- [x] Initialize git repo and push to GitHub
- [x] Generate Spring Boot project scaffold (hand-written `pom.xml` pinned to Spring Boot 4.0.6, Spring AI 1.1.4 BOM, Java 21)
- [x] Configure Flyway and run V1 migration locally (verified against local `atlas` DB and Testcontainers)
- [x] First green test (smoke test: app starts, DB connects, V1 migration applied)

## Phase 1 — Schema Completion

- [x] Design and migrate `apis` table
- [x] Design and migrate `databases` table
- [x] Design and migrate `external_dependencies` table
- [x] Design and migrate relationship tables (service_dependencies, service_databases, api_consumers, service_external_deps)
- [x] Design and migrate `service_changes` audit table
- [x] Resolve open schema decisions (ADR-008 updated_at via JPA @PreUpdate, ADR-009 status as TEXT+CHECK, ADR-010 JSON via Hibernate @JdbcTypeCode)

## Phase 2 — Intake Pipeline

- [x] Spring Boot REST entry point for intake (CLI dropped per ADR-011)
- [x] Add `com.anthropic:anthropic-java` dependency to `pom.xml`
- [x] Anthropic API integration using anthropic-java SDK
- [x] Interview flow logic (question → response → follow-up → validation)
- [x] Persistence layer for capturing interview output to DB
- [x] Tests: behavior-focused, with Testcontainers for DB integration

## Phase 3 — MCP Server

- [ ] Spring AI MCP server scaffold (`spring-ai-starter-mcp-server`)
- [ ] `search_services` tool
- [ ] `get_service_details` tool — response includes joined relationship data (APIs, dependencies, databases) read-only
- [ ] `update_service` tool — covers `services`-row columns only; relationship-table writes are out of scope for Phase 3 (see Phase 3.5)
- [ ] `list_services` tool
- [ ] Tests: MCP tool contract tests, no implementation coupling

## Phase 3.5 — Capture relationship data

Discovery during Phase 2: the intake interview captures 4 of 17 `services` columns and zero rows in any relationship table (`apis`, `service_dependencies`, `service_databases`, `api_consumers`, `service_external_deps`). The Phase 4 Confluence page template explicitly has APIs and Dependencies sections — without populated relationship data, rendered pages will be skeletons.

- [ ] Extend intake interview to capture remaining `services`-row fields (language, framework, repo_url, deployment, support_contact, sla, notes)
- [ ] Extend intake to capture APIs exposed by the service (writes to `apis`)
- [ ] Extend intake to capture upstream/downstream service dependencies (writes to `service_dependencies` with directed edges)
- [ ] Extend intake to capture databases used and ownership (writes to `service_databases`, with `is_owner` flag)
- [ ] Extend intake to capture external/third-party dependencies (writes to `service_external_deps`)
- [ ] Tests: behavior-focused, exercise the relationship-capture paths end-to-end

## Phase 4 — Confluence Sync

- [x] Credential discovery upfront: identify Confluence base URL, API token, target space, and page hierarchy *before* writing client code (avoid Phase 2's env-var-into-non-interactive-shell back-and-forth)
- [x] Atlassian Confluence API client
- [x] Page template renderer (DB record → Confluence storage format) — handles missing relationship data gracefully (thin sections, not errors)
- [x] Sync logic (create new page, update existing, track sync timestamp)
- [x] Scheduled job for periodic syncs
- [x] Tests: sync agent against a mocked Confluence API at the architectural seam

## Phase 4.5 — Confluence Layout Alignment

- [x] Landing page parents service sub-pages; service titles prefixed with "Service: " (M1)
- [x] Service-to-service references rendered as hyperlinks to peer Confluence pages (M1)
- [x] Inventory sub-pages: "Inventory: Data Stores", "Inventory: External Dependencies" (M2)
- [x] About Atlas page; service-page back-references to inventory pages (M3)
- [x] Orphan-page cleanup on service deletion — soft-delete column + cleanup-sync pass (ADR-014, DD-013 resolved)

Closes DD-011 entirely. ADR-014 documents the soft-delete pattern.

## Phase 5 — End-to-End Validation

- [x] Define "demo-ready service shape" as entry criterion (which fields and relationship rows must be populated for a representative demo) before kicking off this phase — see `docs/phase-5-entry-criteria.md`
- [x] Pick one real service, run intake → DB → MCP → Confluence end-to-end — Atlas itself dogfooded; `atlas-intake`, `atlas-mcp`, `atlas-confluence-sync` all registered and synced
- [x] Verify Confluence page renders correctly with all expected sections populated
- [x] Verify update path: change a field, re-sync, confirm Confluence reflects change — exercised on `atlas-intake` in Phase 5.5 M5: description swapped to a verification marker (sync → page v2 reflects), restored to original (sync → page v3 reflects). Two-way update path proven.
- [x] Document any rough edges discovered — DD-005, DD-008, DD-010 captured in `docs/deferred-decisions.md`

## Phase 5.6 — Drill-down depth (L3–L5)

Goal: extend the Confluence space so a newcomer can drill from the landing page down to a single class's public method signatures without leaving the wiki. Surfaces the depth of Atlas's data model in stakeholder demos. Plan: `docs/plans/2026-04-29-drill-down-l3-l5.md`.

- [x] L3 — schema-level API detail on per-endpoint pages (parameters, request body, responses, examples)
- [x] L4 — Maven module tree: per-module Confluence pages parented under each service
- [x] L5 — Spring bean / class index: one Beans page per service with stereotype-grouped public methods
- [x] L2 service page acquires an "Internals" cross-reference section linking L3/L4/L5/Tests
- [x] Dogfood: all three Atlas modules render the full L1→L5 drill-down end-to-end

**Phase 5.6 is closed.** Verified live in the ATLAS Confluence space: every service page's Section 8 "Internals" block links its L4 module pages, L5 Beans page, Tests page, and L3 endpoint pages. The landing page carries a "How to read this space" preamble.

## Phase 5.7 — Spring Integration drill-down (PAUSED, will resume TRIMMED)

Goal: extend Atlas so it can describe a Spring Integration service at the same granularity Phase 5.6 achieved for vanilla Spring services. The user's organization runs ~12 services on Spring Integration; Phase 5.6's L5 Beans extractor misses the framework's most interesting structure (`IntegrationFlow` DSL chains, `MessageChannel` beans, `@MessagingGateway` interfaces). Plan: `docs/plans/2026-04-30-spring-integration-drill-down.md`.

**Status: paused 2026-05-05.** Resumes **trimmed** after Phase 5.9 closes. The first real target for this phase has been confirmed as a Spring Integration application with the `spring-integration-aws` direct dependency.

**Trim decision (recorded in `docs/plans/2026-05-05-configuration-extraction.md` addendum):** when 5.7 resumes, **M3 is cut** (per-service Flows mermaid graphs). The renderer emits flows as ordered text tables instead of diagrams. M0 / M1 / M2 (RepoSourceFetcher + annotation endpoints + gateways + channel beans + IntegrationFlow DSL parser) all stay — the structural data is the load-bearing piece for ownership analysis. Mermaid visualization is deferred to a possible Phase 6 polish. **Scope additions when resumed**: `spring-integration-aws` adapter handling (`@SqsListener`, `S3StreamingMessageSource`, etc.). **Migration numbers shift to V30–V32** (V25 taken by 5.8; V26-V29 taken by 5.9; one fewer migration than the original plan since M3 is cut).

- [ ] M0 — Discovery + `RepoSourceFetcher` abstraction (local-filesystem + GitHub-Contents implementations); register target SI service via `file://` URL; scope annotation/DSL mix
- [ ] M1 — Annotation endpoints + `@MessagingGateway` interfaces (V26 + V27 + extractors + coordinator + REST endpoint)
- [ ] M2 — `@Bean MessageChannel` definitions + `IntegrationFlow` DSL parser with project-level cross-file channel resolution (V28 + V29); AWS adapter coverage
- [ ] M3 — Per-service Flows page (mermaid graph per flow) + L2 Section 8 "Internals" Flows sub-bullet
- [ ] M4 — Re-point Atlas at the SI ownership-analysis target; granularity validation; ownership-doc readiness check

Closes by phase boundary: the team can open the target's Markdown vault and walk down to per-channel / per-flow / per-handler detail. Both missions served — the same artifact also lands the Phase 6 demo.

## Phase 5.8 — Local Markdown wiki sink (CLOSED 2026-05-05)

Goal: introduce a `WikiSink` abstraction with a `LocalMarkdownWikiSink` implementation so Atlas can write its renderings to a local directory as Obsidian-compatible Markdown files instead of (or in addition to) syncing to Confluence. **Required prerequisite for the ownership-analysis mission**: confidential work targets must not egress to external Confluence. Plan: `docs/plans/2026-05-05-local-markdown-wiki-sink.md`.

- [x] M1 — `WikiSink` interface + `ConfluenceWikiSink` refactor (no behavior change)
- [x] M2 — Parallel Markdown renderers (10 of them; consume same `*PageContext`; emit GFM with YAML front matter + Obsidian WikiLinks)
- [x] M3 — `LocalMarkdownWikiSink` + V25 schema (parallel `local_markdown_path` columns) + per-sink `SyncCoordinator` rewrite
- [x] M4 — Defaults flipped (`local-markdown.enabled=true`, `confluence.enabled=false`); `confluence-dogfood` profile re-enables both sinks for the existing public Atlas instance; dogfood verified live

**Phase 5.8 is closed.** 477 active tests, 0 failures. Production defaults are now confidential-safe (local-markdown only); the public ATLAS Confluence dogfood continues to work via the `confluence-dogfood` Spring profile. Phase 5.9 (configuration extraction) is the next phase; Phase 5.7 (Spring Integration drill-down, paused) resumes after 5.9.

## Phase 5.9 — Configuration extraction (ACTIVE — M1 closed)

Goal: surface application configuration deep enough to answer "what does this service actually run with?" Closes the largest remaining gap for the ownership-analysis mission — services that lean heavily on in-house `@Enable*` annotations or external config sources are otherwise opaque to today's Atlas.

- [x] M1 — `application.properties` / `application.yml` parsing, including profile-specific overrides (V26 `service_config_properties` + `PropertiesFileParser` + `refreshConfiguration` coordinator + `POST /api/code-sync/refresh-configuration/{serviceId}`; DD-016 + DD-017 captured)
- [x] M2 — `@Value` / `@ConfigurationProperties` extraction from source (V27 adds `service_value_injections` + `service_configuration_properties_types` with JSON-encoded components on parent row; `JavaConfigurationExtractor` AST visitor; `refreshConfiguration` extended with a second pass walking `{module_path}/src/main/java`)
- [x] M3 — `@Enable*` annotation surface (V28 adds `service_enable_annotations`; `JavaEnableAnnotationExtractor` lexical filter on `@Configuration`/`@SpringBootApplication` classes with import-resolved FQN + same-package fallback; `JavaTypeJavadocIndexer` for same-module javadoc resolution; DD-018 captured for cross-module gap)
- [ ] M4 — Per-service Configuration page; L2 Section 8 "Internals" gains a Configuration sub-bullet

Closes by phase boundary: opening a service's Markdown vault answers "what config knobs exist, what their defaults are, what overrides apply per profile, and which `@Enable*` annotations are turning on what subsystems."

## Phase 6 — Stakeholder Demo and Handoff

Demo the L1→L5 + Spring Integration drill-down to internal stakeholders, using a real work-org Spring Integration service as the demo subject. Goal: win support for rolling Atlas out to the other 11 services and beyond.

- [ ] Run the granular Confluence walkthrough against the Phase 5.7 demo subject
- [ ] Produce `docs/deferred-decisions.md` capturing prototype shortcuts the production team must address: auth model, network binding (`127.0.0.1`-only today), session-state strategy, secret handling, JSON-filter-in-Java pattern (ADR-010 escape hatch), and anything else surfaced during Phases 3–5.7
- [ ] Document AWS migration plan (RDS, deployment, secrets management)
- [ ] Handoff packet for the team taking it to production

## Phase 7 — Production Migration

(Driven by team after demo approval)

- [ ] Provision AWS RDS (MySQL or MariaDB)
- [ ] Adapt schema migrations for target DB
- [ ] Deploy Spring Boot apps to approved infrastructure
- [ ] Cut over from local prototype to production environment
- [ ] Migrate or re-collect service data from real systems
