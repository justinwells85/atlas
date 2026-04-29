# Atlas — kickoff for a fresh session

*Saved at end of session 2 of 2026-04-29 to seed the next conversation. The earlier same-day kickoff is `2026-04-29-session-end.md` — read both for full context across the day, but this one supersedes for "where things stand now".*

## Snapshot at the start of this session

Where we stood when this session began:

- **Branch state**: `main`, 9 commits ahead of origin (latest at the time was `2b7e215` — the docs commit tracking session 1's end-of-session note). Session 1 had landed M1 through M4 of the code-driven-documentation plan. Session 1's review session-end note was reviewed and lightly fixed (corrected "7 commits" → "8 commits", added a Flyway-version SQL one-liner) and committed under `2b7e215`.
- **Tests**: 236 active across 4 modules (atlas-domain 33, atlas-intake 91, atlas-mcp 24, atlas-confluence-sync 88), 0 failures, 0 skipped — same as session-1 close.
- **Open question at session start**: M5 (last open milestone of the active plan) — should it ship as planned, in a different shape, or yield to a different priority?

## What was built this session

Two milestones plus the phase close.

- **Interim phase reflection (before M5)** — Per CLAUDE.md §6, the phase-level reflection conventionally lands at end of M5. The user requested it earlier so the reflection's findings could inform whether M5 should ship as planned. The reflection (appended to the active plan after the M4 reflection) found that **renderer integration was the highest-impact unrealized work from M1–M4**: pom-derived metadata was in the DB but Confluence pages weren't surfacing it. Recommended **splitting M5 into M4.5 (renderer integration + stale-intake cleanup) + M5 (interview shrinkage + phase close)**. User agreed.
- **M4.5 (`5067c35`) — Renderer reads service_metadata + stale-intake cleanup.** No new migrations; reused M3.5/M4 schema. Repository: `findExternalDependenciesFor` rewritten as a window-function live-view exposing `source`; new `findStaleIntakeApis`. Renderer: `ServicePageContext` carries `List<ServiceMetadata>`; `renderTechnicalDetails` reads it (pom-xml > intake > entity-column fallback) with `(from pom.xml)` suffix; new Language Version / Framework Version / Build Tool lines. External Dependencies section composes intake+pom rows with conservative artifactId-token matching (length ≥ 4, case-insensitive) collapsing matched pairs into "✓ matches pom: ..." annotation. New `CodeSyncCoordinator.tombstoneStaleIntakeApis(serviceId)` + `POST /api/code-sync/tombstone-stale-intake-apis/{serviceId}` — opt-in per service, NOT auto-fired during refreshOpenApi. **18 new tests, all written red-first.** Discipline (per the M2 reset) held throughout. 236 → 254 tests.
- **M5 (`08667ce`) — Interview shrinkage + phase close.** `InterviewService.next()` no longer prompts for LANGUAGE / FRAMEWORK / HAS_APIS (auto-derived from pom.xml + openapi). Two new prompts added: OPENAPI_SPEC_URL + MODULE_PATH (so code-sync knows where to fetch). `ServiceDraft` + `applyDraftFields` updated. `InterviewStage` enum + `applyInput` cases retained for serialized-state backward compatibility — the LANGUAGE/FRAMEWORK/API_* stages are unreachable from `next()` but still dispatchable if a legacy client payload arrives. Test cleanup: 9 API-section tests deleted (covered behavior no longer exists; CodeSyncCoordinatorTest covers the equivalent openapi-derivation seam); 2 new tests pin the post-M5 shape. **End-of-phase reflection appended to the active plan**. 254 → 247 tests.
- **Field-count delta**: 17 → 16 single-prompt fields. Bigger UX win is the entire APIs sub-loop (up to 10 prompts per endpoint) gone — for atlas-intake (3 endpoints) that's ~30 prompts saved per registration.

## Snapshot now (end of session)

- **Branch state**: `main`, 11 commits ahead of origin (not yet pushed). Latest commit `08667ce` (M5). New session commits: `5067c35` (M4.5), `08667ce` (M5).
- **Tests**: 247 active across 4 modules (atlas-domain 39, atlas-intake 88, atlas-mcp 24, atlas-confluence-sync 96), 0 failures, 0 skipped. Net change −7 from session start (M4.5 added 18, M5 deleted 9, M5 added 2). The drop is honest: 9 tests covered code paths that no longer exist (the APIs sub-section of the interview).
- **Code-driven-documentation phase: CLOSED.** All 7 plan success criteria met. End-of-phase reflection committed.
- **Local environment**: three Atlas services registered in the user's local Postgres DB. Migrations V13–V20 will apply on the next `atlas-intake` boot (Flyway has applied V19+V20 under Testcontainers in CI; V19+V20 may or may not be applied in user's DB — to check: `psql atlas -c "SELECT version FROM flyway_schema_history WHERE version >= '19' ORDER BY version;"`). Sync-side live-verify of M2/M2.5/M3/M3.5/M4/M4.5/M5 against the dogfood ATLAS Confluence space remains deferred to user discretion.

## Read these to load context

- **CLAUDE.md** — project intent + working-style rules. §6 (Reflect at Milestone and Phase Boundaries) is now a fully-worked example: the active plan has 7 milestone reflections + 1 interim phase reflection + 1 end-of-phase reflection.
- **docs/plans/2026-04-29-code-driven-documentation.md** — the now-closed plan. Read the **end-of-phase reflection** at the very bottom for the framing of "what comes next" — Phase 6 (demo + handoff per roadmap), AI-narrated walkthroughs (deferred layer 3), or production-readiness DDs (DD-001, DD-003, AWS).
- **docs/handoff/current-state.md** — one-page summary, updated through M5. Trust it as the post-phase snapshot.
- **docs/sessions/2026-04-29-session-end.md** — session-1 kickoff doc (M1–M4 narrative). Still useful for the early-milestone arc.
- **docs/decisions.md** — ADRs 1–15 unchanged this session; no new ADRs added in M4.5/M5 (the architectural decisions were milestone-internal and lived in the plan reflections).
- **docs/deferred-decisions.md** — DD-001 through DD-013 unchanged this session.
- **docs/roadmap.md** — Phase 5 closed, Phases 6–7 not yet ticked.

## Open work — what to do next

The end-of-phase reflection's **recommended next phase is Phase 6 (Demo and Handoff)** per the roadmap. Rationale (from the reflection):

> The prototype's architectural completeness is at a peak right now. The append-only model is in place, the renderer composes intake + code-sync data, the interview is materially slimmer, the dogfood is honest. This is the moment to demo. Adding more features before stakeholder buy-in risks investing in directions the production team won't prioritise; production-readiness work without buy-in is wasted. Phase 6 unlocks the right next conversation.

Phase 6 deliverables, all already substantially drafted:

1. **Demo to stakeholders** — `docs/demo-script.md` exists; rehearse and run.
2. **`docs/deferred-decisions.md`** — DD-001 through DD-013 already documented; review for completeness post-phase before the demo.
3. **`docs/aws-migration-plan.md`** — exists; review the 10 `[org-decision]` items for currency.
4. **Handoff packet** — `docs/handoff/` is current as of M5.

**Alternative directions** if priority has shifted:

- **AI-narrated walkthroughs (deferred layer 3)**: technically tractable now — the data model has APIs + tests + deps + metadata, all queryable. A narration tool ("here's a service in 3 paragraphs, derived from its data") is a small Spring Boot endpoint that passes live-view rows through an LLM. Risk: speculative until demo lands.
- **Production-readiness DDs (DD-001 auth, DD-003 CI, AWS provisioning)**: real work, but should follow Phase 6. Without stakeholder green-light, production-readiness investment may target the wrong things.

## Other findings worth carrying forward

- **Discipline drift on TDD red-first is recoverable but recurring.** M1 drifted (tests-and-code-together), M2 corrected, M3 held, M4 drifted again, M4.5 + M5 held. Pattern: discipline drifts when work feels routine; holds when work introduces new shape or scale-up. Worth promoting "red-first applies even on apparently-routine work" to a CLAUDE.md guideline if it drifts again.
- **Pre-existing bug fixed silently in M4.5**: the old `findExternalDependenciesFor` ignored `presence` and would have surfaced tombstoned external-dep rows once any disappeared. The dogfood didn't trip this because no external-dep tombstones existed yet. Worth tagging as a near-miss: M4 introduced `presence` columns but didn't re-audit every read path. Future schema-shape changes should explicitly enumerate readers and verify each one was updated.
- **Append-only model has ratcheted up in value.** It started as an M3.5 retrofit ("don't modify ingested data"), but by M4.5 the live-view query shape was uniform across `apis`, `service_metadata`, `service_external_deps`, and `service_test_scenarios`. Future writers (docker compose, Terraform) plug into the same vocabulary without coordination. This is the most portable architectural lesson of the phase.
- **Per-source provenance turned out to be the right hinge.** Code-sync writers and intake writers each tag their rows. Composition happens at render time, not at write time. The renderer's M4.5 intake+pom external-deps composition is the visible payoff.
- **Code-sync stayed folded into atlas-intake (M3 decision) — held up through M4, M4.5, M5.** No friction. The package boundary is clean enough that a production team could lift it later if they wanted clearer ownership.
- **MCP `update_service` is now the legitimate "human override" path for `services.language` / `services.framework`.** Intake no longer captures these; MCP still writes them via `setLanguage()`/`setFramework()`. The renderer's M4.5 entity-column fallback exists specifically for this path. Don't be tempted to remove it without first removing the MCP write paths too.
- **Carry-overs for Phase 6 (or a post-Phase-6 cleanup pass)**: pom parent-chain traversal (M4 limitation — atlas-intake's framework_version came up empty); private-repo GitHub auth (DD-014 candidate); test-name normalisation polish; MariaDB project rule (`VARCHAR(191)` for unique-index columns) promotion to CLAUDE.md Constraints; `ServicePageContext` builder pattern (fixture sprawl is real); unreachable LANGUAGE/FRAMEWORK/API_* applyInput cases (~120 lines of defensive code); `otherRows` defensive branch in `renderExternalDependencies` (YAGNI candidate).
- **Live-verify against the dogfood remains deferred.** Suggested cold-start verification before any Phase 6 demo:
  ```
  POST /api/code-sync/refresh-pom/{atlas-intake-id}
  POST /api/code-sync/refresh/{atlas-intake-id}
  POST /api/code-sync/tombstone-stale-intake-apis/{atlas-intake-id}
  POST /api/sync/run
  ```
  Expected: language/framework with "(from pom.xml)" suffix, External Dependencies showing 2 intake + ~9 pom rows with composition, `/api/smoke/anthropic` gone from APIs section.
- **11 commits ahead of origin, unpushed.** User hasn't asked for a push.
