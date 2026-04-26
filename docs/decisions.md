# Atlas — Decision Log

Architectural decisions captured in lightweight ADR (Architecture Decision Record) format. Newest at top.

---

## ADR-007: Pin Spring Boot 4 integration modules explicitly

**Status**: Accepted

**Context**: During Phase 0 smoke testing we discovered two breaking changes from prior versions, neither flagged at compile time:

1. **Spring Boot 4 modularized autoconfiguration.** In 3.x, putting `flyway-core` on the classpath was enough to trigger Flyway autoconfig. In 4.x, autoconfig classes moved out of `spring-boot-autoconfigure` and into per-integration modules (e.g., `org.springframework.boot:spring-boot-flyway`, `spring-boot-hibernate`, `spring-boot-data-jdbc`). Without the integration module, the autoconfig classes are simply absent — the application starts cleanly with no warning, but the integration silently doesn't initialize.

2. **Testcontainers 2.x renamed every artifact** to add a `testcontainers-` prefix (e.g., `org.testcontainers:postgresql` → `org.testcontainers:testcontainers-postgresql`). Spring Boot 4 manages Testcontainers 2.x by default, so any 1.x artifact name in `pom.xml` produces "version missing" errors with no hint that the artifact was renamed.

Both surfaced because behavior-focused tests (ADR-006) asserted on real outcomes (migration applied, container started) rather than on code presence. A test that just checked "is the FlywayAutoConfiguration bean defined?" would have caught the first one earlier; a test that asserted "the schema we expect exists" caught it without coupling to internals.

**Decision**: For every Spring Boot 4 integration we add (Flyway, JPA, MCP server, etc.), declare both the underlying library AND the corresponding `spring-boot-<integration>` module explicitly in `pom.xml`. Do not assume transitive pickup from a starter or a base library. When adding any dependency from a Spring Boot 3.x tutorial, verify the artifact ID against Spring Boot 4 reference docs at https://docs.spring.io/spring-boot/reference/.

**Consequences**:
- POM is more explicit (and longer) than typical Spring Boot 3.x examples.
- Online tutorials, Stack Overflow answers, and AI-generated examples written before late 2025 will frequently be wrong about both module names and autoconfig behavior. Treat all examples skeptically.
- Future migrations (e.g., bumping to Spring Boot 5) should re-verify which integration modules are needed; the modularization may be revisited.

---

## ADR-006: Strict TDD with Behavior-Focused Tests

**Status**: Accepted

**Context**: Tests serve three purposes for Atlas — living requirements documentation, refactor safety, and migration insurance (Postgres → MySQL/MariaDB). Implementation-coupled tests (mocking internal collaborators, asserting on private behavior) become a maintenance tax and break during refactoring even when behavior is preserved.

**Decision**: Strict Red/Green/Refactor TDD on all non-trivial work, with tests written against public interfaces only. Mock only at architectural seams (external services). Use Testcontainers for DB tests. Test names express specifications.

**Consequences**:
- Tests survive refactoring as long as behavior is preserved.
- Tests survive the eventual MySQL/MariaDB migration if they're decoupled from Postgres-specific SQL.
- Slightly higher upfront cost per feature; offset by lower long-term maintenance and confidence during migration.

---

## ADR-005: Java 21 + Spring Boot 4.0.x + Spring AI 1.1.x

**Status**: Accepted

**Context**: Initially considered Python given AI ecosystem maturity. Re-evaluated after confirming the official MCP Java SDK (maintained in collaboration with Spring AI), Spring Boot MCP starters, and the `anthropic-java` SDK are all production-ready. Spring AI 1.1.x explicitly requires Java 21 to build.

**Decision**: Java 21 LTS (not 25), Spring Boot 4.0.x (current stable), Spring AI 1.1.x, Maven build tool.

**Consequences**:
- Easier handoff to enterprise team (Java/Spring shop).
- Production migration to AWS is straightforward — same language, same framework.
- Spring AI MCP APIs have had churn through 2025; pin exact versions to avoid drift from online examples.
- We give up some Python ecosystem momentum but gain typed APIs and team familiarity.

---

## ADR-004: Maven, not Gradle

**Status**: Accepted

**Context**: Both work. Maven is dominant in enterprise Java; Gradle has faster incremental builds.

**Decision**: Maven, for predictability, eventual team handoff, and richer beginner-friendly tooling/documentation.

---

## ADR-003: Local Postgres prototype, AWS MySQL/MariaDB production

**Status**: Accepted (revised 2026-04-26 — see Revision below)

**Context**: Work environment provides easy access to MySQL/MariaDB on AWS but not Postgres-as-a-service. Local Postgres lets the prototype get built fast on a Mac.

**Decision**: PostgreSQL 14+ locally for the prototype. Schema designed to be portable. Production migration to AWS RDS with MySQL or MariaDB.

**Consequences**:
- Schema must avoid vendor-specific features where possible (or accept rewriting on migration).
- JSON columns supported by both — but query operators differ. Application code must use portable JSON access patterns (JPA methods, not Postgres `@>` and `?` operators).
- Postgres ENUM types don't port directly. Resolve before V2 migration.

**Revision (2026-04-26)**: Original decision specified PostgreSQL 16+. Relaxed to 14+ because the local Mac already had `postgresql@14` running and V1 schema features (`gen_random_uuid()`, `JSONB`, GIN indexes, `TIMESTAMPTZ`, ENUM types) are all available in PG 14. Production target (MySQL/MariaDB) is unchanged, so the version of the local prototype Postgres has no downstream impact.

---

## ADR-002: Postgres (relational + JSON), not Neo4j (graph)

**Status**: Accepted, with revisit trigger

**Context**: Atlas models a service dependency graph, which suggests Neo4j. But the prototype's actual queries are bounded ("what depends on Service X?"), which SQL JOINs handle fine. Graph DB introduces a steeper learning curve and weaker enterprise support story.

**Decision**: Postgres with explicit relationship tables for the prototype. Revisit if dependency traversal patterns become deep (3+ hops) or computationally expensive.

**Consequences**:
- Faster to prototype.
- Lower friction for the enterprise team taking it over.
- Some traversal queries will be more verbose in SQL than Cypher.

---

## ADR-001: OB1 as architectural blueprint, not code source

**Status**: Accepted

**Context**: OB1 (Open Brain by Nate B. Jones) is a strong reference for the architecture pattern: central DB + MCP server + structured intake. Work has an open source approval policy that would require formal review to adopt OB1 directly.

**Decision**: Use OB1 as inspiration for architecture (data model patterns, four-tool MCP design, intake flow shape). Write all Atlas code from scratch.

**Consequences**:
- No open source approval bottleneck.
- More upfront engineering, but we control the design end-to-end.
- We benefit from OB1's tested patterns without inheriting its dependencies.
