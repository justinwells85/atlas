# Atlas — Handoff Packet

You're reading this because you (or your team) are taking Atlas to production. This packet is the index. Every link below points at one specific document; this README tells you what each one is for and the order to read them in.

## Who this is for

The engineering team productionising Atlas after the prototype phase. Familiarity with Spring Boot, JPA/Flyway, Postgres or MariaDB, and AWS basics is assumed. No Atlas-specific context required — that's what these docs build.

## Read in this order

1. **[`current-state.md`](current-state.md)** — one-page summary of what's built, what's tested, what's not done, and what's deferred. **Read this first.**
2. **[`setup.md`](setup.md)** — fresh-developer setup. Get the prototype running locally end-to-end (~30 minutes from clone to first Confluence page).
3. **[`../architecture.md`](../architecture.md)** — system overview, three Spring Boot apps + shared library + DB + Confluence. The diagram and the components.
4. **[`../schema.md`](../schema.md)** — data model: tables, key columns, migration history. Includes the Phase-5.5 prototype-stage exception note for context on the V1–V10 rewrite.
5. **[`../decisions.md`](../decisions.md)** — ADRs explaining the non-obvious technology choices (Spring Boot 4 modular autoconfig per ADR-007; multi-module split per ADR-012; etc.). Read in reverse-chronological order — newest decisions are the most load-bearing.
6. **[`../deferred-decisions.md`](../deferred-decisions.md)** — the production-readiness checklist. Every entry has a *trigger to revisit* and a *remediation sketch*. **Closing the items here is the work between you and shippable.**
7. **[`../aws-migration-plan.md`](../aws-migration-plan.md)** — provisioning starting point: RDS, ECS, Secrets Manager, networking. Includes ten `[org-decision]` open items the team must fill in.
8. **[`../roadmap.md`](../roadmap.md)** — phase history and what comes after handoff (Phase 7 — Production Migration).
9. **[`../confluence-template.md`](../confluence-template.md)** + **[`../confluence-layout.md`](../confluence-layout.md)** — the output spec (per-page template) and the proposed space hierarchy. Layout proposal is awaiting stakeholder feedback per DD-011; current sync writes pages flat at space root.
10. **[`../demo-script.md`](../demo-script.md)** — 15-minute live demo script for engineering buy-in. Useful both for showing the team and for understanding what the project is meant to do.

## What's NOT here

- A CI pipeline. None exists today; DD-003 captures the gap.
- Production deployment artefacts. The AWS plan is the starting point; no Terraform / CloudFormation / image build pipelines have been authored yet — that's intentional handoff scope.
- An auth implementation. DD-001 has the gap. Atlas runs `127.0.0.1`-only in the prototype.
- Service-level observability/alerting. CloudWatch is the assumption in the AWS plan; tooling beyond that is `[org-decision]`.

## Conventions used across these docs

- **DD-NNN** — entries in `deferred-decisions.md`. The number is stable; the status changes from *Deferred* to *Resolved* as items get closed.
- **ADR-NNN** — architectural decisions in `decisions.md`. Newest at top.
- **`[org-decision]`** — placeholder in the AWS migration plan for items the production team fills in based on their org's conventions.
- **Phase N — …** — roadmap milestones. Phases 0–5 are complete; Phase 5.5 closed the MariaDB portability gap and produced this packet; Phase 6 is the demo + handoff (this); Phase 7 is the production migration (your work).

## When in doubt

`current-state.md` first, then `deferred-decisions.md`. Between them, you have what's done, what's not, and why.
