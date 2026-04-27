# Atlas — Local Developer Setup

Get the prototype running end-to-end on a Mac, ~30 minutes from clone to first Confluence page.

## Prerequisites

| Tool | Version | Why |
|---|---|---|
| **Java** | 21 LTS | Spring AI 1.1.x requires it; ADR-005. |
| **Maven** | 3.9+ | Build tool. |
| **PostgreSQL** | 14+ | Local DB. ADR-003. |
| **Docker** | running | Testcontainers spins up Postgres + MariaDB instances during `mvn verify`. |
| **Python** | 3.10+ | The intake-driver script that automates a service interview. |
| **An Anthropic API key** | — | Set as `ANTHROPIC_API_KEY` env var. atlas-intake calls Claude during description-clarification turns. |
| **A Confluence Cloud space + API token** | — | Sync target. Set `ATLAS_CONFLUENCE_EMAIL` and `ATLAS_CONFLUENCE_API_TOKEN` env vars; configure the base URL and space key in `atlas-confluence-sync/src/main/resources/application.properties`. |

On Mac:

```
brew install openjdk@21 maven postgresql@14
brew services start postgresql@14
# Docker Desktop installed separately
```

## Environment variables

Add to `~/.zshrc` (or `~/.bashrc`):

```
export ANTHROPIC_API_KEY="sk-ant-…"
export ATLAS_CONFLUENCE_EMAIL="you@example.com"
export ATLAS_CONFLUENCE_API_TOKEN="…"
```

Then `source ~/.zshrc`. Verify the shell sees them:

```
echo "ANTHROPIC_API_KEY len=${#ANTHROPIC_API_KEY}"
echo "ATLAS_CONFLUENCE_EMAIL=$ATLAS_CONFLUENCE_EMAIL"
echo "ATLAS_CONFLUENCE_API_TOKEN len=${#ATLAS_CONFLUENCE_API_TOKEN}"
```

Empty values mean the export didn't take. Check the rc file. Note: env vars in `~/.zshrc` are only available in interactive shells — non-interactive subprocesses (e.g., a CI runner) need them set differently.

## Clone & build

```
git clone https://github.com/justinwells85/atlas.git
cd atlas
```

Create a local Postgres DB:

```
psql postgres -c "CREATE DATABASE atlas OWNER \"$USER\";"
```

Build and run all tests:

```
mvn verify
```

You should see `BUILD SUCCESS` and `Tests run: 108, Failures: 0` (current as of Phase 5.5; will grow). Testcontainers downloads `postgres:14` and `mariadb:10.11` images on first run.

## Run the apps

Each Spring Boot app is its own Maven module. Start one in its own terminal:

```
# Terminal 1 — atlas-intake (port 8080)
mvn -pl atlas-intake spring-boot:run

# Terminal 2 — atlas-mcp (port 8081)
mvn -pl atlas-mcp spring-boot:run

# Terminal 3 — atlas-confluence-sync (port 8082)
mvn -pl atlas-confluence-sync spring-boot:run
```

All three bind to `127.0.0.1` only (ADR-011 — auth is deferred until the binding moves off localhost; DD-001).

## First end-to-end run

Two paths to populate a service:

### Path A — Run the intake interview manually (the demo path)

In a new terminal:

```
curl -s -X POST http://127.0.0.1:8080/api/intake/turn \
  -H 'Content-Type: application/json' -d '{}' \
  | python3 -m json.tool
```

The first response carries `state` and `question`. To answer, send the next request with `state` (echoed verbatim) and `userInput`:

```
curl -s -X POST http://127.0.0.1:8080/api/intake/turn \
  -H 'Content-Type: application/json' \
  -d '{"state": <previous-state-object>, "userInput": "billing-service"}' \
  | python3 -m json.tool
```

Loop until `complete: true` (typically ~25–60 turns depending on how much relationship data you provide).

### Path B — Drive intake from a Python script

`/tmp/intake_driver.py` (committed neither in repo nor in this packet — was a prototype demo helper, see git log around the `feat: Phase 5.5 M1` commit). The pattern is: hit `/api/intake/turn` with the `state` field round-tripped, choose answers based on `state.stage`. Useful for repeat demo runs against the dogfood services.

### Trigger a sync

Once a service is in the DB:

```
curl -s -X POST http://127.0.0.1:8082/api/sync/run | python3 -m json.tool
```

Expected response: `{"successCount": N, "failureCount": 0, "failures": []}`. Confluence pages now exist in your configured space.

## Verifying state

```
psql atlas -c "SELECT name, confluence_page_id, last_synced_to_confluence FROM services;"
```

Each row should have a `confluence_page_id` and a recent `last_synced_to_confluence` timestamp. The page is at `https://<your-site>.atlassian.net/wiki/spaces/<KEY>/pages/<confluence_page_id>`.

## Resetting the local environment

If migrations fail to apply (because you've edited a committed migration in violation of the rule — except per the documented Phase-5.5 exception in `docs/schema.md`), Flyway will refuse to start due to a checksum mismatch. The cleanest reset:

```
psql postgres -c "DROP DATABASE atlas;"
psql postgres -c "CREATE DATABASE atlas OWNER \"$USER\";"
```

Next `mvn spring-boot:run` re-applies V1–V10 from scratch.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `mvn verify` fails on `MariaDBPortabilitySmokeTest` | Docker not running, or Testcontainers can't pull `mariadb:10.11`. |
| App starts but every Confluence request returns 401 | `ATLAS_CONFLUENCE_EMAIL` or `ATLAS_CONFLUENCE_API_TOKEN` missing in the shell that ran `mvn`. Re-`source ~/.zshrc` first. |
| Intake returns 500 with `Anthropic` in the error | `ANTHROPIC_API_KEY` missing or invalid. Curl `/api/smoke/anthropic` to test the connection independently. |
| `Flyway: checksum mismatch` on startup | Migrations have been edited after a previous run. Drop+recreate `atlas` DB (see "Resetting the local environment"). |
| Sync reports `successCount` matching the service count, but Confluence pages don't appear | Wrong space key in `application.properties`. Check `atlas.confluence.space-key`. |

## Where the code lives

```
atlas/
├── atlas-domain/                # JPA entities, repositories, Flyway migrations
├── atlas-intake/                # AI-assisted interview REST service (port 8080)
├── atlas-mcp/                   # MCP server exposing service inventory (port 8081)
├── atlas-confluence-sync/       # Sync agent (port 8082)
└── docs/                        # All Atlas documentation
    ├── handoff/                 # This packet
    ├── architecture.md
    ├── decisions.md             # ADRs
    ├── deferred-decisions.md    # Production-readiness checklist
    ├── schema.md
    ├── confluence-template.md   # Per-page output spec
    ├── confluence-layout.md     # Proposed space layout (DD-011)
    ├── aws-migration-plan.md
    ├── demo-script.md
    └── roadmap.md
```
