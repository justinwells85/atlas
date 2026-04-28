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
- [ ] Orphan-page cleanup on service deletion — carved out as DD-013 (data-model change required)

Closes DD-011 except for the orphan-cleanup item, which moved to DD-013.

## Phase 5 — End-to-End Validation

- [x] Define "demo-ready service shape" as entry criterion (which fields and relationship rows must be populated for a representative demo) before kicking off this phase — see `docs/phase-5-entry-criteria.md`
- [x] Pick one real service, run intake → DB → MCP → Confluence end-to-end — Atlas itself dogfooded; `atlas-intake`, `atlas-mcp`, `atlas-confluence-sync` all registered and synced
- [x] Verify Confluence page renders correctly with all expected sections populated
- [x] Verify update path: change a field, re-sync, confirm Confluence reflects change — exercised on `atlas-intake` in Phase 5.5 M5: description swapped to a verification marker (sync → page v2 reflects), restored to original (sync → page v3 reflects). Two-way update path proven.
- [x] Document any rough edges discovered — DD-005, DD-008, DD-010 captured in `docs/deferred-decisions.md`

## Phase 6 — Demo and Handoff

- [ ] Demo the working prototype to stakeholders
- [ ] Produce `docs/deferred-decisions.md` capturing prototype shortcuts the production team must address: auth model, network binding (`127.0.0.1`-only today), session-state strategy, secret handling, JSON-filter-in-Java pattern (ADR-010 escape hatch), and anything else surfaced during Phases 3–5
- [ ] Document AWS migration plan (RDS, deployment, secrets management)
- [ ] Handoff packet for the team taking it to production

## Phase 7 — Production Migration

(Driven by team after demo approval)

- [ ] Provision AWS RDS (MySQL or MariaDB)
- [ ] Adapt schema migrations for target DB
- [ ] Deploy Spring Boot apps to approved infrastructure
- [ ] Cut over from local prototype to production environment
- [ ] Migrate or re-collect service data from real systems
