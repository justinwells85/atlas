# Atlas — Deferred Decisions

Things we've knowingly chosen not to address yet — because the prototype phase doesn't surface them, the cost-to-fix is high relative to current value, or we don't yet know the right shape of the answer.

This is **not** a backlog of "nice to have" features. Each entry is a real gap with a concrete trigger condition. The production team taking Atlas to handoff (Phase 6/7) needs to address every entry below.

Format: short ID, one-line summary, **Why deferred**, **Trigger to revisit**, **Remediation sketch**.

Newest at top.

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

## DD-002 — Schema is NOT portable to MariaDB (V1 is Postgres-specific)

**Status**: Deferred. *Phase 4 M3, captured by failing test.*

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
