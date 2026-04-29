# Atlas — Decision Log

Architectural decisions captured in lightweight ADR (Architecture Decision Record) format. Newest at top.

---

## ADR-015: Confluence-sync reads via `atlas-domain` repositories, not MCP

**Status**: Accepted (2026-04-28)

**Context**: The original architecture diagram in `docs/architecture.md` showed an MCP-in-the-middle pipeline: `Intake → DB → MCP Server → Sync Agent → Confluence`. The actual implementation has always read from `atlas-domain`'s JPA repositories directly — MCP is a parallel branch serving AI clients (Claude Desktop, Claude Code, partner agents), not a layer the sync path goes through. Question raised: should the sync agent be refactored to honour the original diagram and consume MCP tools instead of `atlas-domain`?

A tally of `SyncCoordinator`'s actual reads on `atlas-domain` (as of this ADR):

| Repository call | Purpose | Soft-delete-aware? |
|---|---|---|
| `serviceRepository.findAll()` | Live service list — drives the per-service render loop and well-known pages. | yes (filter implicit) |
| `serviceRepository.findById(uuid)` | `syncOne` lookup. | yes |
| `serviceRepository.findSoftDeletedWithConfluencePage()` | Orphan cleanup pass (ADR-014). | **no — bypasses filter** |
| `serviceRepository.clearConfluencePageId(uuid)` | Native UPDATE that nulls the page ID after orphan cleanup. | **no — bypasses filter** |
| `serviceRepository.save(svc)` | Persists `confluence_page_id` + `last_synced_to_confluence`. | yes |
| `relationships.findApisFor` / `findApiConsumersFor` / `findUpstream…` / `findDownstream…` / `findDatabasesFor` / `findExternalDependenciesFor` / `findRecentChangesFor` | Per-service detail rendering (matches `get_service_details` shape but with `change_history` added). | yes |
| `relationships.findAllDataStoresWithUsages()` / `findAllExternalDependenciesWithUsages()` | Inventory page rendering. | yes |
| `relationships.findAllServiceDependencies()` | Architecture map (ADR-015 follow-up; milestone C). | yes |

Replacing these with MCP tool calls would require **adding ≈4 new MCP tools** purely to satisfy sync's needs:

1. `find_soft_deleted_services_with_confluence_page` — exposes a sync-internal bookkeeping concern.
2. `clear_confluence_page_id` — write op for sync's bookkeeping.
3. `find_recent_changes_for_service` — currently rolled into per-service rendering; not in `get_service_details`.
4. `list_all_service_dependencies` (architecture map) and the two inventory variants — all-rows reads with shapes specific to renderer needs.

**Decision**: keep `atlas-confluence-sync` reading via `atlas-domain` repositories. Do not refactor.

**Rationale**:

- **MCP is the AI-client surface, not a service-bus.** Tools should be capability-shaped — verbs an AI agent would naturally invoke. `find_soft_deleted_services_with_confluence_page` and `clear_confluence_page_id` are sync-internal mechanics; exposing them as tools dilutes the AI surface with concerns that have no AI use case.
- **Coupling goes up, not down.** Today, `atlas-confluence-sync` depends on `atlas-domain`. After the refactor, it would depend on `atlas-mcp`, which depends on `atlas-domain` — same data, two layers of contracts (Java repository methods plus JSON tool envelopes). Internal consumers reading direct from the domain layer is the simpler shape.
- **Operational surface area grows.** Today sync needs the DB. After the refactor, sync needs MCP running (and reachable, and authenticated, and rate-limit-friendly). New failure modes for no functional gain.
- **Type safety regresses.** `findAllServiceDependencies()` returns `List<ServiceDependencyEdge>` — typed. The MCP equivalent returns a JSON blob requiring deserialization, with bugs surfacing at runtime instead of compile time.
- **The architecture diagram was wrong, not the code.** ADR-014 and the layout work in Phase 4.5 already pulled the data flow further from the original diagram. Milestone A's docs pass put the diagram in line with reality (parallel exposures from `atlas-domain`); this ADR records the decision to keep that reality.

**Consequences**:

- `architecture.md`'s System Overview is the authoritative shape going forward; ADR-001's "OB1 architecture" reference does not imply MCP-in-the-middle for sync.
- Future internal consumers (monitoring agent, alert pipeline, …) should also read via `atlas-domain` directly. MCP is reserved for clients that benefit from the AI-tool framing.
- If a future requirement needs sync to call out across the network (e.g., a multi-region deployment where DB and Confluence-sync are not co-located), revisit. The MCP-bridge cost would be paid for a real reason — network isolation — not architectural symmetry.

---

## ADR-014: Soft-delete pattern for `services` to support Confluence orphan cleanup

**Status**: Accepted (Phase 4.5 follow-up; closes DD-013)

**Context**: When a service is removed from the Atlas inventory, its Confluence page becomes orphaned — the sync agent needs the page ID to delete the page in Confluence, but the page ID lives on the service row, which gets removed by a hard DELETE. Three options were considered (see DD-013 for the full comparison):

1. Soft-delete column on `services` (`deleted_at TIMESTAMP NULL`); rewrite delete() into UPDATE; queries auto-filter the soft-deleted rows; cleanup-sync uses native query to bypass.
2. Shadow table (`deleted_services_pages`) populated by a trigger or app-wrapper on hard delete.
3. Confluence-side reconciliation — list all "Service: …" pages, delete those whose service no longer exists in the DB.

**Decision**: Option 1 — `services.deleted_at` plus Hibernate `@SQLDelete` + `@SQLRestriction`. Cleanup-sync uses native queries to find soft-deleted rows with non-null `confluence_page_id`, calls `DELETE /wiki/api/v2/pages/{id}`, and nulls the page ID.

Rationale:
- Standard Spring/Hibernate pattern; well-understood; no new infrastructure.
- One Flyway migration (`V11__services_soft_delete.sql`) adds an `ALTER TABLE` + index — portable across Postgres and MariaDB.
- Active table stays simple; the implicit "WHERE deleted_at IS NULL" filter is what callers want 99% of the time.
- Cleanup is idempotent (already-cleaned rows have null page ID and don't re-match the filter).
- Works on top of the prototype's hard-DELETE-via-`repository.delete()` API surface — no caller code changes.

**Consequences**:
- Every JPA query on `Service` gains an implicit `WHERE deleted_at IS NULL`. A developer writing a new query gets soft-delete-aware behaviour for free, but has to remember the filter exists when designing flows that legitimately need to see soft-deleted rows. The cleanup-sync code is the one place that bypasses it (two repository methods marked native).
- The `@SQLDelete` rewrite means `repository.delete(s)` and `deleteAll()` no longer remove rows. Tests that assert "row is gone after delete" need updating *if* they query through JPA — but the standard pattern (`existsById`, `findById` returning empty) is unchanged because of `@SQLRestriction`.
- The unique constraint on `services.name` is still in effect across soft-deleted rows. Rather than work around the constraint, intake **reactivates** the existing soft-deleted row when a user re-registers its name. The AWAITING_NAME stage falls through silently when it finds a soft-deleted match; at persist time, intake clears `deleted_at`/`confluence_page_id`/`last_synced_to_confluence` on the existing UUID, **also clears every relationship row attached to that UUID** (apis, api_consumers, service_dependencies on either side, service_databases, service_external_deps), applies the new draft fields, and writes a `service_changes` row with `change_type='reactivated'` (V12 extends the CHECK to allow this value).
- The reactivate path is a *clean restart on the services row* under the same UUID — relationships are wiped so the about-to-run intake inserts don't trip per-table UNIQUE constraints, and the audit thread (`service_changes` on the same UUID) preserves the create-then-reactivate history. Old relationship data is lost from the live tables. If retaining old relationships is required, the user should restore the soft-deleted row directly (`UPDATE services SET deleted_at = NULL`) instead of running intake.
- The next sync renders a fresh Confluence page because `confluence_page_id` was cleared.
- Soft-deleted rows accumulate forever. No retention policy. Production may want one. Not a prototype concern.
- Cleanup runs on every `syncAll()` (manual + scheduled). `syncOne()` does NOT run cleanup — it's targeted. The scheduled cron handles rolling cleanup at the configured cadence (default 15 minutes).

---

## ADR-013: LLM access via a provider-neutral gateway interface

**Status**: Accepted (Phase 5.5 M6)

**Context**: atlas-intake calls an LLM exactly once during the interview — the description-clarification turn. The original implementation (`AnthropicGateway`) wrapped the `anthropic-java` SDK directly; the dependency on a specific provider was visible in package names, class names, controller endpoints (`/api/smoke/anthropic`), property keys (`anthropic.api-key`, `anthropic.model`), and consumer code that autowired the concrete class.

The production team taking Atlas to handoff has access to all foundation models through an *internal LLM API gateway* — a single org-controlled endpoint that abstracts over multiple model vendors. Atlas needs to be configurable to route LLM calls through that internal gateway in production rather than calling Anthropic directly.

Three options were considered:

1. **Defer the abstraction until the internal gateway is implemented**. Cheapest now; pushes the swap onto the production team to do alongside their integration work, when their attention is divided.
2. **Build the abstraction now against one impl + a stub**. Locks in interface shape before the second implementation is real. Risk: the interface may not fit the internal gateway's actual API and require refactor.
3. **Spec the internal gateway's API now and design the interface for both**. Best end state, but requires a spec that doesn't yet exist.

**Decision**: Option 2 — build the abstraction now. Replace `AnthropicGateway` with a `com.atlas.llm.LlmGateway` interface and two implementations: `AnthropicLlmGateway` (current direct-SDK behavior) and `InternalLlmGateway` (stub that throws on call with a clear pointer to DD-012). Selection is property-driven via `atlas.llm.provider` (default `anthropic`), wired with Spring's `@ConditionalOnProperty`.

The interface is intentionally **use-case-shaped, not message-shape**: a single `String complete(String prompt)` method, not a `Message[] send(Message[] messages)` mirror of Anthropic's SDK. Atlas's only LLM use case today is single-prompt-in / text-out clarification; richer features (system prompts, tool blocks, multi-turn) would leak Anthropic-specific concepts into the interface and likely not match the internal gateway's API anyway.

**Convention** (the rule the abstraction enforces): the literal strings `anthropic` and `claude` may appear in code/config only inside (a) the `AnthropicLlmGateway` impl class, (b) `pom.xml` dependency declarations, (c) ADR / deferred-decisions prose, and (d) the `atlas.llm.anthropic.*` config namespace plus the `ANTHROPIC_API_KEY` env var name. Anywhere else is a leak; fix at code-review time.

**Consequences**:

- One commit's worth of mechanical churn: package rename (`com.atlas.anthropic` → `com.atlas.llm`), property rename (`anthropic.api-key` → `atlas.llm.anthropic.api-key`), endpoint rename (`/api/smoke/anthropic` → `/api/smoke/llm`), test-fixture renames. Local environments running off `application.properties` overrides may need to update property keys; the `ANTHROPIC_API_KEY` env-var name is preserved.
- The `InternalLlmGateway` stub fails on call (not at boot) — preserves the ability to exercise toggle wiring in tests and lets the property be flipped without bricking local dev. Trade-off accepted: a misconfigured production looks healthy until the first intake request.
- The interface is expected to evolve when the internal gateway lands. Single use case + small API surface keeps the eventual refactor bounded. Honest framing: this abstraction is *worth the effort even if the interface changes*, because the swap then becomes interface-fit work rather than codebase-wide find-and-replace.
- Adding a new LLM use case (e.g., summarisation) requires a new method on `LlmGateway` and an implementation in both providers. That's the friction tax for keeping the abstraction unleaky.
- See DD-012 for the deferred internal-gateway implementation, including its open spec items.

---

## ADR-012: Multi-module Maven build; MCP server in its own Spring Boot app

**Status**: Accepted

**Context**: Phase 3 introduces an MCP server that exposes the Atlas data model to AI clients. Two structural questions had to be settled before the first line of MCP code was written:

1. **Process shape.** Should the MCP server live inside the existing intake Spring Boot app (`com.atlas`, started by `AtlasApplication`), or as a separate Spring Boot application?
2. **If separate, how do the apps share JPA entities and migrations?**

Co-locating in one process is cheaper today: shared Spring context, one `mvn verify`, no module-boundary design. But the architecture diagram (`docs/architecture.md`) treats Intake, MCP, and the future Confluence Sync Agent as distinct components, and Phase 6's handoff to the enterprise team will need them deployable as independent services regardless. ADR-011's "asymmetric cost-of-being-wrong" reasoning cuts here too — except inverted: pulling apart a co-located app later means peeling JPA entities, repositories, configuration, and the Spring context boundary apart all at once, on a deadline. Splitting now, with one module and 38 tests on the line, is cheaper than splitting later with three modules of code on the line.

For sharing JPA, three options were considered:
- **Shared library module** — both apps depend on `atlas-domain` for entities, repositories, and migrations. One source of truth.
- **Duplicate JPA entities** in each app. Guaranteed to drift; rejected.
- **MCP reads via the intake REST API.** Adds an HTTP hop between two services that should both speak to the same DB; couples two prototype components in a way the architecture doesn't ask for. Rejected.

**Decision**: Convert Atlas to a multi-module Maven project with three modules:

- `atlas-domain` — library jar. Holds JPA entities (`Service`, `ServiceStatus`, `ServiceStatusConverter`), repositories (`ServiceRepository`), and Flyway migrations (`db/migration/V*.sql`). No `@SpringBootApplication` in `src/main`; a test-only `DomainTestApplication` in `src/test/java/com/atlas` lets schema and repository tests boot a Spring context.
- `atlas-intake` — Spring Boot app on port `8080`, `127.0.0.1`-only. Houses the existing intake REST controller, interview state, and `AnthropicGateway`. Depends on `atlas-domain`.
- `atlas-mcp` — Spring Boot app on port `8081`, `127.0.0.1`-only. Phase 3 MCP server scaffold; tools land in M1–M3. Depends on `atlas-domain`.

Both Spring Boot apps load Flyway autoconfig; both run migrations on startup. Flyway's `flyway_schema_history` table-level lock serializes concurrent migration attempts, so whichever app starts first applies pending migrations and the other sees a clean schema. Standard Spring Boot multi-module Flyway pattern.

**Consequences**:
- Root `pom.xml` becomes a `<packaging>pom</packaging>` aggregator. Per-module `pom.xml` files declare actual dependencies. Versions live in root via the parent BOM and explicit `<dependencyManagement>` for `atlas-domain` and `anthropic-java`.
- The Spring Boot 4 integration-module rule from ADR-007 still applies, now per module: `atlas-domain` declares `spring-boot-data-jpa`, `spring-boot-hibernate`, `spring-boot-flyway`; `atlas-intake` and `atlas-mcp` each declare `spring-boot-webmvc` and `spring-boot-tomcat` alongside `spring-boot-starter-web`.
- `AtlasApplication` is renamed to `AtlasIntakeApplication`. `AtlasApplicationSmokeTest` becomes `AtlasIntakeApplicationSmokeTest`. Schema and repository tests move to `atlas-domain` and boot the test-only `DomainTestApplication`. All 38 Phase 2 tests pass in their new homes.
- Two embedded Tomcats run side-by-side in local dev. Both bind to `127.0.0.1` per ADR-011, on distinct ports. Auth is still deferred until either app goes off `127.0.0.1` (per ADR-011's deferred-work register).
- Future modules (e.g., `atlas-confluence-sync` in Phase 4) follow the same pattern: depend on `atlas-domain`, declare their own integration modules, run on their own port.
- Boot classes live at the `com.atlas` package root (`AtlasIntakeApplication`, `McpServerApplication`) — not in a deeper sub-package — so default Spring Boot scanning picks up the shared JPA entities and repositories at `com.atlas.services`. Spring Boot 4 removed the `@EntityScan` annotation, so a boot class in a deeper package no longer has an annotation-only fallback. Discovered the hard way during M2: an MCP boot class at `com.atlas.mcp` failed to find `ServiceRepository` with `NoSuchBeanDefinitionException`, and the Spring Boot 3 fix (`@EntityScan(basePackages = "com.atlas.services")`) doesn't compile under Spring Boot 4.

---

## ADR-011: REST entry point for intake; conversation state deferred

**Status**: Accepted

**Context**: Phase 2 of the roadmap calls for a "Spring Boot CLI/REST entry point for intake." The intake flow is conversational and turn-based (ask → respond → follow-up → validate → persist), so the entry-point shape has real downstream consequences:

- A **CLI** (`CommandLineRunner` triggered by `--intake`) maps the conversation onto stdin/stdout naturally. Single-user, single-session, no auth, no session storage. Simplest possible shape, but not callable from a future web UI or by another service.
- A **REST endpoint** (`@RestController`) is reachable by any HTTP client, so a future UI or scripted caller can drive it. Cost: HTTP servers are stateless by default but interviews are stateful, so a session-storage decision (in-memory map, DB-backed, HTTP session) is forced. Plus auth becomes a real concern once anything on the network can reach it.
- **Both** doubles the test surface and pays the REST complexity cost regardless.

**Decision**: REST (`@RestController`) for the intake entry point. Conversation state tracking is deliberately deferred — the Phase 2 implementation is server-stateless: the prior conversation history rides in each request body; the server returns the next question (or a final result with a persisted `serviceId`).

Rationale:
- Atlas is heading toward enterprise handoff (Phase 6/7); preserving the option to add a UI without a transport rewrite is worth more than the LOC saved by CLI.
- The asymmetric cost-of-being-wrong: CLI → REST is a bounded refactor; REST → CLI means already-paid complexity stays unused.
- Stateless turns sidestep the session-storage design problem entirely. When real UX needs arrive (multi-device, resume-an-interview), the persistence shape is decided then with a real consumer informing the choice.

**Consequences**:
- `pom.xml` adds `spring-boot-starter-web` plus the per-integration modules (`spring-boot-webmvc`, `spring-boot-tomcat`) per ADR-007.
- The embedded server binds to `127.0.0.1` only (`server.address=127.0.0.1` in `application.properties`) for the prototype — not network-reachable, so we can defer auth without exposing the API.
- Phase 2 surfaces `/api/smoke/anthropic` (M1) and `/api/intake/turn` (M3) as the entry points. No `CommandLineRunner` for intake.
- Deferred work tracked here: (1) auth model when the prototype goes off `127.0.0.1`, (2) interview-session persistence if/when conversations need to span requests on the server side.

---

## ADR-010: JSON column access via Hibernate `@JdbcTypeCode(SqlTypes.JSON)`

**Status**: Accepted

**Context**: `services.metadata` is a JSON column today (`JSONB` on Postgres, `JSON` on MySQL/MariaDB). The application needs a way to read and write it that works on both databases. Three options were considered:

1. **Hibernate `@JdbcTypeCode(SqlTypes.JSON)`** on a `Map<String, Object>` (or domain object) field. Hibernate 6 — bundled with Spring Boot 4 — picks the right JDBC type per dialect (`JSONB` on Postgres, `JSON` on MySQL) and serialises through Jackson transparently.
2. **Custom `AttributeConverter<Map, String>`** that serialises to a `String` column. Also portable, but more boilerplate and loses the "this is JSON" signal at the schema layer.
3. **Postgres-native operators (`@>`, `?`, `->>`) inside JPQL/native queries.** Fast for filtering, but locks Atlas to Postgres and is already forbidden by `CLAUDE.md` (Constraints: Migration portability).

**Decision**: Use `@JdbcTypeCode(SqlTypes.JSON)` on a `Map<String, Object>` field for `metadata` (and any future JSON column). Filter on JSON contents in Java after fetching, never in the WHERE clause. If a particular JSON key becomes a frequent query target, promote it to a real column rather than reach for dialect-specific operators.

**Consequences**:
- One annotation, no converter code, identical entity definition on Postgres and MySQL/MariaDB.
- The existing `idx_services_metadata` GIN index remains useful for any future ad-hoc DBA-side queries but is not relied on by application code.
- We accept that filtering on JSON keys happens in Java — fine at prototype scale (low row counts), and the "promote to a column" escape hatch is well-understood when it isn't.

---

## ADR-009: `status` enum portability via TEXT + CHECK constraint

**Status**: Accepted

**Context**: V1 defined `service_status` as a Postgres `ENUM` type. Postgres ENUMs do not port to MySQL/MariaDB — MySQL has its own `ENUM` column type with different semantics, and altering the value set on either side is awkward. To keep V1 honest about its eventual production target (AWS MySQL/MariaDB, per ADR-003), the column type needs to be one that both engines model identically.

Options:

1. **`TEXT` column with a `CHECK` constraint** restricting values to the allowed set. Identical SQL on Postgres and MySQL 8 / MariaDB 10.6+. Slightly weaker than a true type, but enforced at row level on both engines.
2. **Keep Postgres `ENUM`** and accept rewriting the column at MySQL migration time. Stronger typing now, more work later.
3. **`TEXT` only, validate in application code.** Most flexible, weakest safety — relies entirely on the app being the only writer, with no defence in depth at the DB layer.

**Decision**: Convert `services.status` to `TEXT NOT NULL DEFAULT 'active'` with `CHECK (status IN ('active','deprecated','in_dev'))`. Drop the `service_status` ENUM type. Pair the DB-level CHECK with a Java enum at the application layer when entities arrive in Phase 2 (defence in depth: type safety in the app, value enforcement in the DB).

**Consequences**:
- V2 migration applies this change. Since Phase 0 just landed and the local `atlas` DB has no real data, the migration can drop and recreate the column without a data-preservation step.
- Adding a new status value in the future requires a new migration that drops and recreates the CHECK constraint. Acceptable: status changes are rare and worth being deliberate about.
- Same `.sql` text runs on Postgres and MySQL, modulo the existing `TIMESTAMPTZ` / `JSONB` substitutions documented elsewhere.

---

## ADR-008: `updated_at` auto-update via JPA `@PreUpdate`

**Status**: Accepted

**Context**: V1 sets `updated_at` to `now()` on insert via a column default, but nothing bumps it on row updates — so today the column is effectively a duplicate of `created_at`. Two ways to fix that:

1. **JPA `@PreUpdate` lifecycle callback** on the entity. Pure Java, identical behaviour on every database Hibernate supports. Downside: only fires on JPA-managed updates. A direct `UPDATE` issued via `psql` or a raw JDBC script bypasses it.
2. **Database trigger.** Fires regardless of writer. Downside: the DDL is dialect-specific. Postgres needs a `CREATE FUNCTION ... RETURNS trigger` plus a `CREATE TRIGGER`; MySQL uses a column-level `ON UPDATE CURRENT_TIMESTAMP` attribute. So we'd carry per-DB migration files for this single behaviour.

**Decision**: Use `@PreUpdate` in the JPA entity (added in Phase 2). No DB trigger, no `ON UPDATE` attribute. Migrations stay dialect-agnostic.

**Consequences**:
- Atlas's source-of-truth principle (architecture.md: "All service inventory data lives here. Changes flow DB → Confluence, never the other direction") means the application is the only sanctioned writer, so the gap doesn't matter operationally.
- Manual `psql` corrections during development won't bump `updated_at`. Acceptable trade for portability; if it bites, we revisit with a per-dialect trigger migration.
- Until Phase 2 wires up the entity, `updated_at` will continue to behave as it does today (set on insert, never moved). Schema-layer tests written in Phase 1 will document this gap rather than mask it.

---

## ADR-007: Pin Spring Boot 4 integration modules explicitly

**Status**: Accepted

**Context**: During Phase 0 smoke testing we discovered two breaking changes from prior versions, neither flagged at compile time:

1. **Spring Boot 4 modularized autoconfiguration.** In 3.x, putting `flyway-core` on the classpath was enough to trigger Flyway autoconfig. In 4.x, autoconfig classes moved out of `spring-boot-autoconfigure` and into per-integration modules (e.g., `org.springframework.boot:spring-boot-flyway`, `spring-boot-hibernate`, `spring-boot-data-jdbc`). Without the integration module, the autoconfig classes are simply absent — the application starts cleanly with no warning, but the integration silently doesn't initialize.

2. **Testcontainers 2.x renamed every artifact** to add a `testcontainers-` prefix (e.g., `org.testcontainers:postgresql` → `org.testcontainers:testcontainers-postgresql`). Spring Boot 4 manages Testcontainers 2.x by default, so any 1.x artifact name in `pom.xml` produces "version missing" errors with no hint that the artifact was renamed.

Both surfaced because behavior-focused tests (ADR-006) asserted on real outcomes (migration applied, container started) rather than on code presence. A test that just checked "is the FlywayAutoConfiguration bean defined?" would have caught the first one earlier; a test that asserted "the schema we expect exists" caught it without coupling to internals.

**Decision**: For every Spring Boot 4 integration we add (Flyway, JPA, MCP server, etc.), declare both the underlying library AND the corresponding `spring-boot-<integration>` module explicitly in `pom.xml`. Do not assume transitive pickup from a starter or a base library. When adding any dependency from a Spring Boot 3.x tutorial, verify the artifact ID against Spring Boot 4 reference docs at https://docs.spring.io/spring-boot/reference/.

**Consequences**:
- POM is more explicit (and longer) than typical Spring Boot 3.x examples.
- Online tutorials, Stack Overflow answers, and AI-generated examples written before late 2025 will frequently be wrong about both module names and autoconfig behavior. Treat all examples skeptically.
- Future migrations (e.g., bumping to Spring Boot 5) should re-verify which integration modules are needed; the modularization may be revisited.

---

## ADR-006: Strict TDD with Behavior-Focused Tests

**Status**: Accepted

**Context**: Tests serve three purposes for Atlas — living requirements documentation, refactor safety, and migration insurance (Postgres → MySQL/MariaDB). Implementation-coupled tests (mocking internal collaborators, asserting on private behavior) become a maintenance tax and break during refactoring even when behavior is preserved.

**Decision**: Strict Red/Green/Refactor TDD on all non-trivial work, with tests written against public interfaces only. Mock only at architectural seams (external services). Use Testcontainers for DB tests. Test names express specifications.

**Consequences**:
- Tests survive refactoring as long as behavior is preserved.
- Tests survive the eventual MySQL/MariaDB migration if they're decoupled from Postgres-specific SQL.
- Slightly higher upfront cost per feature; offset by lower long-term maintenance and confidence during migration.

---

## ADR-005: Java 21 + Spring Boot 4.0.x + Spring AI 1.1.x

**Status**: Accepted

**Context**: Initially considered Python given AI ecosystem maturity. Re-evaluated after confirming the official MCP Java SDK (maintained in collaboration with Spring AI), Spring Boot MCP starters, and the `anthropic-java` SDK are all production-ready. Spring AI 1.1.x explicitly requires Java 21 to build.

**Decision**: Java 21 LTS (not 25), Spring Boot 4.0.x (current stable), Spring AI 1.1.x, Maven build tool.

**Consequences**:
- Easier handoff to enterprise team (Java/Spring shop).
- Production migration to AWS is straightforward — same language, same framework.
- Spring AI MCP APIs have had churn through 2025; pin exact versions to avoid drift from online examples.
- We give up some Python ecosystem momentum but gain typed APIs and team familiarity.

---

## ADR-004: Maven, not Gradle

**Status**: Accepted

**Context**: Both work. Maven is dominant in enterprise Java; Gradle has faster incremental builds.

**Decision**: Maven, for predictability, eventual team handoff, and richer beginner-friendly tooling/documentation.

---

## ADR-003: Local Postgres prototype, AWS MySQL/MariaDB production

**Status**: Accepted (revised 2026-04-26 — see Revision below)

**Context**: Work environment provides easy access to MySQL/MariaDB on AWS but not Postgres-as-a-service. Local Postgres lets the prototype get built fast on a Mac.

**Decision**: PostgreSQL 14+ locally for the prototype. Schema designed to be portable. Production migration to AWS RDS with MySQL or MariaDB.

**Consequences**:
- Schema must avoid vendor-specific features where possible (or accept rewriting on migration).
- JSON columns supported by both — but query operators differ. Application code must use portable JSON access patterns (JPA methods, not Postgres `@>` and `?` operators).
- Postgres ENUM types don't port directly. Resolve before V2 migration.

**Revision (2026-04-26)**: Original decision specified PostgreSQL 16+. Relaxed to 14+ because the local Mac already had `postgresql@14` running and V1 schema features (`gen_random_uuid()`, `JSONB`, GIN indexes, `TIMESTAMPTZ`, ENUM types) are all available in PG 14. Production target (MySQL/MariaDB) is unchanged, so the version of the local prototype Postgres has no downstream impact.

---

## ADR-002: Postgres (relational + JSON), not Neo4j (graph)

**Status**: Accepted, with revisit trigger

**Context**: Atlas models a service dependency graph, which suggests Neo4j. But the prototype's actual queries are bounded ("what depends on Service X?"), which SQL JOINs handle fine. Graph DB introduces a steeper learning curve and weaker enterprise support story.

**Decision**: Postgres with explicit relationship tables for the prototype. Revisit if dependency traversal patterns become deep (3+ hops) or computationally expensive.

**Consequences**:
- Faster to prototype.
- Lower friction for the enterprise team taking it over.
- Some traversal queries will be more verbose in SQL than Cypher.

---

## ADR-001: OB1 as architectural blueprint, not code source

**Status**: Accepted

**Context**: OB1 (Open Brain by Nate B. Jones) is a strong reference for the architecture pattern: central DB + MCP server + structured intake. Work has an open source approval policy that would require formal review to adopt OB1 directly.

**Decision**: Use OB1 as inspiration for architecture (data model patterns, four-tool MCP design, intake flow shape). Write all Atlas code from scratch.

**Consequences**:
- No open source approval bottleneck.
- More upfront engineering, but we control the design end-to-end.
- We benefit from OB1's tested patterns without inheriting its dependencies.
