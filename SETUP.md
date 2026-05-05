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

## Wiki Sinks (Phase 5.8 M4 onward)

Atlas renders service documentation through a `WikiSink` abstraction with two implementations: **local Markdown** (Obsidian-browseable vault) and **Confluence Cloud** (the original dogfood target). Each sink is independently toggled, so an instance can run one, the other, or both.

### Two-instance pattern

Atlas is built to run as two parallel instances on different machines or different working copies:

| Instance | Purpose | Default sink | Confluence sink |
|----------|---------|--------------|-----------------|
| Confidential local-only | Ownership analysis of confidential targets | local-markdown ON | OFF (egress forbidden) |
| Public dogfood | Atlas-on-Atlas, exercised against the public ATLAS Confluence space | local-markdown ON | ON (`confluence-dogfood` profile) |

The default configuration (no profile) is the **confidential local-only** mode: only the local-markdown sink runs, and the Confluence sink stays inert even if API credentials happen to be in the environment. This is the safe default for any clone of the repo placed near work-confidential source.

### Required configuration

When local-markdown is enabled (the default), one property is mandatory:

```bash
export ATLAS_WIKI_LOCAL_MARKDOWN_PATH=/absolute/path/to/your/vault
```

The local-markdown sink fails at startup with `IllegalStateException` if this is unset. Pick any directory; the sink will create files inside it. To browse with Obsidian, open that directory as a vault.

### Running the public dogfood instance

```bash
export ATLAS_CONFLUENCE_EMAIL='you@example.com'
export ATLAS_CONFLUENCE_API_TOKEN='...'
export ATLAS_WIKI_LOCAL_MARKDOWN_PATH="$HOME/atlas-vault"   # or your preferred vault dir

cd atlas-confluence-sync
mvn spring-boot:run -Dspring-boot.run.profiles=confluence-dogfood
```

This activates the `confluence-dogfood` Spring profile (`application-confluence-dogfood.properties`), which re-enables the Confluence sink alongside local-markdown. Both sinks fan out per page on every sync.

### Running a confidential local-only instance

```bash
export ATLAS_WIKI_LOCAL_MARKDOWN_PATH=/path/to/confidential/vault

cd atlas-confluence-sync
mvn spring-boot:run
```

No profile, no Confluence env vars needed (and they'll be ignored if set).

## Troubleshooting

**"createdb: command not found"** — Postgres isn't on your PATH. Try `brew services restart postgresql@16` and reopen the terminal.

**"Permission denied (publickey)" pushing to GitHub** — set up SSH or use HTTPS. For HTTPS, GitHub requires a Personal Access Token (not your password): https://github.com/settings/tokens

**Java version mismatch** — confirm `java -version` shows 21. If multiple JDKs are installed via SDKMAN, run `sdk default java 21-tem`.

**Claude Code can't find CLAUDE.md** — make sure you're running `claude` from `/Users/justin/Documents/Coding/Atlas`, not a subdirectory or parent.
