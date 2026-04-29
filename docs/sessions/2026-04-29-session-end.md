# Atlas — kickoff for a fresh session

*Saved at end of session 2026-04-29 to seed the next conversation.*

## Snapshot at the start of this session

Where we stood as you may want to set context:

- **Branch state**: `main`, 8 commits ahead of origin (not yet pushed). Latest commit `bede3dc` (M4 — pom.xml ingestion). Earlier session commits in order: `93f6904` (plan + CLAUDE.md §6 reflection rule), `f1293e3` (M1), `fd04f9b` (carry-overs fold-in), `531442c` (M2), `0c37f80` (M2.5 — superseded by M3.5), `ed6e781` (M3), `88bdec2` (M3.5 — append-only retrofit), `bede3dc` (M4).
- **Tests**: 236 active across 4 modules (atlas-domain 33, atlas-intake 91, atlas-mcp 24, atlas-confluence-sync 88), 0 failures, 0 skipped. Up from 159 at start of last session. Re-verify with `mvn test` if needed.
- **Local environment**: three Atlas services registered in the user's local Postgres DB. Migrations V13 through V20 will apply on the next `atlas-intake` boot (Flyway has already applied them under Testcontainers in CI; V13–V18 are applied in the user's DB; V19+V20 may or may not be). To check: `psql atlas -c "SELECT version FROM flyway_schema_history WHERE version >= '19' ORDER BY version;"`. The dogfood ATLAS Confluence space hasn't been re-synced since M2 — sync-side live-verify of M2/M2.5/M3/M3.5/M4 is deferred to user discretion.

## Read these to load context

- **CLAUDE.md** — project intent + working-style rules. §6 (Reflect at Milestone and Phase Boundaries) is new this session and codifies the per-milestone reflection pattern that's been used consistently.
- **docs/plans/2026-04-29-code-driven-documentation.md** — the plan that drove this session, with milestone-by-milestone reflections and resumable summaries appended after each. Read the most recent reflection (M4) for the most current view.
- **docs/handoff/current-state.md** — one-page summary of what is built / tested / deferred. Updated after each milestone of this session; reflects M4 state.
- **docs/decisions.md** — ADRs 1–15 (no ADR added this session; the architectural decisions were milestone-internal and lived in the plan reflections).
- **docs/deferred-decisions.md** — DD-001 through DD-013, with statuses and triggers (no new DDs added in this session, though a "DD-014 candidate" for private-repo GitHub auth is mentioned in carry-overs).
- **docs/roadmap.md** — phase history, pre-this-session position. Not updated this session; the active plan is the source of truth.

## What's been built this session

The arc was a strategic-conversation-then-execution shift from human-interview-driven service registration toward **code-driven documentation**: APIs, tests, and pom-derived metadata auto-refreshed from the actual code.

- **M1 (`f1293e3`)** — Per-source provenance landed (`apis.source`, V13 migration). OpenAPI ingestion produces `source='openapi'` rows from a remote spec. Springdoc on `atlas-intake` exposes `/v3/api-docs`. New `POST /api/code-sync/refresh/{serviceId}` endpoint round-trips against Postgres. Intake-owned endpoints are skipped, not overwritten.
- **M2 (`531442c`)** — Per-endpoint Confluence pages parented under the service page (V14: `apis.confluence_page_id`). Service-page APIs section linkifies to them. `@Operation` annotations on Atlas's own controllers so springdoc emits real summaries.
- **M2.5 (`0c37f80`)** — Endpoint-page orphan cleanup via `apis.deleted_at` soft-delete (V15). **Superseded by M3.5** but the commit stays in history.
- **M3 (`ed6e781`)** — Test-method extraction → "What this service guarantees" page. V16: `services.module_path`, `services.tests_page_id`, `service_test_scenarios`. RepoFileFetcher (GitHub Contents API) + JavaTestExtractor (JavaParser AST) + refreshTests + TestScenariosPageRenderer. Decision recorded: code-sync stays folded into `atlas-intake`.
- **M3.5 (`88bdec2`)** — **Append-only ingestion retrofit.** User clarified mid-session that "don't modify ingested data" was the intent — original artifact preserved, updates decorate, never overwrite. V17 adds `observed_at` + `presence` to `apis` and `service_test_scenarios`. Vendor-split V18 (`db/migration_postgresql/V18.sql` + `db/migration_mariadb/V18.sql`, picked up via `spring.flyway.locations` with `{vendor}` placeholder) drops the unique constraints. Code-sync flips to insert-only; disappearance recorded as tombstones. Live readers use window-function "latest per key" queries. `confluence_page_id` is the only mutable bookkeeping column. **All 219 tests still passed unchanged** — ADR-006's behavior-not-internals rule held under a fundamental schema flip.
- **M4 (`bede3dc`)** — pom.xml ingestion. V19 adds `service_metadata` (append-only from day one) and observation columns on `service_external_deps`. Vendor-split V20 drops the unique. PomParser via Maven's `MavenXpp3Reader` extracts literal declarations only — no property resolution, no parent-chain traversal. New `POST /api/code-sync/refresh-pom/{serviceId}`. Live-verified against atlas-intake's pom: 11 observations (2 metadata + 9 external deps).

## Open work — what we were about to do

**Last user direction**: M4 just closed; user asked for an intro summary for a new session, didn't pick a next direction. The plan's last open milestone is **M5 — phase reflection + interview shrinkage**. M5's natural shape:

1. Count intake-prompted fields at start of phase (~17) vs what they could be after auto-derivation. Audit `InterviewService.java` stage list.
2. Migrate intake to write `service_metadata` rows (instead of/alongside `services.language`/`services.framework`). Maybe deprecate those entity columns.
3. Implement the interview slimming — remove or make-optional stages now auto-derived (APIs, tests, language, framework, external deps). The interview shrinks to the human-only set: ownership, SLA, support contact, business rationale.
4. Stale-intake-cleanup migration: the dogfood DB has `/api/smoke/anthropic` (intake-source) which is dead code post-ADR-013 rename. M5 should clean these up.
5. **Phase-level reflection** (per CLAUDE.md §6): are we still on track for the project's stated goals? Where next — AI-narrated walkthroughs (deferred layer 3 from the original strategic conversation), production-readiness (open DDs), or something else?

## Other findings worth carrying forward

- **`docs/handoff/current-state.md` is current as of M4** — test counts, what's tested per module, active-plan status all reflect post-M4 state. Trust it.
- **Carry-overs** (from the last reflection): pom parent-chain traversal for richer framework/version detection; renderer integration to surface `service_metadata` alongside `services.language`/`services.framework`; private-repo GitHub auth (DD-014 candidate); test-name normalisation polish; sync-side live-verify against real Confluence deferred to user; production-scale index design needs `apis.method`/`apis.path` to become bounded VARCHAR; MariaDB project rule about `VARCHAR(191)` for unique-index columns worth promoting to CLAUDE.md Constraints.
- **Discipline note about TDD red-first** has shown some drift: M4's PomParser was written-then-tested (8/8 passed first run). The discipline holds but isn't always perfectly observed; the behavior-coupled assertions still validate correctness.
- **`com.atlas.codesync` package** is now ~16 main-source classes + ~50 tests in `atlas-intake`. Decision recorded at M3.8 to stay folded; the package boundary is a clean seam for the production team to lift if needed.
- **Strategic note**: the original "shift from interview to code-driven review" goal is mostly delivered. APIs, tests, framework/language, external deps are all auto-derivable. M5 is the moment to make the interview *actually* shrink to match.
- **8 commits ahead of origin, unpushed.** User hasn't asked for a push.
