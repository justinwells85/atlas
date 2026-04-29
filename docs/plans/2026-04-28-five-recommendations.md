# Plan — close the five carried-forward recommendations

Created: 2026-04-28. Tracks the five items carried over from the prior gap-review session. Approved by user; executing autonomously to milestone boundaries.

## Goal

Land the five carried-forward items: doc-honesty pass on `architecture.md` (item 5), close DD-010 (item 1), add a delete path through MCP and intake (item 2), add an architecture-map page to the Confluence space (item 3), and re-evaluate whether confluence-sync should read via MCP (item 4).

## Success criteria

- **Milestone A** — `docs/architecture.md` shows the actual data flow (parallel exposures from `atlas-domain`; MCP as one of two consumers, not in the middle). Intake accepts non-HTTP API surfaces (`MCP`, `gRPC`, `GraphQL`, `AMQP`, `Kafka`) without re-prompt loops. DD-010 marked **resolved** (option 1).
- **Milestone B** — A `delete_service` MCP tool soft-deletes a service by ID. Intake gains a top-level "remove an existing service" entry path (mode chooser on first turn). Re-running confluence-sync removes the orphan page (existing ADR-014 cleanup path).
- **Milestone C** — A new well-known page ("Atlas — Architecture Map") parents under the landing page, regenerated each sync, showing the live service-dependency graph. Renders correctly in dogfood ATLAS space.
- **Milestone D** — A decision recorded (in `decisions.md` or `deferred-decisions.md`) on whether to refactor confluence-sync to read via MCP. If "yes," the refactor lands; if "no," the rationale is captured and item 4 is closed without code.

## Assumptions

- DD-010 takes **option 1** (widen whitelist). Option 2 (`protocol` column + schema migration) is overkill for prototype.
- Soft-delete (per ADR-014) is the deletion semantics for both the MCP tool and the intake removal flow.
- Architecture-map page renders via Confluence's `mermaid-cloud` macro embedded in storage-format XML. Fallback if unavailable: plain ordered list of edges.
- Item 4's pushback is still standing — the plan defers refactor code until milestone D's decision step.
- All work surgical: no incidental cleanup of unaffected code.

## Approach

Four review milestones, each independently reviewable. TDD for every code change: tests first, red, then green. Stop at each milestone for user validation before starting the next.

Doc + DD-010 are paired in milestone A because both are small and unblock everything else by getting the architecture story straight before adding to it.

## Steps

### Milestone A — Doc honesty + DD-010

1. Rewrite `docs/architecture.md`'s "System Overview" diagram and "Data Flow" section: `Service Owners → Intake → atlas-domain DB`, then DB consumed in parallel by `MCP server` (AI clients) and `Confluence Sync Agent` (direct repository reads). Note option to swap sync's reads to MCP as a future architectural decision.
2. Widen `VALID_HTTP_METHODS` in `InterviewService.java` to include `MCP, GRPC, GRAPHQL, AMQP, KAFKA` alongside the HTTP verbs. Update prompt text to match.
3. Mark DD-010 **resolved** in `deferred-decisions.md` (mirror DD-002 / DD-011 closeout style).
4. Run `mvn verify`.

### Milestone B — `delete_service` end-to-end

1. **MCP tool**: add `deleteService(serviceId)` to `ServiceTools.java`. Calls `services.delete(svc)` — Hibernate's `@SQLDelete` handles the soft-delete UPDATE. Audits `service_changes` row with change type `deleted`. Returns the soft-deleted `ServiceDetails`.
2. **Intake stage**: add a top-level mode chooser on first turn ("register a new service" / "remove an existing service"). New stages: `AWAITING_REMOVAL_NAME`, `AWAITING_REMOVAL_CONFIRM`, `REMOVAL_COMPLETE`. Confirmation step shows service name + owner team. On confirm, calls the same soft-delete path. Audits `service_changes` row with change type `deleted` and source `intake-removal`.
3. Tests:
   - MCP: `whenDeleteServiceCalled_thenServiceIsSoftDeleted`, `whenServiceAlreadySoftDeleted_thenDeleteIsIdempotent`, `whenServiceIdMissing_thenIllegalArgument`.
   - Intake: `whenUserChoosesRemoveAndConfirms_thenServiceIsSoftDeleted`, `whenUserDeclinesAtConfirmation_thenServiceIsUntouched`, `whenServiceNameMissing_thenStageRePrompts`.
4. Manual end-to-end check on a throwaway dogfood service.

### Milestone C — Architecture-map page

1. New `ArchitectureMapRenderer` reading all non-deleted services + service-dependency edges. Emits Confluence storage-format with a `mermaid-cloud` macro containing a `flowchart LR` body.
2. `SyncCoordinator` gains a `syncArchitectureMapPage()` step (pattern matches `syncAboutPage()`). Page title: `"Atlas — Architecture Map"`. Parent: landing page.
3. Add link to architecture-map page from landing page preamble + About page.
4. Tests:
   - Renderer: `whenServicesHaveNoDependencies_thenMermaidGraphIsEmpty`, `whenServicesHaveDependencies_thenMermaidEdgesMatch`, `whenServiceIsSoftDeleted_thenItIsExcludedFromGraph`.
   - Coordinator: `syncAll_createsArchitectureMapPage_onFirstRun`, `syncAll_updatesArchitectureMapPage_onSecondRun`.
5. Live verify in dogfood ATLAS space.

### Milestone D — Confluence-sync reads via MCP (decision-first)

1. **Pause for decision before any code**. Tally `SyncCoordinator`'s reads on the repositories (count + shape). For each, ask: would mirroring it as an MCP tool make the AI surface better, or worse?
2. Two outcomes:
   - **Outcome 1 (recommended)**: record an ADR / DD entry — "confluence-sync reads via repositories, not MCP — rationale: MCP is the AI-client surface; sync is an internal consumer; mirroring repo methods as tools dilutes the AI surface." Mark item 4 closed without code.
   - **Outcome 2**: refactor goes ahead. Sub-plan drafted at that point (separate milestone, separate review).
3. Update `architecture.md` (already touched in A1) with one line referencing the resolution.

## Open questions

- **Q1** (Milestone B entry shape): top-level mode chooser vs. separate REST endpoint. Default judgment call: top-level mode chooser, fits existing single-endpoint shape. Will confirm in milestone B report.
- Other questions surface at their respective milestone (mermaid vs SVG for C, decision wording for D).

## Status log

- 2026-04-28 — plan committed.
- 2026-04-28 — milestone A closed (commit `91c3135`).
- 2026-04-28 — milestone B closed (commit `b18b854`). Q1 resolved as separate REST endpoint.
- 2026-04-28 — milestone C closed (commit `5042fd0`). Mermaid via Confluence `code` macro; no cross-links from landing/About.
- 2026-04-28 — milestone D closed. Decision: keep sync reading via `atlas-domain` repositories. Recorded as **ADR-015**.
