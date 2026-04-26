# Atlas

An AI-maintained service documentation system. Atlas collects structured data about services (APIs, dependencies, owners, databases), stores it in a central database, and auto-syncs that data to Confluence pages — keeping service documentation current without manual effort.

## Status

🚧 **Prototype** — local Postgres on Mac, designed for migration to AWS MySQL/MariaDB once proven.

## How It Works

```
[Service Owners] → [Intake Agent] → [Postgres DB] → [MCP Server] → [Sync Agent] → [Confluence]
```

1. Service owners are interviewed by an AI-assisted intake agent.
2. Structured data is captured in a central database.
3. An MCP server exposes the data to AI clients.
4. A sync agent transforms records into Confluence pages and keeps them current.

See [`docs/architecture.md`](docs/architecture.md) for details.

## Quick Start

### Prerequisites

- Java 21 LTS
- Maven 3.9+
- PostgreSQL 16+ (local)
- An Anthropic API key
- (Eventually) An Atlassian/Confluence API token

### Setup

```bash
# Clone
git clone https://github.com/justinwells85/atlas.git
cd atlas

# Start Postgres locally
brew services start postgresql@16
createdb atlas

# Apply schema migrations
mvn flyway:migrate

# Run the application
mvn spring-boot:run
```

## Documentation

| Doc | Purpose |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Context for the Claude Code AI assistant |
| [`docs/architecture.md`](docs/architecture.md) | System overview and component design |
| [`docs/schema.md`](docs/schema.md) | Database schema and design rationale |
| [`docs/confluence-template.md`](docs/confluence-template.md) | Output specification for Confluence pages |
| [`docs/roadmap.md`](docs/roadmap.md) | Phased delivery plan with current status |
| [`docs/decisions.md`](docs/decisions.md) | Architecture decision log |
| [`SETUP.md`](SETUP.md) | First-time setup walkthrough |

## License

To be determined.
