# Atlas — Demo Script

A live demo of Atlas, designed for a **mixed audience** (engineering + product + leadership) with the goal of **engineering buy-in** for taking Atlas to production. Core demo is ~15 minutes; Q&A extends to 30.

The pitch is: *"Atlas is an AI-maintained service inventory. Watch it document itself. If we like it, we ship it."* The credibility move is leading with the working artefact, then showing the build honestly — including what's not done.

## Pre-demo checklist

Run through these in the 10 minutes before the meeting:

- [ ] Local Postgres `atlas` DB is up and contains the three Atlas services (run `psql atlas -c "SELECT name FROM services;"` — expect 3 rows: `atlas-intake`, `atlas-mcp`, `atlas-confluence-sync`).
- [ ] `ATLAS_CONFLUENCE_EMAIL` and `ATLAS_CONFLUENCE_API_TOKEN` are set in the demo terminal session (`echo "${ATLAS_CONFLUENCE_EMAIL:0:10}…"` to spot-check).
- [ ] `ANTHROPIC_API_KEY` is set if intake will be live-driven.
- [ ] Confluence space https://justinwellsanderson.atlassian.net/wiki/spaces/ATLAS is open in a browser tab; the three pages exist.
- [ ] Two terminal windows ready (one for `mvn` runners, one for `curl`/`psql`).
- [ ] `docs/deferred-decisions.md` open in editor.
- [ ] (Optional) `mvn verify` already run today so the green output is fresh and quick to re-show.

## Time budget

| Beat | Time | Audience focus |
|---|---|---|
| 1. Set the room | 1 min | Mixed — frame what they're about to see |
| 2. The end state (Confluence walk) | 3 min | Product/leadership see value |
| 3. The intake demo | 5 min | Engineering sees the differentiator |
| 4. Architecture in 60 seconds | 1 min | Engineering credibility |
| 5. Update path | 2 min | Mixed — source-of-truth model |
| 6. What's not done | 2 min | Engineering trust |
| 7. MariaDB / portability story | 1 min | Engineering — the hard part is done |
| 8. Q&A | 5–15 min | Anyone |

## Beat 1 — Set the room (1 min)

> *"Service docs in our org are manually maintained, frequently stale, and live in disconnected places. Atlas auto-generates and maintains a Confluence wiki of service inventory — APIs, dependencies, owners, databases — from a central database. Today I'll show you Atlas documenting itself. The Confluence pages I'm about to show you were written by Atlas, not by me. If we like what we see, we'll talk about taking it to production."*

Don't overstate. The credibility move is the dogfood.

## Beat 2 — The end state (3 min)

**Action**: switch to the Confluence browser tab. Show the ATLAS space index page. Three pages: `atlas-intake`, `atlas-mcp`, `atlas-confluence-sync`. Click into `atlas-intake`.

Walk all 7 sections, ~20 seconds each:

1. **Overview** — Service name, description, owner, status. *"This is the human summary. Auto-generated from structured intake."*
2. **Technical Details** — Language, framework, repo URL (clickable link), deployment. *"Engineering metadata; flows from the intake interview."*
3. **APIs** — `POST /api/intake/turn`, `POST /api/smoke/anthropic`. *"Each row is one endpoint, with auth method and consumer list."*
4. **Dependencies** — *Open this slowly.* Databases (atlas — owned), Upstream Services (none — first in the chain), Downstream Services (atlas-mcp, atlas-confluence-sync), External Dependencies (Anthropic API). *"This is the graph. Click into atlas-mcp's page to see the same graph from its perspective: upstream is atlas-intake, downstream is atlas-confluence-sync. Every edge was captured during intake; every page renders its current view of the graph."*
5. **Data** — Owned databases, data classification (thin note here — fine, free-form metadata).
6. **Operational** — Support contact, SLA, notes about Phase 6/7.
7. **Change History** — Last updated timestamp, audit row showing "intake-agent — created — Service registered via intake interview."

Land on: *"Every word on this page came from a structured interview. No human wrote any of this Confluence content."*

## Beat 3 — The intake demo (5 min)

This is the core engineering moment. Two options: **live** or **walk a recording** of an existing intake. Live is more compelling but riskier; have the recording as fallback.

**Live path**:

```
mvn -pl atlas-intake spring-boot:run
```

Once it's up (port 8080, ~5-8 sec), in the second terminal:

```
curl -s -X POST http://127.0.0.1:8080/api/intake/turn \
  -H 'Content-Type: application/json' -d '{}' \
  | python3 -m json.tool
```

Show the JSON response. Walk the structure:

> *"Server-stateless. The state object you see comes back to me in the next request. No session, no DB-persisted intake-progress — that's a deliberate prototype shortcut documented in ADR-011."*

Drive 3-5 turns by editing the `state` field in subsequent curls. Don't drive the whole interview live (~37 turns = 6 minutes of typing). The point is to show the shape, not the entirety.

If anyone asks "what does the AI actually do here?" — *"The interview state machine is deterministic for most stages. Anthropic Claude is called specifically during description-clarification: when the user's description is ambiguous, Claude asks a follow-up. That's the only LLM-driven turn; the rest is structured prompts."*

## Beat 4 — Architecture in 60 seconds (1 min)

Open `docs/architecture.md` to the ASCII diagram. Read it aloud:

```
[Service Owners] → [Intake Agent] → [Postgres DB]
                                          ↓
                                    [MCP Server]
                                          ↓
                                  [Confluence Sync Agent]
                                          ↓
                                  [Confluence Wiki]
```

> *"Three Spring Boot apps. One library (atlas-domain) for shared JPA entities and Flyway migrations. Each app is deployable independently — that's per ADR-012, so the production team can scale and secure them separately. Today they all run on `127.0.0.1` (ADR-011). Production-hardening their network and auth is on the deferred-decisions list, which we'll see in a minute."*

## Beat 5 — Update path (2 min)

Make the source-of-truth model concrete.

```
psql atlas -c "UPDATE services SET description = '<new description>' WHERE name = 'atlas-intake';"
curl -s -X POST http://127.0.0.1:8082/api/sync/run | python3 -m json.tool
```

(`atlas-confluence-sync` needs to be running first — `mvn -pl atlas-confluence-sync spring-boot:run`. Pre-start it if doing live.)

Refresh the `atlas-intake` Confluence page. New description appears.

> *"DB is the source of truth. If a human edits a Confluence page directly, Atlas overwrites it on next sync. That's the documented contract — captured in `docs/confluence-template.md`. There's also a scheduled sync that runs every 15 minutes by default."*

## Beat 6 — What's not done (2 min)

This is the trust beat for the engineering audience.

Open `docs/deferred-decisions.md`. Scroll the entries. Highlight three:

- **DD-001** — Auth model and `127.0.0.1` binding. *"Prototype shortcut. Production needs SSO + TLS termination. Trigger to revisit: the moment any Atlas service goes off `127.0.0.1`."*
- **DD-002** — Schema portability to MariaDB. *"This was the biggest hard claim in the architecture. Was theoretical for most of the build. Resolved last commit — schema actually runs on both Postgres and MariaDB now, verified by a passing test."*
- **DD-011** — Confluence layout proposal. *"Forward-looking proposal in `docs/confluence-layout.md` for stakeholder review. Currently we render flat at space root; the proposal adds a landing page and parents service pages under it. ~4 follow-up commits when we agree."*

> *"Eleven entries total. Each one has a trigger condition — when to revisit — and a remediation sketch. Whoever takes this to prod has a checklist, not a mystery."*

## Beat 7 — MariaDB / portability story (1 min)

Show test output:

```
mvn verify
# ... look for the green output, especially MariaDBPortabilitySmokeTest
```

> *"108 active tests across the four modules. Includes a MariaDB Testcontainers test that runs the full V1–V10 schema migrations against a real MariaDB instance. The production target is MariaDB on RDS. The schema is now verified portable. Phase 5.5 closed this gap last week."*

## Beat 8 — Q&A

Likely questions and credible answers:

**"Why three apps instead of one?"**
ADR-012. Deployable independently. Production team can scale and secure them separately. Cost: multi-module Maven; one-time setup. Benefit: handoff team has clean service boundaries.

**"How is auth handled?"**
Today: not at all — `127.0.0.1`-only binding (ADR-011) is the security model. Production: DD-001 captures the gap. The remediation sketch points at SSO via Spring Security.

**"What if Confluence is down during a sync?"**
Per-service failures are isolated in `SyncResult`; one bad service doesn't abort the rest. Next sync run retries. No backoff/retry-with-jitter yet — DD-007 captures it as a production concern.

**"How do we delete a service?"**
DB DELETE works and cascades through relationship tables. The Confluence page becomes orphaned — Atlas does not currently delete pages on service-row delete. DD-011 captures auto-delete as a follow-up.

**"What does MCP buy us that direct DB queries don't?"**
A standardised AI-client interface. Claude Desktop or Claude Code can already read the service inventory through MCP — that's tested. When we want a UI ambition or a partner integration, this shape generalises. The Confluence sync agent reads the DB directly today because it lives in the same process; it could equally well call MCP.

**"How do I add my service?"**
Run intake (`mvn -pl atlas-intake spring-boot:run`, then drive `POST /api/intake/turn`). ~10-minute structured interview; page appears on the next sync.

**"Can the AI fabricate fields?"**
No. The interview state machine validates inputs at each stage. Service-to-service references are looked up against the DB — you can't depend on a service that doesn't exist. The AI structures the conversation; the data lives in our Postgres.

**"What about open source / IP?"**
Internal project, written from scratch per CLAUDE.md. OB1 (Open Brain) was architectural inspiration — central DB + MCP server + structured intake — but no code copied.

**"What's still risky for production?"**
The big three: (1) auth/network model (DD-001), (2) CI pipeline (DD-003), (3) the 409-conflict / rate-limiting / retries cluster (DD-006/007/008/009) which only matter at production scale. Plus the Confluence layout proposal awaiting stakeholder feedback (DD-011).

## Backup screenshots (in case live fails)

If `mvn` or the network misbehaves mid-demo, fall back to these. Take fresh ones during the pre-demo checklist:

1. ATLAS Confluence space index — three pages visible.
2. `atlas-intake` page — full scroll, especially Dependencies section.
3. `atlas-mcp` page — Dependencies section showing the inter-module dep edges from the other side.
4. Terminal: `mvn -pl atlas-intake spring-boot:run` started cleanly.
5. Terminal: first curl to `/api/intake/turn` with the JSON response.
6. Architecture ASCII from `docs/architecture.md`.
7. Terminal: `psql UPDATE` + `curl POST /api/sync/run`.
8. Confluence page after refresh — highlight the changed field.
9. `docs/deferred-decisions.md` showing DD-001 in detail.
10. `mvn verify` output — 108 tests, BUILD SUCCESS, including MariaDBPortabilitySmokeTest in green.

## What NOT to demo

These exist but distract from the engineering-buy-in narrative:

- The `update_service` MCP tool (works fine; not what we're selling). If asked, mention it as part of the four-tool MCP surface.
- The intake interview's Anthropic-API hop (only relevant if someone asks "what does the AI do?"). Don't lead with it.
- The `WireMock` test harness (engineering nice-to-have; mention only if Q&A goes to "how is this tested?").

## Closing

> *"Atlas is built end-to-end, dogfooded against itself, and the schema is portable. The deferred-decisions list is the production-readiness checklist. If we want to take this to production, the next conversation is about who owns the handoff and what their environment looks like — `docs/aws-migration-plan.md` is the starting point."*
