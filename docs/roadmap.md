# Atlas — Roadmap

Phased delivery plan. The goal of the prototype phase is to prove the full pipeline works end-to-end with real data, then sell the idea internally for production migration.

## Phase 0 — Foundation (current)

- [x] Define Confluence page template
- [x] Define data inventory fields
- [x] Choose tech stack (Java 21 + Spring Boot 4 + Spring AI 1.1.x + Maven)
- [x] Choose DB strategy (local Postgres → AWS MySQL/MariaDB)
- [x] Design `services` table schema (V1 migration)
- [x] Set up project structure and CLAUDE.md for Claude Code
- [ ] Initialize git repo and push to GitHub
- [ ] Generate Spring Boot project scaffold via Spring Initializr
- [ ] Configure Flyway and run V1 migration locally
- [ ] First green test (smoke test: app starts, DB connects)

## Phase 1 — Schema Completion

- [ ] Design and migrate `apis` table
- [ ] Design and migrate `databases` table
- [ ] Design and migrate `external_dependencies` table
- [ ] Design and migrate relationship tables (service_dependencies, service_databases, api_consumers, service_external_deps)
- [ ] Design and migrate `service_changes` audit table
- [ ] Resolve open schema decisions (status enum portability, updated_at trigger, JSON query strategy)

## Phase 2 — Intake Pipeline

- [ ] Spring Boot CLI/REST entry point for intake
- [ ] Anthropic API integration (anthropic-java SDK)
- [ ] Interview flow logic (question → response → follow-up → validation)
- [ ] Persistence layer for capturing interview output to DB
- [ ] Tests: behavior-focused, with Testcontainers for DB integration

## Phase 3 — MCP Server

- [ ] Spring AI MCP server scaffold (`spring-ai-starter-mcp-server`)
- [ ] `search_services` tool
- [ ] `get_service_details` tool
- [ ] `update_service` tool
- [ ] `list_services` tool
- [ ] Tests: MCP tool contract tests, no implementation coupling

## Phase 4 — Confluence Sync

- [ ] Atlassian Confluence API client
- [ ] Page template renderer (DB record → Confluence storage format)
- [ ] Sync logic (create new page, update existing, track sync timestamp)
- [ ] Scheduled job for periodic syncs
- [ ] Tests: sync agent against a mocked Confluence API at the architectural seam

## Phase 5 — End-to-End Validation

- [ ] Pick one real service, run intake → DB → MCP → Confluence end-to-end
- [ ] Verify Confluence page renders correctly
- [ ] Verify update path: change a field, re-sync, confirm Confluence reflects change
- [ ] Document any rough edges discovered

## Phase 6 — Demo and Handoff

- [ ] Demo the working prototype to stakeholders
- [ ] Document AWS migration plan (RDS, deployment, secrets management)
- [ ] Handoff packet for the team taking it to production

## Phase 7 — Production Migration

(Driven by team after demo approval)

- [ ] Provision AWS RDS (MySQL or MariaDB)
- [ ] Adapt schema migrations for target DB
- [ ] Deploy Spring Boot apps to approved infrastructure
- [ ] Cut over from local prototype to production environment
- [ ] Migrate or re-collect service data from real systems
