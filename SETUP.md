# Atlas — Setup Instructions

Step-by-step instructions to go from this doc bundle to a working Claude Code session.

## Prerequisites

Before starting, install:

- **Java 21 LTS** — recommended via SDKMAN: `curl -s "https://get.sdkman.io" | bash`, then `sdk install java 21-tem`
- **Maven 3.9+** — `brew install maven`
- **PostgreSQL 14+** — `brew install postgresql@14`
- **Git** — already on macOS
- **Claude Code** — see https://docs.claude.com/en/docs/claude-code/overview for the current installer

Verify:

```bash
java -version       # should show 21.x
mvn -version
psql --version
git --version
claude --version
```

## Step 1: Place the doc bundle

```bash
mkdir -p /Users/justin/Documents/Coding/Atlas
cd /Users/justin/Documents/Coding/Atlas
# Copy the contents of this bundle into the directory.
# After copying you should have:
#   CLAUDE.md
#   README.md
#   .gitignore
#   SETUP.md (this file)
#   docs/
#   db/migrations/V1__initial_schema.sql
```

## Step 2: Initialize git locally

```bash
cd /Users/justin/Documents/Coding/Atlas
git init
git add .
git commit -m "Initial doc bundle for Atlas"
```

## Step 3: Create the GitHub repo and push

Two options. Pick whichever is easier.

### Option A: GitHub CLI (faster)

```bash
brew install gh
gh auth login
gh repo create justinwells85/atlas --private --source=. --remote=origin --push
```

### Option B: GitHub web UI

1. Go to https://github.com/new
2. Owner: `justinwells85`. Repository name: `atlas`. Visibility: Private.
3. Do NOT initialize with README, .gitignore, or license — you already have them.
4. Click "Create repository."
5. Back in your terminal:

```bash
cd /Users/justin/Documents/Coding/Atlas
git remote add origin https://github.com/justinwells85/atlas.git
git branch -M main
git push -u origin main
```

## Step 4: Verify Postgres is running

```bash
brew services start postgresql@14
createdb atlas
psql atlas -c "SELECT version();"
```

You should see Postgres version output. If it errors, troubleshoot Postgres before proceeding.

## Step 5: Start your first Claude Code session

```bash
cd /Users/justin/Documents/Coding/Atlas
claude
```

Claude Code will start, automatically read `CLAUDE.md`, and have full project context.

### Suggested first prompt

> I've just initialized the Atlas project. Please verify the setup by reading CLAUDE.md and the docs/ folder, then propose a plan for completing Phase 0: generating the Spring Boot project scaffold (via Spring Initializr or equivalent), configuring Flyway, and running V1__initial_schema.sql against the local Postgres database. Show me the plan in the format defined in CLAUDE.md and wait for my approval before executing.

This launches Claude Code in plan-first mode, aligns it with the roadmap, and lets you review before any code is written.

## Step 6: After your first session

- Review what Claude Code generated. Confirm it followed the planning format from CLAUDE.md.
- If it skipped the plan step, that's a signal CLAUDE.md needs tightening on that rule.
- Use `/memory` in the session to inspect what Claude Code has auto-remembered.
- Use `#` followed by a rule mid-session to add to memory (e.g., `# Always run mvn test after code changes`).

## Troubleshooting

**"createdb: command not found"** — Postgres isn't on your PATH. Try `brew services restart postgresql@16` and reopen the terminal.

**"Permission denied (publickey)" pushing to GitHub** — set up SSH or use HTTPS. For HTTPS, GitHub requires a Personal Access Token (not your password): https://github.com/settings/tokens

**Java version mismatch** — confirm `java -version` shows 21. If multiple JDKs are installed via SDKMAN, run `sdk default java 21-tem`.

**Claude Code can't find CLAUDE.md** — make sure you're running `claude` from `/Users/justin/Documents/Coding/Atlas`, not a subdirectory or parent.
