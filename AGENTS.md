# AGENTS.md

Guidance for AI coding agents working in this repository.

## Architecture Overview

**pi-java** is a Pure Java (JDK 25) port of [pi](https://github.com/earendil-works/pi), an AI coding agent. The project has 11 Maven modules (no JPMS; modules run on the classpath) with strict bottom-up dependencies:

```
telemetry ← ai ← agent ← coding-agent
                         ← tui
              agent ← session-backend-sqlite
              coding-agent ← evals
              protocol ← client
              protocol ← server
```

## Key Resources

- **CLAUDE.md** — commands, coding conventions, SDK entry points
- **docs/01-requirements-analysis.md** — 35 functional + 10 non-functional requirements
- **docs/02-architecture-design.md** — module structure, layer dependencies, core interfaces
- **docs/03-detailed-design.md** — class-level design: Entry/LaneRecord, AgentHarness, SessionStorage/Repository, SQLite schema, JSONL format, TamboUI components, slash commands, CLI parameters
- **docs/04-open-items-register.md** — open items register (the "what is still not aligned" ledger)
- **docs/05-module-alignment-map.md** — per-module alignment map (detail under `docs/map/`)

## Development Workflow

All code is written by AI, reviewed by humans per the 8-step process in `docs/00-ai-driven-development-process.md`. Current work proceeds as alignment packages tracked in `docs/04`; closed package docs were pruned (history in git).
