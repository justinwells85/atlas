# Atlas — Architecture

## Purpose

Atlas is an AI-maintained service documentation system. It builds a living inventory of services, their APIs, dependencies, and owners, auto-updated through a pipeline rather than maintained manually.

## System Overview

```
[Service Owners]
       │
       ▼
[Intake Agent]              ← AI-assisted interview, calls Anthropic API
       │
       ▼
[Postgres / MySQL DB]       ← source of truth
       │
       ▼
[MCP Server]                ← reads DB, exposes tools to MCP clients
       │
       ▼
[Confluence Sync Agent]     ← reads via MCP, writes to Confluence
       │
       ▼
[Confluence Wiki]           ← human-readable, auto-updated pages
```

## Components

### 1. Intake Agent

A Spring Boot CLI/REST service that conducts AI-assisted interviews with service owners. Uses the `anthropic-java` SDK to call Claude. Captures structured data about a service and persists it to the database.

**Responsibilities:**
- Run an interview flow (questions, follow-ups based on responses)
- Validate inputs (required fields, formats, references to existing services)
- Write to the `services` table and related tables

### 2. Database

PostgreSQL 14+ for local prototyping. The schema (see `schema.md`) is designed to be portable to MySQL/MariaDB for production migration.

**Source-of-truth principles:**
- All service inventory data lives here. Confluence is generated output.
- Changes flow DB → Confluence, never the other direction. Confluence edits will be overwritten.

### 3. MCP Server

A Spring Boot application using `spring-ai-starter-mcp-server`. Exposes Atlas data via standardized MCP tools, following a four-tool pattern inspired by OB1:

- `search_services` — find services by name, owner, or attributes
- `get_service_details` — full data for a service including dependencies
- `update_service` — apply changes to a service record
- `list_services` — paginated browse

The MCP server is the integration boundary: any MCP-compatible client (Claude Desktop, Claude Code, the sync agent) can connect.

### 4. Confluence Sync Agent

A scheduled Spring Boot job that reads from the MCP server, transforms data into the Confluence page template (see `confluence-template.md`), and updates Confluence via the Atlassian REST API. Tracks last-sync timestamp per service to enable incremental updates.

## Data Flow

1. **Intake**: Service owner runs the intake agent. Agent interviews them, validates, writes to DB.
2. **Storage**: Data lives in Postgres locally (or MySQL/MariaDB in production).
3. **MCP exposure**: MCP server makes the data discoverable and updatable by AI clients.
4. **Sync**: Sync agent periodically (or on-demand) reads from MCP, generates Confluence page content, writes via the Atlassian API.
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
