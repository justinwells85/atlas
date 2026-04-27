# Atlas — AWS Migration Plan

A starting point for the team taking Atlas to production. Written organisation-agnostic; entries marked **`[org-decision]`** are open items the production team fills in based on your AWS account, networking conventions, and security posture.

This document covers what to provision, in roughly the order you'd provision it. It does **not** prescribe an implementation timeline — that depends on `[org-decision]` items below.

## Purpose & scope

- **Who reads this**: the engineering team standing up Atlas in AWS. Assumes familiarity with AWS basics (VPC, IAM, ECS or equivalent, RDS, Secrets Manager).
- **What it covers**: provisioning the three Atlas runtime services + their MariaDB database; secrets handling; networking; cut-over from prototype to production.
- **What it doesn't cover**: CI/CD pipelines (DD-003), observability tooling (open item), cost optimisation past prototype scale, multi-region or DR.

## Target architecture (logical)

```
┌─────────────────── AWS account / region ──────────────────────────┐
│                                                                    │
│  VPC (private subnets)                                            │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │  ECS cluster (Fargate)                                       │ │
│  │  ┌────────────────┐  ┌───────────────┐  ┌─────────────────┐ │ │
│  │  │ atlas-intake   │  │ atlas-mcp     │  │ atlas-confluence│ │ │
│  │  │ :8080          │  │ :8081         │  │ -sync :8082     │ │ │
│  │  └───────┬────────┘  └───────┬───────┘  └────────┬────────┘ │ │
│  │          │                   │                    │          │ │
│  │          └───────────────────┴────────────────────┘          │ │
│  │                              │                                │ │
│  └──────────────────────────────┼────────────────────────────────┘ │
│                                 ▼                                  │
│                     ┌────────────────────┐                         │
│                     │  RDS MariaDB 10.11 │                         │
│                     │  (single-AZ for    │                         │
│                     │   non-prod;        │                         │
│                     │   multi-AZ for prod)│                        │
│                     └────────────────────┘                         │
│                                                                    │
│  AWS Secrets Manager                                              │
│   - atlas/db/password                                             │
│   - atlas/anthropic/api-key                                       │
│   - atlas/confluence/api-token                                    │
│                                                                    │
│  CloudWatch Logs (per-service log groups)                         │
│                                                                    │
│  NAT Gateway (egress to api.anthropic.com, *.atlassian.net)       │
└────────────────────────────────────────────────────────────────────┘
```

## RDS — MariaDB

| Item | Recommendation | Rationale |
|---|---|---|
| Engine | MariaDB **10.11** | Matches the version verified by `MariaDBPortabilitySmokeTest`. Long-term support release. |
| Instance class | `db.t4g.medium` (2 vCPU, 4 GiB, ARM Graviton) | Burstable, prototype-scale cost (~$60/mo us-east-1 single-AZ). Bump to `db.t4g.large` or `db.m7g.large` once load is observed. |
| Storage | 20 GiB gp3 minimum, **autoscaling enabled to 100 GiB** | Schema is small; autoscaling absorbs growth without manual intervention. |
| Multi-AZ | **Off for non-prod, On for prod** | Failover insurance. ~2× cost. |
| Backups | 7-day retention | Sufficient for a documentation system; revisit if RPO requirements differ. `[org-decision]` if a longer retention is mandated. |
| Maintenance window | Off-hours, region-appropriate | `[org-decision]` |
| Encryption | At-rest (KMS) on; in-transit (TLS) on | Standard. `[org-decision]` if a specific KMS key is required. |
| Public accessibility | **No** | Tasks reach the DB via VPC private subnets only. |
| Parameter group | Default `mariadb10.11` | No app-side tuning needed at prototype scale. |
| User | `atlas_app` (least privilege; SELECT/INSERT/UPDATE/DELETE on the schema, no DDL after Flyway baselines) | Initial DDL via Flyway runs as a higher-priv user on first deploy; revoke after. `[org-decision]` if your org uses a different bootstrap pattern. |

**One-time DB-side action**: Atlas's `services.name`, `data_stores.name`, and `external_dependencies.name` are unique. The `services` table holds prototype scale (~hundreds of rows expected over time), so no special indexing beyond what V1–V10 create.

## Compute — ECS Fargate (recommended)

Three tasks, one per Spring Boot app. **Recommended** because all three are stateless web apps and Fargate eliminates the host-management work.

Alternative: **Elastic Beanstalk** (simpler if your team already uses it) or **EKS** (overkill at this scale; reasonable if your org standardises on it).

`[org-decision]` — pick one; the rest of this section assumes Fargate.

| Item | Recommendation |
|---|---|
| Cluster | One ECS cluster, Fargate launch type |
| Task definitions | One per service (intake, mcp, confluence-sync). 0.5 vCPU / 1 GiB to start; bump per profiling. |
| Container image | Build the Spring Boot fat JAR (`mvn package`), wrap in a minimal JDK 21 base image (e.g., `eclipse-temurin:21-jre-alpine`). Push to ECR. |
| Image tagging | `[org-decision]` — git SHA is the cleanest pattern. |
| Service definition | One ECS service per task definition. Desired count = 1 for prototype; raise after scale-load testing. |
| Service discovery | ECS Service Connect (recommended) or Cloud Map. Lets the three apps reach each other by DNS name without ALB. |
| Internal ALB | Optional — only if you want to expose intake or sync via path-based routing for an external client. Today they're meant to be internal. |
| Health checks | Spring Boot Actuator `/actuator/health` once added (ADR / DD-005 territory; not configured today). Until then, TCP health check on the listening port. |

### Per-service settings

| Service | Port | External? | Notes |
|---|---|---|---|
| `atlas-intake` | 8080 | **No** | Stateless interview turns. May expose via a UI later — but currently no external entry point per ADR-011. |
| `atlas-mcp` | 8081 | **`[org-decision]`** | If AI clients (Claude Desktop, Claude Code) need to reach it, expose through an ALB + auth. Otherwise internal. |
| `atlas-confluence-sync` | 8082 | **No** | Internal trigger + scheduled cron only. |

The `127.0.0.1` binding from ADR-011 will need to change to `0.0.0.0` once the apps live in containers — bind-address is a Spring property override at deploy time, not a code change. **DD-001 captures the auth that should accompany this transition.**

## Secrets management — AWS Secrets Manager

Three secrets minimum:

| Secret | Source | Consumer |
|---|---|---|
| `atlas/db/password` | Generated at RDS provisioning | All three task definitions inject as `SPRING_DATASOURCE_PASSWORD` |
| `atlas/anthropic/api-key` | Org-issued Anthropic key | atlas-intake injects as `ANTHROPIC_API_KEY` |
| `atlas/confluence/api-token` | Atlassian API token | atlas-confluence-sync injects as `ATLAS_CONFLUENCE_API_TOKEN` |
| `atlas/confluence/email` | (optional secret; can also be plain config) | atlas-confluence-sync injects as `ATLAS_CONFLUENCE_EMAIL` |

Inject via the task definition's `secrets` block (NOT `environment`); ECS resolves secrets at task start and passes them as env vars. The Spring Boot `application.properties` files already read these env vars (`${ATLAS_CONFLUENCE_API_TOKEN:}`).

Rotation: Secrets Manager supports automatic rotation. `[org-decision]` — rotation cadence and how Atlas tasks pick up new tokens (today, redeploy required; lighter solutions exist).

## Networking & security

| Item | Recommendation |
|---|---|
| VPC | `[org-decision]` — share an existing org VPC or create dedicated. |
| Subnets | Tasks in private subnets. RDS in DB subnet group across ≥2 AZs (multi-AZ-ready even if not initially enabled). |
| NAT Gateway | Required: tasks need egress to `api.anthropic.com` (intake) and `[your-org].atlassian.net` (sync). |
| Security groups | One SG for tasks; one for RDS. Allow tasks → RDS:3306. Allow tasks → 443 outbound. |
| TLS | RDS in-transit on. Outbound HTTPS to Anthropic + Confluence. No internal TLS between Atlas tasks today (DD-001). |
| WAF | Not needed unless atlas-mcp or intake gets an external ALB. |
| IAM — task execution role | Pulls images from ECR; reads secrets from Secrets Manager; writes logs to CloudWatch. Standard. |
| IAM — task role | None needed today. The apps don't call AWS APIs directly. |

## Cut-over plan

Atlas is a **documentation system**, not an OLTP system. There is no production data to migrate. Cut-over is therefore a **stand-up + repopulate**, not a data migration:

1. **Provision** — VPC, RDS, ECS cluster, secrets, ECR repos. Run Flyway on first task start; `MariaDBPortabilitySmokeTest` already proves the migrations apply cleanly.
2. **Deploy** — push images to ECR, start the three ECS services. Verify all three healthy.
3. **Network reachability check** — exec into a task, curl `api.anthropic.com` and `[your-org].atlassian.net` to confirm egress.
4. **First Confluence sync** — point `atlas-confluence-sync` at a Confluence space. The first `POST /api/sync/run` will be a no-op (empty inventory) — that's expected.
5. **Run intake against real services** — use `intake_driver.py` (or a UI when one exists) to register each service. Each completed intake = one Confluence page on next sync.
6. **Verify** — pages exist; relationship edges look right; updates flow through (`UPDATE services SET description = …; POST /api/sync/run`).
7. **Switch from manual triggers to scheduled** — confirm `atlas.confluence.sync.cron` is set to its production cadence (default 15 min; DD configurable per env).
8. **Declare cut-over complete** — communicate the new docs source-of-truth to teams.

Steps 5–6 are where the time goes. Each service intake takes ~10 minutes of one human's time per the existing prototype interview.

## Observability — minimum viable

| Item | Recommendation |
|---|---|
| Logs | CloudWatch Logs, one log group per service. JSON-structured Spring Boot logs are easy to query. |
| Metrics | CloudWatch metrics for ECS service health. **`[org-decision]`** — if Datadog / Splunk / similar is the org standard, route logs and metrics there. |
| Alarms | (1) Sync failure count > 0 over 1 hour. (2) RDS storage >80%. (3) Task restarts > N per hour. **`[org-decision]`** — alarm targets (PagerDuty? email? Slack?). |
| Tracing | None today. Spring Boot 4 has Micrometer Tracing if you want OTLP. Not a prototype concern. |

## Rough monthly cost (us-east-1, prototype scale)

| Item | Estimate |
|---|---|
| RDS MariaDB `db.t4g.medium`, 20 GiB gp3, single-AZ | ~$60 |
| ECS Fargate × 3 tasks × 0.5 vCPU / 1 GiB / 730 hrs | ~$90 |
| Application Load Balancer (if used) | ~$20 |
| NAT Gateway (1) | ~$32 |
| Secrets Manager (4 secrets) | ~$2 |
| CloudWatch Logs (5 GiB/month) | ~$5 |
| Data transfer out (low) | ~$5 |
| **Estimated total** | **~$215/month** |

Multi-AZ RDS roughly doubles RDS line. Production load (more services synced more often) should not move this materially in the prototype phase.

## Open items — `[org-decision]`

These need org-specific answers before provisioning starts. Bring them to the engineering team taking the handoff:

1. **AWS account / region** — share an existing account, or new one for Atlas?
2. **Networking** — share an existing VPC, or new dedicated VPC?
3. **Compute platform** — Fargate (recommended), Beanstalk, or EKS?
4. **Domain & TLS** — does atlas-mcp need an external endpoint? If yes, what domain, and what cert source (ACM vs. uploaded)?
5. **Auth model for the prototype-to-prod transition** — how does the org's SSO plug into Spring Security? (DD-001 — this is the biggest open architectural item.)
6. **Backup retention beyond 7 days** — any compliance requirement?
7. **Disaster recovery posture** — RPO/RTO targets? Multi-region?
8. **Observability stack** — CloudWatch only, or route to Datadog/Splunk/your-tool?
9. **Alarm targets** — PagerDuty, email, Slack — and to whom?
10. **CI/CD pipeline** — `[org-decision]` per DD-003. Suggested: GitHub Actions building images, pushing to ECR, deploying via `aws ecs update-service`. Org may prefer a different platform.

## What this plan deliberately leaves to the next pass

- **CI/CD specifics** — depends on org tooling.
- **Spring Boot Actuator wiring** for liveness/readiness — small follow-up commit (~30 min) once the deployment shape is decided.
- **The Confluence layout proposal** in `docs/confluence-layout.md` — pending stakeholder feedback (DD-011); not blocking AWS provisioning.
- **MCP exposure for external AI clients** — depends on auth conversation (item 5 above).

When the team begins provisioning, the natural sequence is: VPC → RDS → ECR → first Fargate deploy → secrets wiring → cut-over. Each is a self-contained step; none is novel work for an AWS-experienced team.
