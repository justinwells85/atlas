# Atlas — Deferred Decisions

Things we've knowingly chosen not to address yet — because the prototype phase doesn't surface them, the cost-to-fix is high relative to current value, or we don't yet know the right shape of the answer.

This is **not** a backlog of "nice to have" features. Each entry is a real gap with a concrete trigger condition. The production team taking Atlas to handoff (Phase 6/7) needs to address every entry below.

Format: short ID, one-line summary, **Why deferred**, **Trigger to revisit**, **Remediation sketch**.

Newest at top.

---

## DD-018 — Cross-module same-repo javadoc resolution for `@Enable*` annotations

**Status**: Deferred. *Captured Phase 5.9 M3 — configuration extraction.*

The Phase 5.9 M3 extractor captures every `@Enable*`-prefixed annotation use-site on a service's `@Configuration` / `@SpringBootApplication` classes, plus the import-resolved FQN. Javadoc resolution is best-effort: the coordinator builds an FQN → first-sentence map across the SAME-MODULE source tree (the `{module_path}/src/main/java/**/*.java` files we're already walking for `@Value` / `@ConfigurationProperties` extraction). When the annotation's source lives in that walk, javadoc populates; otherwise `javadoc_first_sentence` stays NULL.

This catches two cases for free: the rare in-line declaration (where an org-internal `@EnableMystery` annotation lives next to its consuming `@Configuration` class) and Spring's built-ins (which always live outside the walk → NULL). It does NOT catch the most common org-internal pattern: a shared annotations module / library declaring `@EnableMyThing` types that downstream services import — those rows ship with NULL javadoc until this gap closes.

**Why deferred**: cross-module same-repo resolution requires either (a) walking source trees of sibling modules in the same git repo (needs a "what counts as 'same repo'" heuristic and a way to enumerate sibling module roots — Atlas's `service_modules` data is per-service, not per-repo), or (b) a separate "annotations source" config field per service plus another `RepoFileFetcher` round trip. Both are non-trivial scope adds for prototype scale. The Spring built-in path stays NULL regardless — those javadocs live in the Spring JAR and aren't reachable without classpath introspection (which Atlas explicitly avoids per the AST-only constraint).

**Trigger to revisit**: when an ownership-target codebase imports a shared annotations library whose javadocs would be load-bearing for the rendered Configuration page. The Phase 5.7 SI ownership-analysis target is the main candidate — its in-house `@Enable*` annotations come from a shared microservice-common library.

**Remediation sketch**:

1. Add `services.annotations_repo_url TEXT` (or a separate `service_annotation_sources` table for multi-source support).
2. New `RepoFileFetcher` walk: for each unique `annotation_fqn` whose first segment matches a configured "in-org" prefix, fetch the annotation's source file by FQN-as-path, parse with JavaParser, extract first-sentence javadoc.
3. Cache the FQN → javadoc index across one refresh so identical FQNs from different services don't re-fetch.
4. Tests: synthetic two-repo fixture (annotation in repo A, consumer in repo B) — assert M3 captures the use-site row with the resolved javadoc.

A simpler interim alternative: a curated `@Enable*` → javadoc map in `application.properties` (`atlas.code-sync.enable-annotation-javadocs.com.example.EnableMyThing=...`) that the coordinator overlays on extracted rows. Cheap, manual, but unblocks the demo for the SI target without infrastructure.

---

## DD-017 — Spring Boot relaxed-binding aliases are not normalised in `service_config_properties`

**Status**: Deferred. *Captured Phase 5.9 M1 — configuration extraction.*

Spring Boot's relaxed-binding rules treat `my.app.url` ≡ `my.app.URL` ≡ `MY_APP_URL` ≡ `my-app-url` ≡ `my_app_url` as the same key when binding to `@ConfigurationProperties` or resolving `@Value("${...}")`. The Phase 5.9 M1 extractor records keys **verbatim** as they appear in the source file — no aliasing, no canonicalisation. Two property files declaring `atlas.feature.flag` and `atlas.feature.FLAG` end up as two separate observation rows, and a `@Value("${atlas.feature.flag}")` injection-site cross-link resolved against the verbatim key will only match one of them.

**Why deferred**: relaxed-binding is a runtime resolution rule, not a source-file rule. Implementing it correctly means encoding Spring Boot's `RelaxedNames` set, which has six canonical forms per key, plus environment-variable conversion (`SPRING_DATASOURCE_URL` ↔ `spring.datasource.url`). At prototype scale, the verbatim-text shape is a more honest representation of "what the file actually says" — surfacing alias collisions as separate rows can itself be useful information for an ownership transition. The cross-link asymmetry only matters for codebases that mix styles, which is uncommon in greenfield Spring Boot.

**Trigger to revisit**: an ownership-target codebase mixes alias styles in a way that materially confuses the rendered Configuration page (e.g. dev properties use kebab-case, prod overrides use environment-variable case, and the cross-link to `@Value` injection-sites silently misses half the matches). The Phase 5.7 SI ownership-analysis target is a candidate — it inherits in-house `@Enable*` annotations whose internal property keys may not follow a single convention.

**Remediation sketch**:

1. Add a `canonicalKey(String)` helper in `PropertiesFileParser` that lowercases, replaces `_`/`-` with `.`, and strips bracket suffixes for indexed lists. Keep the verbatim key on the row; add a derived `canonical_key` column for joins.
2. Update the M2 cross-link join (renderer-side, in `ConfigurationPageContext`) to compare canonical keys instead of raw strings.
3. Bonus: if two distinct verbatim keys canonicalise to the same value, surface them on the Configuration page as a "key alias collision" annotation — concrete value to the ownership reader.

---

## DD-016 — `spring.config.import` chained imports are not followed

**Status**: Deferred. *Captured Phase 5.9 M1 — configuration extraction.*

A Spring Boot configuration file can declare `spring.config.import=optional:file:./extra.properties` (or `classpath:`, `vault:`, `configtree:`, etc.) to chain in additional configuration sources. The Phase 5.9 M1 extractor walks `application*.{properties,yml,yaml}` only — chained imports are recorded as a property key like any other, not followed and re-parsed. A service whose `application.properties` declares `spring.config.import=classpath:db.properties` will surface the import declaration but none of `db.properties`'s keys.

**Why deferred**: each `spring.config.import` URI scheme has its own resolver shape (filesystem traversal for `file:`, classpath lookup for `classpath:`, external services like Vault / Consul / Kubernetes ConfigMaps for the rest). Implementing them all is a non-trivial scope add for prototype scale, and a cycle-detection pass to handle `a.properties → b.properties → a.properties` adds further complexity. The single-pass walk covers the common case where everything lives in `src/main/resources/application*.{properties,yml,yaml}`.

**Trigger to revisit**: an ownership-target codebase that uses `spring.config.import` for a non-trivial fraction of its keys (rather than incidentally), such that the Configuration page rendered against the verbatim files is materially incomplete. The Phase 5.7 SI ownership-analysis target is a candidate — in-house `@Enable*` annotations may pull configuration from internal property sources that aren't in the repo's `src/main/resources`.

**Remediation sketch**:

1. Decide which schemes are in scope. `file:` and `classpath:` are tractable from a `RepoFileFetcher` walk; `vault:` / `configtree:` etc. require live infrastructure and stay deferred.
2. Implement a recursive resolver in the coordinator (not the parser — the parser stays single-file). Walk `spring.config.import` declarations, fetch each referenced file via `RepoFileFetcher.fetchFile`, parse, recurse. Track visited paths to detect cycles.
3. Each chained-source key gets `source_file` set to the *imported* filename (so the cross-link to the import declaration is recoverable on the rendered page).
4. Add tests for: simple `classpath:` import, `file:` relative import, optional-vs-required `optional:` prefix handling, cycle detection, missing-file tolerance.

---

## DD-013 — Confluence orphan-page cleanup when a service is deleted from the DB — RESOLVED

**Status**: Resolved (Phase 4.5 follow-up). *Carved out of DD-011 during the Phase 4.5 layout work; closed by ADR-014.*

**Resolution**: implemented option 1 (soft-delete column on `services`). V11 adds `services.deleted_at TIMESTAMP NULL` + an index. Hibernate's `@SQLDelete` rewrites `repository.delete()` into an UPDATE that stamps `deleted_at`; `@SQLRestriction("deleted_at IS NULL")` filters every JPA query. The sync agent's `cleanupDeletedServices` pass loads soft-deleted rows with non-null `confluence_page_id` via a native query, calls `DELETE /wiki/api/v2/pages/{id}` (treating 404 as success — page already gone), and nulls the page ID. Cleanup runs on every `syncAll()`, is idempotent, and isolates per-row failures so one stuck page doesn't block others.

Verified live: a soft-deleted service's Confluence page is removed on the next sync; the row stays with `deleted_at` set and `confluence_page_id` nulled; re-running sync is a no-op on cleaned rows. Re-activation (clearing `deleted_at`) creates a fresh page on the next sync.

Caveat (since handled): the unique constraint on `services.name` is still in effect across soft-deleted rows. Rather than work around the constraint, intake **reactivates** the existing soft-deleted row when its name is reused — clears `deleted_at` + sync state, **clears every attached relationship row** (so the new intake's relationship inserts don't hit per-table UNIQUE), applies the new draft fields, audits a `reactivated` change-type. Same UUID preserved; next sync produces a fresh Confluence page. See ADR-014 for the full pattern.

**Below preserved as the original deferred entry for historical context.**

---

When a service is deleted from the Atlas database, its Confluence page becomes orphaned — the sync agent does not currently delete pages for services that no longer exist. The detection logic isn't trivial because the only handle to a service's page (`services.confluence_page_id`) disappears together with the row.

**Why deferred**: requires a data-model change to retain the link after the source row is gone — either (a) a soft-delete column (`services.deleted_at`) so the row stays around with `confluence_page_id` intact, or (b) a separate `deleted_services_pages` shadow table populated on DELETE that the sync pass scans for cleanup. Neither is a code-only change.

**Trigger to revisit**: the first time a real service in production is deprecated/decommissioned and its inventory entry is removed. Before then, manual page deletion via the Confluence UI is acceptable.

**Remediation sketch**:

1. Decide between soft-delete column vs. shadow table. Soft-delete is simpler at one column's cost; shadow table keeps the active `services` table clean.
2. Either way: add a Flyway migration introducing the chosen mechanism.
3. Update intake / MCP `update_service` paths to write the deletion signal instead of (or alongside) the hard DELETE.
4. Add a sync-pass step in `SyncCoordinator` that finds rows-marked-deleted (or shadow-table entries) and calls `DELETE /wiki/api/v2/pages/{id}` for each, logging successes and failures.
5. Tests via WireMock for the cleanup path; integration test for the full delete → re-sync flow.

---

## DD-012 — Internal LLM gateway implementation (`InternalLlmGateway`) is a stub

**Status**: Deferred. *Phase 5.5 M6 introduced the abstraction; the second impl is pending the org-LLM-gateway spec.*

ADR-013 added a `LlmGateway` interface and two implementations: `AnthropicLlmGateway` (current direct-SDK behavior, default) and `InternalLlmGateway`. The internal-gateway impl is a **stub** that throws `UnsupportedOperationException` on call:

```
atlas.llm.provider=internal-gateway is configured but not yet implemented.
See docs/deferred-decisions.md DD-012 for status. Set
atlas.llm.provider=anthropic to use the current Anthropic-direct
implementation.
```

**Why deferred**: the org's internal LLM gateway has its own API shape (auth, request/response envelope, supported features, rate limits, model identifiers) that hasn't been specced into Atlas yet. Building a real implementation against a guessed shape produces churn when the actual spec arrives. The stub preserves the toggle wiring so the swap, when it happens, is bounded to one class.

**Trigger to revisit**: the production team has the org-internal LLM gateway's API documented (or accessible enough to reverse-engineer). Likely during Phase 7 environment provisioning — the gateway's endpoint and auth are AWS-environment concerns.

**Open spec items** (the production team must answer before implementation):

1. **Endpoint URL** — fully-qualified base URL of the internal gateway.
2. **Auth model** — Bearer token? mTLS? IAM-signed? Static API key per app?
3. **Request envelope** — JSON shape for a "complete this prompt" call. Single-prompt vs. message-array? Required fields beyond prompt (model name, max tokens, temperature, system prompt)?
4. **Response envelope** — text under what JSON path? Error shape? Streaming support?
5. **Model selection** — does the gateway pick a model based on a header, a body field, or a per-app-config default?
6. **Rate limits + retry posture** — does the gateway return 429s? Retry-After header semantics?
7. **Observability** — are calls billed/tracked centrally? Does Atlas need to send a `X-Application-Id` or similar header?

**Remediation sketch**:

1. Capture the answers above (probably as a brief `docs/internal-llm-gateway-spec.md`).
2. Replace `InternalLlmGateway`'s `complete(prompt)` body with a real HTTP call (likely `RestClient`, mirroring `ConfluenceClient`'s pattern). Same use-case-shaped interface; same property-driven toggle.
3. Add provider-specific config keys under `atlas.llm.internal-gateway.*` (endpoint, secret name, model identifier).
4. Add `WireMock` tests for the new impl, mirroring `ConfluenceClientTest`. The interface contract test stays unchanged.
5. Update `aws-migration-plan.md`'s LLM section with the gateway endpoint, secret wiring, and any IAM/networking specifics (e.g., VPC endpoint vs. public URL).
6. When confidence is high enough to flip production, change the property default in production environments (or override per-environment via `application-prod.properties` once profile-based config is added).

The interface itself (`LlmGateway.complete(String) → String`) is expected to fit; if it doesn't (e.g., the internal gateway requires async streaming), revisit ADR-013 to widen the interface.

---

## DD-011 — Confluence space layout: implementation lags the proposed structure — RESOLVED (mostly)

**Status**: Resolved in Phase 4.5 M1–M3, *except* the delete-on-DB-delete item which remains deferred (data-model gap — see DD-013 below for the standalone follow-up).

**Resolution**: items 1–3 of the four-step remediation sketch landed in the Phase 4.5 commits. Service pages are now parented under a "Atlas — Service Inventory" landing page; titles are prefixed with "Service: "; service-to-service references in the rendered pages are hyperlinks to the peer's Confluence page; database and external-dependency references back-link into two new inventory pages ("Inventory: Data Stores", "Inventory: External Dependencies"); a new "About Atlas" page sits alongside. Live-verified against the dogfood ATLAS space.

Item 4 (orphan-page deletion when a service is removed from the DB) is still deferred — the data model has no "service deleted" signal that survives the row's deletion. Captured separately as DD-013.

**Below preserved as the original deferred entry for historical context.**

---

`docs/confluence-layout.md` (added separately for stakeholder socialisation) proposes a different Confluence space shape than what the M3–M5 sync agent currently produces. Current behaviour was settled in M1 as option (c) — the simplest fit for a dedicated `ATLAS` space — and works end-to-end today. The proposal asks for richer structure.

**Gap**:

| Aspect | Current (M1 option c) | Proposed (`confluence-layout.md`) |
|---|---|---|
| Page placement | Flat at space root | Children of a "Atlas — Service Inventory" landing page |
| Service page title | `<service.name>` | `Service: <service.name>` |
| Landing page | None | Required: about-this-space + auto-refreshed service index table |
| Service deletion | Page is orphaned in Confluence; never removed by sync | Sub-page is deleted when the service is removed from Atlas |

**Why deferred**: option (c) is sufficient for the Phase 5 dogfood demo and the prototype handoff path; the proposal adds real implementation work and is meant to be reviewed by stakeholders before we commit.

**Trigger to revisit**: stakeholders sign off on the proposed layout (or revise it). Until then, current shape stands.

**Remediation sketch (four steps to close the gap)**:
1. **Title prefix** — change `SyncCoordinator` to use `"Service: " + service.name` as the page title. ~1 line + renderer test update.
2. **Landing page generator** — new component that renders the "Atlas — Service Inventory" body (preamble + service-index table). Persistence: where does the landing page's Confluence ID live? Likely a single-row config table (e.g., `confluence_landing` with one row), or a fixed sentinel row in `services` (uglier).
3. **Parent ID resolution** — each service-page create uses the landing page ID as `parentId`. `ConfluenceClient.createPage` already accepts a parent argument shape; coordinator wires it.
4. **Delete-on-DB-delete** — a sync pass that finds Confluence pages whose `confluence_page_id` is set on no-longer-existing services (or a soft-delete flag if we add one) and DELETEs them via `DELETE /wiki/api/v2/pages/{id}`. New code path; no current orphan-cleanup exists.

If the proposal is approved, items 1–3 are a clean follow-up phase (call it Phase 4.5 — Confluence Layout Alignment) that does not touch intake or MCP. Item 4 is the trickier one because today's data model has no "service deleted" signal — would need either a new `services.deleted_at` column or a separate `deleted_services` shadow table to drive the page-cleanup pass.

---

## DD-010 — Intake's `method` validation rejects non-HTTP API surfaces (MCP, gRPC, AMQP, etc.) — RESOLVED

**Status**: Resolved (2026-04-28). *Closed via option 1 — widened whitelist.*

**Resolution**: `InterviewService.VALID_API_METHODS` (renamed from `VALID_HTTP_METHODS`) now accepts the original HTTP verbs plus `MCP, GRPC, GRAPHQL, AMQP, KAFKA`. The `AWAITING_API_METHOD` prompt was updated to advertise both groups. Lowercase input continues to normalise via `toUpperCase()`, so user-entered `gRPC` / `kafka` / `graphql` all pass. Two new tests cover the new tokens (`whenNonHttpProtocolMethodIsProvided_thenInterviewAccepts`, `whenAnyOfTheRecognisedNonHttpProtocolsIsProvided_thenAllAreAccepted`); the original `whenInvalidHttpMethodIsProvided_thenInterviewReprompts` still passes — `BANANA` is still rejected.

Option 2 (a separate `protocol` column on `apis`) remains an option for the production team if heterogeneous-method semantics become a real problem; today the field is descriptive, the renderer just prints whatever string was captured, and a flat whitelist is sufficient.

**Below preserved as the original deferred entry for historical context.**

---

**Status (originally)**: Deferred. *Surfaced during the Phase 5 dogfood demo (Atlas registering Atlas).*

`atlas-intake`'s `AWAITING_API_METHOD` stage validates user input against a fixed HTTP-verb whitelist (`GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS`). When registering `atlas-mcp` — which exposes 5 MCP tools (`search_services`, `list_services`, `get_service_details`, `update_service`, `ping`) — labeling the method as `MCP` was rejected; intake re-prompted in a loop until the driver hit its turn cap.

**Workaround applied**: each MCP tool is registered with `method = POST` (the underlying HTTP/SSE transport when MCP runs over HTTP) and the tool's MCP-tool-ness is captured in the description (`"MCP tool. Find services by..."`). Functionally accurate, semantically lossy.

**Why deferred**: workaround is a one-line description tweak per tool; broader fix needs schema-level thinking and we don't yet have other non-HTTP API surfaces in Atlas to validate the design against.

**Trigger to revisit**: when registering a service whose APIs are gRPC, GraphQL, AMQP queues, Kafka topics, or anything else the HTTP-verb whitelist can't honestly describe. Even within Atlas this matters more once MCP becomes a documented integration story for partners.

**Remediation sketch (two options)**:
1. **Widen the validation** to accept a small set of named protocols (`MCP`, `gRPC`, `GraphQL`, `AMQP`, `Kafka`, plus the HTTP verbs). One-line code change, broader human input range, but `method` becomes a heterogeneous field that mixes verbs with protocol names.
2. **Add a `protocol` column to `apis`** (HTTP / MCP / gRPC / Kafka / ...) and let `method` be context-dependent (HTTP verb when `protocol = HTTP`, tool name when `protocol = MCP`, topic when `protocol = Kafka`). Cleaner modeling but a schema migration plus renderer + intake updates.

Option 2 is the proper fix; option 1 is the prototype-acceptable shortcut.

---

## DD-009 — `409 Conflict` on Confluence PUT (concurrent edit race)

**Status**: Deferred. *Phase 4 M3.*

If a human edits a Confluence page in the browser between Atlas's `GET` (to read the current version) and `PUT` (to write the new version), Confluence returns `409 Conflict`. The current `SyncCoordinator` reports it as a generic per-service failure and moves on; the next sync will retry from a fresh `GET` and likely succeed.

**Why deferred**: low-frequency in a prototype where only Atlas writes. The "Confluence edits will be overwritten" architectural principle (`docs/confluence-template.md`) means a 409 is acceptable behavior — Atlas's next sync clobbers the human edit anyway.

**Trigger to revisit**: when the prototype goes multi-user, or when human-edit-protection becomes a feature (e.g., "warn before overwriting").

**Remediation sketch**: catch `HttpClientErrorException.Conflict` in `ConfluenceClient.updatePage`, wrap as a typed `ConfluenceVersionConflictException`, retry-once-from-fresh-GET in the coordinator.

---

## DD-008 — Auth-error classification (401 / 403 vs 5xx)

**Status**: Deferred. *Phase 4 M3, revisit at M4 real-instance smoke.*

`ConfluenceClient` does not distinguish auth failures (`401 Unauthorized`, `403 Forbidden`) from transient server errors (`5xx`). Both surface as generic `RestClientException` and become per-service failures in `SyncResult`.

**Why deferred**: we don't yet know what real-instance auth failures look like (token expired, scope insufficient, IP blocked, etc.). M4 will hit the live Confluence API and tell us.

**Trigger to revisit**: M4 smoke test when we exercise the real `ATLAS` space. Likely a follow-up commit in M4.

**Remediation sketch**: catch `HttpClientErrorException.Unauthorized` / `Forbidden` in the client, throw a typed `ConfluenceAuthException` so the coordinator can fail the *whole* sync (not just one service) — auth failure isn't recoverable per-service.

---

## DD-007 — Rate limiting (429) handling and retry-with-backoff

**Status**: Deferred. *Production concern, not prototype.*

Confluence Cloud rate-limits API requests. Atlas does no backoff or retry; a `429` will surface as a per-service failure and the next sync run will hit the same wall if the limit is still in effect.

**Why deferred**: the prototype syncs a handful of services on a 15-minute cadence. Even a free-tier rate limit (~1000 req/hour) is far above what Atlas does today.

**Trigger to revisit**: if scheduled sync ever exceeds 100 services, or when Atlas runs at production cadence.

**Remediation sketch**: Spring's `@Retryable` with exponential backoff and a `Retry-After`-aware policy on `ConfluenceClient` methods. Spring Retry adds a starter dep.

---

## DD-006 — RestClient / HTTP timeout customization

**Status**: Deferred. *Defaults adequate for prototype.*

`ConfluenceClient` uses Spring's `RestClient.builder()` defaults for connect/read timeouts. No custom timeout configuration today.

**Why deferred**: defaults work fine on a low-latency local dev → Confluence Cloud path.

**Trigger to revisit**: when sync runs on a CI runner with flaky egress, or when "the sync is hanging" becomes a real user complaint.

**Remediation sketch**: configure `JdkClientHttpRequestFactory` (or similar) with explicit connect/read timeouts, surface them as `application.properties` keys.

---

## DD-005 — Spring Boot 4 modular-autoconfig discovery (Jackson, RestClient.Builder)

**Status**: Deferred. *Workaround in place, low blast-radius.*

`atlas-confluence-sync` was unable to autowire `RestClient.Builder` or `ObjectMapper` — both throw `NoSuchBeanDefinitionException` at boot time. Per ADR-007, Spring Boot 4 split autoconfig into per-integration modules, and the right modules for these two beans are not currently identified.

Workaround: `RestClient.builder()` static + inline `new ObjectMapper()` in `ConfluenceClient`. Functionally equivalent; misses any Spring Boot tunings (`MapperFeature` defaults, etc.).

**Why deferred**: workaround is ~6 lines of code and zero behavioral cost; finding the right modules is a research task that doesn't unblock anything.

**Trigger to revisit**: when another Atlas module needs the auto-configured beans, or when a Spring Boot 4 reference doc names the modules clearly.

**Remediation sketch**: identify the `spring-boot-restclient` (or equivalently named) and `spring-boot-jackson` modules, add to `atlas-confluence-sync/pom.xml`, switch `ConfluenceClient` to constructor-inject the auto-configured beans.

---

## DD-004 — `service.updatedAt` rendering not directly tested

**Status**: Deferred. *Covered indirectly.*

The `ServicePageRenderer.renderChangeHistory` path that displays `service.updatedAt` has no direct unit test, because the field has no setter (JPA-managed via `@PreUpdate` per ADR-008) and reflection-driven test fixtures were judged over-engineering at this scale.

**Why deferred**: M4's real-instance smoke will populate `updatedAt` via a real JPA save+update, and the rendered page will visually verify the field arrives. The renderer's null-handling path *is* covered (minimal-fixture test).

**Trigger to revisit**: if M4 reveals that `updatedAt` rendering is broken; or if a future refactor changes how the renderer reads the field.

**Remediation sketch**: add a JPA-saved fixture in `ServicePageRendererTest`, or add a small reflection helper to set the field directly.

---

## DD-003 — No CI pipeline (no GitHub Actions, no PR gating)

**Status**: Deferred. *Phase 6/7 handoff concern.*

Atlas has no automated CI today. `mvn verify` runs locally. Branch protection, required checks, and automated builds are all manual / absent.

**Why deferred**: solo-developer prototype on a local Mac. CI cost-to-set-up exceeds value at this scale.

**Trigger to revisit**: when handoff to the production team starts (Phase 6) and multiple people commit. Earlier if collaboration starts before then.

**Remediation sketch**: GitHub Actions workflow running `mvn verify` on PRs, branch protection on `main` requiring green checks, optional matrix run against MariaDB once DD-002 is fixed.

---

## DD-002 — Schema is NOT portable to MariaDB (V1 is Postgres-specific) — RESOLVED

**Status**: Resolved in Phase 5.5 M1. *Originally Phase 4 M3.*

**Resolution**: V1–V10 rewritten in portable SQL. `MariaDBPortabilitySmokeTest` is no longer `@Disabled` and passes; both Postgres and MariaDB Testcontainers run the migrations cleanly in `mvn verify`. Specific changes:

- V1: dropped the `CREATE TYPE service_status AS ENUM` block and folded the V2 fix in directly (services.status is now TEXT + CHECK from the start). V2 is now a no-op kept for version-sequence continuity.
- All tables: removed `gen_random_uuid()` defaults; UUIDs are generated app-side (Hibernate `@GeneratedValue` for entities; explicit `UUID.randomUUID()` in the `JdbcTemplate` writes — five repository methods updated, plus the schema/contract tests).
- Type swaps: `JSONB` → `JSON`, `TIMESTAMPTZ` → `TIMESTAMP`, `now()` → `CURRENT_TIMESTAMP`. GIN index dropped (Postgres-only; unused per ADR-010 since JSON queries already happen in Java).
- Reserved-word renames: table `databases` → `data_stores` (DATABASES is reserved in MariaDB); `service_changes.before` / `.after` → `before_snapshot` / `after_snapshot` (BEFORE is reserved in MariaDB).
- Documented in `docs/schema.md` as a prototype-stage exception to the "never edit a committed migration" rule. The rule re-applies in full going forward.

**Below preserved as the original deferred entry for historical context.**

---

The "portable to MariaDB" claim in `docs/architecture.md` and ADR-003 was originally false. `MariaDBPortabilitySmokeTest` (atlas-domain, `@Disabled`) ran the V1–V10 migrations against a MariaDB Testcontainer and failed at V1 line 7:

The "portable to MariaDB" claim in `docs/architecture.md` and ADR-003 is currently false. `MariaDBPortabilitySmokeTest` (atlas-domain, `@Disabled`) runs the V1–V10 migrations against a MariaDB Testcontainer and fails at V1 line 7:

```
SQL error 1064: ... near 'TYPE service_status AS ENUM ('active','deprecated','in_dev')' at line 7
```

V1 contains at least four Postgres-specific constructs:

| Construct | V1 line | MariaDB equivalent |
|---|---|---|
| `CREATE TYPE service_status AS ENUM (...)` | 7 | drop entirely; use V2's `TEXT + CHECK` from line 1 |
| `gen_random_uuid()` default | several | `UUID()` (returns text in MariaDB; column type would change to `CHAR(36)`) |
| `JSONB` column type | metadata | `JSON` |
| `TIMESTAMPTZ` column type | created_at, updated_at | `TIMESTAMP` (no built-in tz; store UTC convention) |
| `CREATE INDEX ... USING GIN` | metadata index | drop or replace with a generated-column + standard index |

V2 already converted `services.status` from the ENUM to `TEXT + CHECK` — so the ENUM in V1 is dead weight, but V1 still tries to create it on every fresh DB.

**Why deferred**: rewriting V1 (and possibly V3–V9 for `JSONB` / `TIMESTAMPTZ` / `gen_random_uuid()`) is a real refactor. The prototype runs on Postgres locally; the production target is MariaDB but production deployment is Phase 7. Fixing this in M3 would dilute Phase 4 focus.

**Trigger to revisit**: before Phase 7 handoff begins, ideally during Phase 6 demo prep so the team taking it can ship to MariaDB without rediscovering this.

**Remediation sketch (two options)**:
1. **Rewrite migrations to portable SQL**. Drop the ENUM type from V1, change `JSONB` → `JSON`, `TIMESTAMPTZ` → `TIMESTAMP`, `gen_random_uuid()` → application-side UUID generation (already used in `ServiceRelationshipsRepository.insertApi` etc.). Drop GIN index — accept the loss of indexed JSON queries (we don't filter on JSON anyway, per ADR-010).
2. **Split per-vendor migrations**. Use Flyway's `${vendor}` placeholder or per-directory migrations (`db/migration/postgresql/`, `db/migration/mariadb/`). Heavier maintenance but preserves Postgres-native types locally.

Option 1 is what ADR-003 actually promised; option 2 is fallback if the loss of `JSONB`/`GIN` is unacceptable.

When fixed: remove `@Disabled` from `MariaDBPortabilitySmokeTest`. The test should pass.

---

## DD-001 — Network binding, auth model, session persistence (prototype shortcuts)

**Status**: Deferred. *Phase 6/7 handoff concern. Originally captured in ADR-011.*

The three Spring Boot apps (atlas-intake :8080, atlas-mcp :8081, atlas-confluence-sync :8082) all bind to `127.0.0.1` only. No authentication. No HTTPS. No session persistence — intake interview state lives in each request body, not in the server.

**Why deferred**: ADR-011 recorded the rationale. Network-local binding is the auth shortcut: nothing on the network can reach the API, so we don't need auth yet.

**Trigger to revisit**: the moment any Atlas service goes off `127.0.0.1`. Either deployment to a shared host or exposure on a LAN interface counts.

**Remediation sketch**:
- **Auth**: Spring Security with the org's standard SSO (likely OAuth/OIDC). Bearer tokens for service-to-service calls.
- **Network**: TLS termination at the deployment boundary; Atlas itself can stay HTTP-internal if behind a sidecar.
- **Session persistence**: Spring Session backed by Redis (or DB if no Redis); intake conversation state moves server-side keyed by session ID.
