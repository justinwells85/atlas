# Plan — Phase 5.7 — Spring Integration drill-down (flows + channels + gateways + endpoints)

Created: 2026-04-30, immediately after Phase 5.6 close. Successor phase before the (revised) Phase 6 stakeholder demo.

## Why this phase exists

The user's organization runs ~12 services that use Spring Integration as their core framework. After Phase 5.6 closed, this surfaced as the production-fit gap: Atlas's L5 Beans page captures Spring stereotype classes, but Spring Integration services' most interesting structure lives in `IntegrationFlow` DSL chains, message channels, and `@MessagingGateway` interfaces — none of which are visible to the current extractors. A demo targeted at one of those services would render thin pages with little to say. The org-wide rollout argument depends on Atlas being able to describe an SI service at the same granularity it describes a vanilla Spring service.

## Goal

Extend Atlas so it can describe a Spring Integration service at granular detail: not "what classes exist" but "what channels feed what endpoints, what flows go from where to where, what handlers process what messages." Demo target: re-point Atlas at one of the 12 work-org SI services, generate a granular Confluence site, and use that artifact to win buy-in for org-wide rollout to the other 11 services and beyond.

## Success criteria

1. **Annotation-driven endpoints captured.** Every method annotated `@ServiceActivator` / `@Transformer` / `@Filter` / `@Splitter` / `@Aggregator` / `@Router` / `@Bridge` / `@InboundChannelAdapter` / `@Gateway` is recorded with its input + output channel attributes and the enclosing class.
2. **Messaging gateway interfaces captured.** Every interface annotated `@MessagingGateway` is recorded with all its methods' signatures + per-method `@Gateway` channel mappings (request channel, optional reply channel).
3. **Channel beans captured.** Every `@Bean` method returning `MessageChannel` (or a Spring Integration subclass — DirectChannel, PublishSubscribeChannel, QueueChannel, ExecutorChannel) is recorded with its bean name + channel type.
4. **IntegrationFlow chains captured.** Every `@Bean` method returning `IntegrationFlow` is recorded as an ordered list of nodes (one per builder call: `.from`, `.transform`, `.filter`, `.handle`, `.route`, `.split`, `.aggregate`, `.channel`, `.to`, `.gateway`, `.bridge`, `.enrich`, etc.) with channel and handler references.
5. **Cross-file channel resolution works.** A flow that says `.from(orderChannel())` resolves the method reference against the channel-bean index built in a project-level pass; flows that use string literals (`.from("orders")`) record the literal; unrecognized references stay as opaque method names. **Two-phase extraction is the architectural change.**
6. **Per-service Flows page renders all four sets.** Headings: Integration Flows (with mermaid graph per flow), Channels, Messaging Gateways, Annotation-driven endpoints. One block per flow with a node table beneath the graph.
7. **L2 Section 8 "Internals" gains a Flows sub-bullet** linking the new page; thin note when the service has no Flows page (i.e. not an SI service).
8. **Append-only-with-presence pattern carries over.** The four new tables (V25–V28) follow the same shape as `service_modules` / `service_beans` — `observed_at`, `presence`, `source='source-tree'`, `confluence_page_id` (where applicable), latest-per-key live-view queries.
9. **MariaDB portability.** Every new migration applies cleanly under both Postgres + MariaDB via the existing portability smoke test. Vendor splits used only when truly needed.
10. **Demo-grade rendering.** Re-pointing Atlas at the target work SI service produces a Confluence site whose granularity tells the audience what the service does without them having to read the source. The demo unlocks support for rolling Atlas out to the other 11 services.

## Assumptions

- **First-party Spring Integration only.** The target services use Spring Integration's own annotations + DSL. Custom org-internal abstractions that wrap Spring Integration (e.g., a homegrown `OrgFlowBuilder.from(...)`) are out of scope; M0 verifies whether they exist and how invasive they are.
- **AST-only extraction.** No compilation, no runtime classpath, no reflection. Same constraint as `JavaBeanExtractor` / `JavaTestExtractor`. Cross-file resolution happens through a coordinator-level index built from a project-wide first pass, not via classpath inspection.
- **Java language level configurable.** `JavaParser` is now pinned to `JAVA_21` (post-M4 fix). If a target service is on Java 17 or 11, the language level can be set per-service; M0 verifies the target's level.
- **IntegrationFlow chains are mostly linear.** Routers and splitters introduce branches; the renderer needs to handle them in mermaid. Branches that go through lambda-typed routing functions (`.route(payload -> ...)`) are recorded as "lambda routing" with the lambda body opaque — extracting routing semantics from arbitrary lambda bodies is out of scope.
- **`@Bean MessageChannel` definitions are static.** Channels created at runtime via factories or reflection are not captured.
- **Per-source provenance + append-only is the design rule.** Same vocabulary as Phase 5.6: `source='source-tree'` for AST-extracted rows, `observed_at`/`presence` columns universal.
- **Private-repo auth (DD-014) may surface as an M0 blocker.** If the demo target is in a private repo, M0's first deliverable is auth strategy + working `RepoFileFetcher`. Public-repo fallback: the user can copy a representative SI service to a sandbox public repo for the duration of the phase.
- **Demo Confluence space.** Phase 5.6 used the ATLAS sandbox space; this phase defaults to the same space until rendering granularity is validated, then optionally re-targets to a work-org space.
- **The four-module architecture stays.** No new module, no new app. The new extractors + table + renderer slot into the existing modules per Phase 5.6 conventions.

## Approach

Five review milestones. Same red-first TDD as Phase 5.6 (the discipline is now well-rehearsed across two phases). Each closes with a reflection + resumable summary per CLAUDE.md §6.

Order is smallest-cost to largest, closing with demo prep:

- **M0 — Discovery + scope resolution** (small, mostly user-collaboration)
- **M1 — Annotation endpoints + gateway interfaces** (medium)
- **M2 — Channels + IntegrationFlow DSL parser** (largest, the technical heart)
- **M3 — Rendering + L2 Internals integration** (medium)
- **M4 — Demo prep + close** (small, demo-focused)

## Milestones

### M0 — Discovery + scope resolution

**Goal**: enough context on the target service to start M1's red phase with confidence. No code, no tests. Deliverable is a written note.

1. Resolve **Open question 1** (below): which work-org service, what repo access. This unblocks everything else.
2. Inspect the target service's source. Inventory:
   - Annotation-driven vs DSL-driven mix (60/40? 90/10?)
   - Channel naming convention (camelCase bean names, hyphenated string names, both)
   - Custom DSL extensions (homegrown `OrgFlowBuilder.from(...)` style — if extensive, scope expands)
   - Java language level (21? 17? 11?)
   - Use of `@MessagingGateway` interfaces — how many, how complex
3. Scope DD-014 if target is private:
   - Personal Access Token via env var — simplest, what to do first
   - GitHub App installation token — reusable, more setup
   - Pre-fetch source to a sandbox public repo as a workaround
4. Pick demo Confluence space: ATLAS sandbox vs work-org. Likely sandbox for M1–M3, optional re-target at M4.
5. Capture findings in `docs/notes/2026-XX-XX-si-discovery.md` — short note (≤2 pages).

**Reflection at M0 boundary**: scope confirmed/refined; M1's open questions stable.

---

### M1 — Annotation endpoints + gateway interfaces

**Goal**: capture annotation-driven messaging endpoints + gateway interfaces in DB. Extractors + coordinator + REST endpoint. No rendering yet.

**Persistence shape (V25 + V26)**:
- `service_messaging_endpoints` — append-only from day one. Columns: `id`, `service_id`, `module_path`, `package_name`, `class_name`, `method_name`, `endpoint_type` (CHECK constrained to the 9 known types), `input_channel`, `output_channel`, `javadoc_summary`, `source='source-tree'`, `observed_at`, `presence`.
- `service_messaging_gateways` — append-only. Columns: `id`, `service_id`, `module_path`, `package_name`, `interface_name`, `methods` (JSON-as-TEXT array of `{method_name, signature, request_channel, reply_channel}`), `javadoc_summary`, `source`, `observed_at`, `presence`.

**Tests (red-first)**:
- `SpringIntegrationAnnotationExtractor`:
   - `whenSourceHasServiceActivator_thenEndpointRecordCarriesInputAndOutputChannels`
   - `whenSourceHasTransformerWithoutOutputChannel_thenOutputChannelIsNull`
   - `whenSourceHasFilterSplitterAggregatorRouter_thenEachTypeRecorded`
   - `whenSourceHasInboundChannelAdapter_thenPollerInfoSkippedButEndpointRecorded`
   - `whenMethodHasJavadoc_thenFirstSentenceCapturedAsSummary`
   - `whenSourceHasNoSpringIntegrationAnnotations_thenEmptyResult`
- `SpringIntegrationGatewayExtractor` (separate from JavaBeanExtractor — interfaces remain skipped there):
   - `whenInterfaceAnnotatedMessagingGateway_thenInterfaceRecorded`
   - `whenGatewayMethodHasGatewayAnnotation_thenChannelMappingCaptured`
   - `whenGatewayMethodHasNoGatewayAnnotation_thenStillRecordedWithoutChannelMapping`
   - `whenSourceIsClassNotInterface_thenSkipped`
- Repository tests for V25 + V26 (live-view + presence-absent + page-id-carry-forward where applicable).
- `CodeSyncCoordinator.refreshMessaging(serviceId)`:
   - `whenServiceHasRepoUrl_thenEndpointsAndGatewaysFetchedAndPersisted`
   - `whenAnnotationDisappearsFromSourceTree_thenLatestObservationMarksItAbsent`
   - `whenServiceHasNoRepoUrl_thenRefreshIsNoop`

**Steps**:
1. Migrations V25 + V26 (portable shapes, vendor splits only if needed).
2. `MessagingEndpointRecord` + `MessagingGatewayRecord` (mirrors `BeanRecord`).
3. `SpringIntegrationAnnotationExtractor` (per-file, stateless).
4. `SpringIntegrationGatewayExtractor` (per-file, stateless; recognizes interfaces).
5. `ServiceMessagingEndpoint` + `ServiceMessagingGateway` JPA records, repository methods (live-view + insert + tombstone + clear).
6. Extend `CodeSyncCoordinator` with `refreshMessaging(serviceId)` walking `{module_path}/src/main/java`.
7. New REST endpoint `POST /api/code-sync/refresh-messaging/{serviceId}`.
8. (Green) tests pass.
9. (Refactor) if extractors share helpers (annotation-name resolution, javadoc parsing), promote to a shared helper class.

**Reflection at M1 boundary**.

---

### M2 — Channels + IntegrationFlow DSL parser

**Goal**: capture `@Bean MessageChannel` definitions + `@Bean IntegrationFlow` chains with cross-file channel resolution. **Largest milestone of the phase** — the DSL parser is the technical heart.

**Persistence shape (V27 + V28)**:
- `service_message_channels` — append-only. Columns: `id`, `service_id`, `module_path`, `package_name`, `class_name`, `bean_method_name`, `channel_name` (the bean name), `channel_type` (CHECK: Direct/PubSub/Queue/Executor/Other), `config_summary`, `source`, `observed_at`, `presence`.
- `service_integration_flows` — append-only. Columns: `id`, `service_id`, `module_path`, `package_name`, `class_name`, `bean_method_name` (the @Bean method that returns IntegrationFlow), `flow_nodes` (JSON-as-TEXT — see shape below), `javadoc_summary`, `source`, `observed_at`, `presence`.

**`flow_nodes` JSON shape** (one entry per builder call):
```json
[
  {"step": 0, "op": "from", "channelRef": "orderChannel", "refKind": "bean-method", "channelType": "Direct"},
  {"step": 1, "op": "transform", "handlerClass": "OrderTransformer", "handlerMethod": "transform"},
  {"step": 2, "op": "filter", "expression": "payload.amount > 100", "expressionKind": "spel"},
  {"step": 3, "op": "route", "kind": "lambda", "branches": []},
  {"step": 4, "op": "handle", "handlerClass": "NotificationHandler", "handlerMethod": null},
  {"step": 5, "op": "channel", "channelRef": "processedChannel", "refKind": "bean-method", "channelType": "PubSub"}
]
```

Where:
- `op`: builder method name (`from` / `transform` / `filter` / `handle` / `route` / `split` / `aggregate` / `channel` / `to` / `gateway` / `bridge` / `enrich` / `wireTap` / `log` / `nullChannel` / etc.)
- `channelRef`: resolved channel name post project-level resolution; literal string when DSL passed a string; null if unresolvable
- `refKind`: `"bean-method"` | `"string"` | `"opaque"`
- `handlerClass` / `handlerMethod`: present when a method ref or class ref is passed; null for lambda handlers
- `kind`: present on routing-style ops where the arg is a lambda — `"lambda"`, `"expression"`, etc.
- Anything not recognized: opaque text capture under `args: "..."`

**Two-phase project-level extraction** (the architectural change):
1. **Phase 1 (per-file, stateless)**: scan all source files in the source tree. For each file, emit:
   - All `@Bean MessageChannel` definitions found (`channel_name`, `channel_type`, etc.)
   - All `@Bean IntegrationFlow` definitions found, but as **raw AST method-body holders** (not yet resolved). Each carries the body's MethodCallExpr chain.
2. **Phase 2 (coordinator-orchestrated)**: build a `Map<String, ChannelInfo>` from phase 1's channel beans (key = bean method name). For each IntegrationFlow holder from phase 1, walk its chain and resolve each `from(beanRef())`, `.channel(beanRef())`, etc. against the index. Output: ordered `List<FlowNode>` per flow.

The coordinator (`CodeSyncCoordinator.refreshMessaging`) becomes the orchestrator. The two extractors stay per-file pure functions.

**The DSL chain walker** (the hardest piece):
- Input: a `MethodDeclaration` whose body is `return IntegrationFlows.from(...).X(...).Y(...).get();` or `IntegrationFlow flow = ...; return flow;`
- Walks the chain of `MethodCallExpr` nodes, innermost first (which corresponds to leftmost in the fluent chain), turning each into a `FlowNode`
- Recognizes Spring Integration's first-party builder methods (a hard-coded set of ~25 names — `OP_NAMES` constant)
- For each call's args:
   - String literal → `refKind="string"`, `channelRef=<the literal>`
   - `MethodCallExpr` referencing a known channel-bean method → `refKind="bean-method"`, `channelRef=<bean method name>`, `channelType=<from index>`
   - `LambdaExpr` → `refKind="opaque"`, `kind="lambda"`
   - Class literal (`SomeHandler.class`) → `handlerClass="SomeHandler"`
   - Method ref (`SomeHandler::process`) → `handlerClass="SomeHandler"`, `handlerMethod="process"`
   - Anything else → opaque text capture
- Recognises sub-flows (`.handle(IntegrationFlows.from(...).get())`) and recurses one level deep; records nested flow nodes inline with a `subflow: true` marker. Deeper nesting collapses to "...complex subflow..." rather than exploding the page.

**Tests (red-first — biggest set of the phase)**:
- `MessageChannelExtractor`:
   - `whenSourceHasDirectChannelBean_thenChannelRecorded`
   - `whenSourceHasPublishSubscribeChannelBean_thenChannelRecorded`
   - `whenSourceHasQueueChannelBean_thenChannelRecorded`
   - `whenSourceHasExecutorChannelBean_thenChannelRecorded`
   - `whenChannelMethodHasJavadoc_thenSummaryCaptured`
   - `whenChannelMethodReturnsCustomChannelSubclass_thenChannelTypeIsOther`
- `IntegrationFlowExtractor` chain-walker:
   - `whenFlowChainsFromTransformAndHandle_thenAllNodesRecordedInOrder`
   - `whenFromUsesChannelBeanMethodReference_thenResolvedAgainstChannelIndex`
   - `whenFromUsesStringChannelName_thenRecordedAsLiteralRef`
   - `whenFromUsesUnknownMethod_thenRecordedAsOpaque`
   - `whenFlowHasFilterWithSpel_thenExpressionTextCaptured`
   - `whenFlowHasRouteWithLambda_thenKindIsLambda`
   - `whenFlowHasHandleWithMethodReference_thenHandlerClassAndMethodCaptured`
   - `whenFlowHasHandleWithLambda_thenLambdaBodyOpaque`
   - `whenFlowHasNestedSubFlow_thenSubFlowNodesRecordedWithSubflowMarker`
   - `whenFlowHasDeeplyNestedSubFlow_thenInnerCollapsesToPlaceholder`
   - `whenFlowMethodHasJavadoc_thenSummaryCaptured`
- Coordinator project-level pass:
   - `whenServiceHasMixedAnnotationAndDslEndpoints_thenAllPersisted`
   - `whenChannelBeanDisappears_thenItsTombstonedAndDependentFlowsRecordOpaqueRef`
   - `whenServiceHasFlowReferencingChannelInDifferentFile_thenResolvedAcrossFiles`
- Repository tests for V27/V28 (standard live-view + tombstone set).

**Steps**:
1. Migrations V27 + V28.
2. `ChannelRecord` + `FlowRecord` + `FlowNode` types.
3. `MessageChannelExtractor` (per-file, simple — `@Bean` methods returning `MessageChannel` subclasses).
4. `IntegrationFlowExtractor` (per-file, stateless — produces `RawFlow` carrying the unresolved AST chain).
5. `FlowChainWalker` — given a `RawFlow` and a channel index, produces `List<FlowNode>`. The chain walking is the meat.
6. `ServiceMessageChannel` + `ServiceIntegrationFlow` JPA records + repository methods.
7. Extend `CodeSyncCoordinator.refreshMessaging` with the two-phase logic. Phase 1: collect channel + flow records per file. Phase 2: resolve flows against the channel index, persist append-only with tombstoning.
8. (Green) tests pass.
9. (Refactor) likely: split `FlowChainWalker` from `FlowNodeFactory`; extract `OP_NAMES` and `BUILDER_METHODS` constants. If the walker exceeds ~400 lines, the chain-traversal vs. node-construction split is the natural seam.

**Reflection at M2 boundary**.

---

### M3 — Rendering + L2 Internals integration

**Goal**: per-service Flows page renders all four data sets with mermaid graphs; L2 Section 8 "Internals" gains a Flows sub-bullet.

**New page**: `{service.name} — Flows`. Parented under the service. Sections:
- **Heading** — `{service.name} — Flows`.
- **Preamble** — one paragraph: "Each block below is a Spring Integration construct found in this service. Flows trace the message paths; channels are the seams; gateways are the entry points; annotation-driven endpoints are the per-method handlers."
- **Integration Flows** — one block per `IntegrationFlow` `@Bean`. Block contents: bean method name + javadoc summary (when present) + a mermaid `flowchart LR` graph showing nodes + channels + handlers + a node table (step / op / channel / handler / kind).
- **Channels** — table: `channel_name` / `channel_type` / `defining_class.method` / `used_by_flows` (rendered as a comma-separated list of flow-bean names that reference this channel, derived from the flow_nodes JSON).
- **Messaging Gateways** — one block per `@MessagingGateway` interface. Block contents: interface FQN + javadoc summary + per-method table (method signature / request channel / reply channel).
- **Annotation-driven endpoints** — table: `class.method` / `endpoint_type` / `input_channel` / `output_channel` / `javadoc_summary`.
- **Back-link** — to the parent service page.

When a service has none of the above (i.e. not a Spring Integration service), the page renders a thin note and is still created — same convention as the Beans/Tests pages.

**L2 Section 8 update**: add a Flows sub-bullet between Code index and Tests; thin note "No flows documented yet." when `flowsPageUrl` is null. Service page context gains a `flowsPageUrl` field; legacy 14-arg constructor (added in M4 of Phase 5.6) gets a 15-arg replacement and itself becomes a compatibility shim. Same shim pattern as before.

**Tests (red-first)**:
- `IntegrationFlowsPageRendererTest`:
   - `whenFlowHasLinearChain_thenMermaidGraphRendersInOrder`
   - `whenFlowHasRouterWithKnownBranches_thenMermaidShowsBranches`
   - `whenFlowHasLambdaRouter_thenMermaidShowsOpaqueRoutingNode`
   - `whenChannelIsDefinedButUnusedByAnyFlow_thenStillAppearsInChannelTableWithEmptyUsedBy`
   - `whenServiceHasGatewayWithMultipleMethods_thenAllMethodsListed`
   - `whenServiceHasNoFlows_thenPageRendersThinNote`
- `ServicePageRendererTest`:
   - `whenServiceHasFlowsPageUrl_thenInternalsBulletLinksIt`
   - `whenServiceHasNoFlowsPageUrl_thenInternalsBulletShowsThinNote`
- `SyncCoordinatorIntegrationTest`:
   - `whenServiceFirstAcquiresFlowsPage_thenItIsCreatedAndIdSetOnServiceRow`
   - `whenServiceLosesAllMessagingArtifacts_thenFlowsPageIsDeletedAndIdNulled`

**Steps**:
1. Migration V29: `services.flows_page_id` (mirrors `services.beans_page_id` exactly).
2. `IntegrationFlowsPageContext` + `IntegrationFlowsPageRenderer`.
3. (Red) renderer tests.
4. (Red) ServicePageRenderer Internals tests for the new bullet.
5. (Red) SyncCoordinator lifecycle tests.
6. Implement the renderer (incl. mermaid generation — reuse the macro pattern from `ArchitectureMapRenderer`).
7. Update `ServicePageRenderer.renderInternals` to add the Flows sub-bullet.
8. Add `SyncCoordinator.syncFlowsPage` mirroring `syncBeansPage`.
9. Plumb `flowsPageUrl` through `ServicePageContext` (15th component + 14-arg secondary constructor).
10. (Green) tests pass.
11. Live verification: `POST /api/code-sync/refresh-messaging/{atlas-intake-id}` and `POST /api/sync/run` against the dogfood — atlas-intake has no SI code so the page renders a thin note (proves the empty-service path).

**Reflection at M3 boundary**.

---

### M4 — Demo prep + close

**Goal**: target work SI service rendered end-to-end in Confluence; demo script updated; phase reflection.

1. (If DD-014 work was deferred at M0) finalize private-repo auth strategy and ship.
2. Re-point Atlas at the target service: register it via intake, set `module_path` correctly, set `repo_url`.
3. Run the full code-sync sequence:
   - `POST /api/code-sync/refresh-pom/{id}`
   - `POST /api/code-sync/refresh/{id}` (OpenAPI — likely empty for SI services without REST surface)
   - `POST /api/code-sync/refresh-tests/{id}`
   - `POST /api/code-sync/refresh-beans/{id}`
   - **`POST /api/code-sync/refresh-messaging/{id}`** (new in Phase 5.7)
   - `POST /api/sync/run`
4. **Granularity check**: walk through the rendered Confluence pages manually. Does the L2 → Flows page → integration-flow block tell the audience what the service does? Are channels and handlers identifiable? Is the mermaid graph readable?
5. Iterate on rendering polish (~1 day reserve). Likely candidates: mermaid layout direction, node labels, handler-class link-out, channel name truncation.
6. Update `docs/demo-script.md` with the new SI walkthrough section.
7. Optional: re-target to a work-org Confluence space if granularity holds.
8. End-of-phase reflection: trajectory vs roadmap, surprises, demo-fitness assessment, recommendation for Phase 6 (the actual stakeholder demo) and post-demo direction.
9. Commit + push + Phase 5.7 closed in roadmap.

## Tests

Behavior-focused, red-first, mocks only at architectural seams (Anthropic, Confluence, GitHub Contents API). Testcontainers Postgres + MariaDB for repository tests. Project rule from CLAUDE.md §4: test names read as specifications.

Estimated test counts:
- M0: 0 (no code)
- M1: ~15 new (5 annotation extractor + 4 gateway extractor + 3 repository + 3 coordinator)
- M2: ~25 new (the DSL parser carries the most: 11 chain-walker + 6 channel extractor + 4 coordinator project-pass + 4 repository)
- M3: ~12 new (6 renderer + 2 service-page + 4 SyncCoordinator + adjacent)
- M4: 0 (manual walkthrough)

End-of-phase target: ~371 active tests (current 319 + ~52). Net counts honest at milestone close.

## Open questions

Per memory: serial, not batched. Answer the first to unblock M0; subsequent surface at their milestone's red-test phase.

1. **Which work-org service is the demo target, and how is its repo accessed?**
   - Options: GitHub.com public, GitHub.com private, GitHub Enterprise, GitLab, Azure DevOps, Bitbucket Cloud, Bitbucket Server.
   - Auth implications: Personal Access Token via env var (simplest), GitHub App installation (reusable across services), pre-fetch source to a public sandbox repo (workaround if auth is blocked).
   - This unblocks every later step. **Pause for answer before starting M0.**

2. *(Surfaces at M0.)* Annotation-style vs DSL-style mix in the target service? Custom DSL extensions wrapping Spring Integration? Both inform M1 vs M2 emphasis.

3. *(Surfaces at M2 red phase.)* For lambda-bodied routing / handling calls (`.route(payload -> ...)`, `.handle((msg, hdrs) -> ...)`), capture the lambda body text verbatim (helpful debugging hook in the rendered page) or just record `kind="lambda"` (cleaner page)? Default proposed: record `kind="lambda"` only; revisit if dogfood feels thin.

4. *(Surfaces at M3 red phase.)* Mermaid in Confluence — confirm via `ArchitectureMapRenderer`'s macro pattern (we know the macro works there). The Flows page will use the same macro; if the architecture-map mermaid renders, this will too. Default: reuse.

5. *(Surfaces at M4.)* Demo Confluence space: keep ATLAS sandbox until rendering is stable, or re-target to a work-org space mid-phase? Default: ATLAS sandbox through M4, optional re-target before the actual stakeholder demo.

## Carry-overs from earlier phases that this plan does not address

These remain unchanged:
- L4 root-only-page omission for leaf-only services (Phase 6 polish carry-over from 5.6 M2/M4).
- Each-Atlas-service-shows-the-whole-monorepo Internals → Modules visual quirk (Phase 6 polish).
- DD-001 auth model (production-readiness).
- DD-003 CI pipeline (production-readiness).
- DD-015 widen Beans stereotype scope to JPA `@Entity` / Spring Data interfaces / `@ConfigurationProperties` — still possible after this phase; this phase widens to messaging-gateway interfaces specifically, not the broader DD-015 scope.
- L5 stereotype-group module-of-origin annotation, L5 javadoc first-sentence heuristic edge cases, Confluence `confluenceTable` CSS class on rendered tables — all polish carry-overs.

## Reflections

(populated at each milestone close)
