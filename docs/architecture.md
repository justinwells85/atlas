# Atlas — Architecture

## Purpose

Atlas is an AI-maintained service documentation system. It builds a living inventory of services, their APIs, dependencies, and owners, auto-updated through a pipeline rather than maintained manually.

## System Overview

```
[Service Owners]
       │
       ▼
[Intake Agent]              ← AI-assisted interview, calls Anthropic API via LlmGateway
       │
       ▼
[atlas-domain DB]           ← source of truth (Postgres locally, MariaDB in production)
       │
       ├──────────────────────────────┐
       ▼                              ▼
[MCP Server]                  [Confluence Sync Agent]
   ↑ AI clients                  reads via repositories, writes via Atlassian REST API
   (Claude Desktop, Code, etc.)        │
                                       ▼
                                [Confluence Wiki]
```

The DB is the source of truth. Two consumers read from it in parallel:

- **MCP Server** is the AI-client surface (Claude Desktop, Claude Code, partner agents). It exposes capability-shaped tools (`search_services`, `list_services`, `get_service_details`, `update_service`, `delete_service`, `ping`).
- **Confluence Sync Agent** is an internal consumer that reads directly from `atlas-domain`'s repositories and renders pages into the ATLAS Confluence space. Sync is *not* an MCP client today — see `decisions.md` for the rationale and `deferred-decisions.md` if you're considering changing that.

## Components

### 1. Intake Agent

A Spring Boot REST service that conducts AI-assisted interviews with service owners. Calls an LLM via a provider-neutral `LlmGateway` (ADR-013) for description-clarification turns; the rest of the interview is deterministic state-machine logic. Captures structured data about a service and persists it to the database.

**Responsibilities:**
- Run an interview flow (questions, follow-ups based on responses)
- Validate inputs (required fields, formats, references to existing services)
- Write to the `services` table and related tables

The `LlmGateway` abstraction has two implementations: a direct-SDK `AnthropicLlmGateway` (default, used in the prototype) and an `InternalLlmGateway` stub for the org's internal LLM API gateway (DD-012, deferred). Selection is property-driven via `atlas.llm.provider`.

### 2. Database

PostgreSQL 14+ for local prototyping. The schema (see `schema.md`) is designed to be portable to MySQL/MariaDB for production migration.

**Source-of-truth principles:**
- All service inventory data lives here. Confluence is generated output.
- Changes flow DB → Confluence, never the other direction. Confluence edits will be overwritten.

### 3. MCP Server

A Spring Boot application using `spring-ai-starter-mcp-server`. Exposes Atlas data via standardized MCP tools — the AI-client surface. Inspired by OB1's tool pattern:

- `search_services` — find services by name, owner, or attributes
- `get_service_details` — full data for a service including dependencies
- `update_service` — apply changes to a service record
- `list_services` — paginated browse
- `delete_service` — soft-delete a service (per ADR-014)
- `ping` — liveness probe

Any MCP-compatible client (Claude Desktop, Claude Code, partner agents) can connect.

### 4. Confluence Sync Agent

A scheduled Spring Boot job that reads from `atlas-domain`'s JPA repositories, transforms data into the Confluence page template (see `confluence-template.md`), and updates Confluence via the Atlassian REST API. Tracks last-sync timestamp per service to enable incremental updates. Sync is an internal consumer, not an MCP client — both sit on top of the same domain layer.

The agent maintains a structured Confluence space (see `confluence-layout.md`):

- One **landing page** ("Atlas — Service Inventory") with a service-index table.
- One **About Atlas** page explaining the auto-generation model.
- Two **inventory sub-pages** ("Inventory: Data Stores", "Inventory: External Dependencies") presenting per-resource views.
- One sub-page per service (titled "Service: <name>") with the seven-section template.

All non-Home pages parent under the landing page. Service-to-service references render as hyperlinks to peer pages; database and external-dep references back-link to the inventory pages. Pages are regenerated on every sync — Confluence edits get overwritten.

## Data Flow

1. **Intake**: Service owner runs the intake agent. Agent interviews them, validates, writes to DB. Soft-delete and reactivation flow through intake too (per ADR-014).
2. **Storage**: Data lives in Postgres locally (or MySQL/MariaDB in production). `atlas-domain` is the shared library that exposes JPA entities and repositories.
3. **AI-client exposure**: MCP server makes the data discoverable and mutable by AI clients via tool calls.
4. **Sync**: Sync agent periodically (default 15 min) or on-demand reads from `atlas-domain` repositories, generates Confluence page content, writes via the Atlassian API.
5. **Consumption**: Humans read Confluence. AI agents query MCP directly.

## Migration Path

The prototype runs entirely on a Mac with local Postgres. Production migration:

1. Export data from local Postgres to a staging environment.
2. Migrate schema to AWS RDS (MySQL or MariaDB) — Flyway migrations are written in portable SQL.
3. Deploy Spring Boot apps to work-approved infrastructure.
4. Re-point connection strings; everything else stays the same.

## Reference Architecture

**OB1 (Open Brain by Nate B. Jones)** — https://github.com/NateBJones-Projects/OB1

Patterns adopted:
- Central DB as source of truth
- MCP server as the AI integration boundary
- Four-tool MCP pattern (search, browse, capture, stats)
- Structured intake workflow (interview → store → sync)
- Flexible per-record metadata via JSON columns

OB1 is reference architecture only. All Atlas code is written from scratch to comply with the work open source policy.
