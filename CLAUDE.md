# Atlas — Service Documentation System

## Project Context

**WHY**: Service documentation in our organization is manually maintained, frequently stale, and lives in disconnected places. Atlas auto-generates and updates a Confluence wiki of service inventory (APIs, dependencies, owners, databases) from a central database, reducing manual effort and keeping documentation current.

**WHAT**: A Spring Boot application that:
1. Collects structured data about services via an AI-assisted intake pipeline
2. Stores it in a central database (Postgres locally, MySQL/MariaDB in production)
3. Exposes the data via a Model Context Protocol (MCP) server
4. Auto-syncs to Confluence pages

**HOW**: See `docs/architecture.md` for the system overview, `docs/schema.md` for the data model, `docs/confluence-template.md` for the output specification, `docs/roadmap.md` for current phase, and `docs/decisions.md` for the rationale behind key technology choices.

## Tech Stack

- **Language**: Java 21 LTS
- **Framework**: Spring Boot 4.0.x
- **AI/MCP**: Spring AI 1.1.x (Spring AI MCP starters)
- **Database**: PostgreSQL 14+ (local prototype), MySQL/MariaDB (production target)
- **Build**: Maven
- **Migrations**: Flyway
- **Testing**: JUnit 5, Testcontainers, AssertJ
- **AI client**: anthropic-java SDK

Pin exact versions in `pom.xml`. Spring AI MCP APIs evolved through 2025; don't trust online examples without checking against the version pinned here.

## Common Commands

```
mvn spring-boot:run              # Run the application
mvn test                         # Run unit tests
mvn verify                       # Run unit + integration tests
mvn clean package                # Build a runnable JAR
mvn flyway:migrate               # Apply pending DB migrations

brew services start postgresql@14
psql atlas                       # Connect to the local Atlas database
```

## Constraints

- **Open source policy**: This project must use custom code only. The OB1 reference (https://github.com/NateBJones-Projects/OB1) is architectural inspiration only — do not copy its code.
- **Migration portability**: All persistence code must be portable between Postgres and MySQL/MariaDB. Use JPA's portable JSON support, not Postgres-native JSONB operators in queries.

## Behavioral Guidelines

These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 1. Think Before Coding

Don't assume. Don't hide confusion. Surface tradeoffs.

- State assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them — don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

Minimum code that solves the problem. Nothing speculative.

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

### 3. Surgical Changes

Touch only what you must. Clean up only your own mess.

- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it — don't delete it.
- Remove imports/variables/functions YOUR changes made unused.

Every changed line should trace directly to the user's request.

### 4. Strict TDD with Behavior-Focused Tests

Transform every non-trivial task into a TDD workflow:

1. **Red**: Write tests that define the desired behavior and fail.
2. **Green**: Write the minimal code needed to make those tests pass.
3. **Refactor**: Improve the code while keeping all tests green.

Tests serve three purposes: living requirements documentation, refactor safety, and migration insurance. They must be decoupled from implementation:

- Test through public interfaces only — REST endpoints, service-layer APIs, MCP tool contracts. Never test private methods or internal state.
- Mock only at architectural seams — external services (Anthropic API, Confluence API). Use real implementations for everything inside Atlas.
- Use Testcontainers for database tests — real DB engine, never in-memory substitutes.
- Test names read as specifications: `whenServiceIsRegistered_thenItAppearsInSearch`, not `testRegisterServiceMethod`.
- If a test breaks when implementation changes but behavior doesn't, the test is wrong. Rewrite to assert observable behavior.

### 5. Planning Format

Always show this upfront for non-trivial work:

1. **Goal** — one-sentence restatement of what we're building.
2. **Success criteria** — observable behaviors that define "done." These become the test cases.
3. **Assumptions** — what you're assuming about inputs, edge cases, or intent. Flag anything uncertain.
4. **Approach** — high-level strategy. If multiple approaches are viable, list them with tradeoffs.
5. **Steps** — numbered, atomic. Each step is small enough to review independently.
6. **Tests** — behavior-focused tests derived from success criteria (TDD: these come first, must fail before implementation).
7. **Open questions** — anything you need from the user before starting.

Group the numbered steps into **review milestones** — points where a deliverable exists that the user can validate (e.g., "tests pass," "schema migrated and verified," "MCP tool returns correct data"). Wait for confirmation before executing the plan. Then work autonomously through each milestone, pausing only at milestone boundaries (or at true blockers) — not between every step.

## Working with the User

- Work autonomously toward the next review milestone. Make judgment calls along the way and bundle them into the milestone report rather than pausing to ask. The user prefers to discuss decisions at the milestone, when there is a deliverable to validate against — not at every fork in the road.
- Pause and ask the user only when one of the following is true:
  1. **True blocker** — authentication needed, a required tool/credential is missing, or the environment fails in a way that requires user action.
  2. **High-blast-radius, hard-to-reverse decision** — creating external accounts, deleting data, or locking in a foundational architecture decision the user hasn't already settled in CLAUDE.md or the decision log.
  3. **Review milestone reached** — a deliverable exists that the user can validate. Present it, list the judgment calls made along the way, and wait.
- For low-blast-radius choices that would otherwise prompt a question, decide based on best judgment, log the decision in the next milestone report, and proceed. The user will redirect at the milestone if any decision was wrong.
- The user is a novice Java developer. Explain non-obvious choices briefly in milestone reports. Avoid jargon when a simpler term exists.
- For trivial tasks (typo fixes, formatting), full planning format is not required.
