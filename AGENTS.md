# AGENTS.md

Guidance for AI coding agents working in this repository.

## Architecture Overview

**pi-java** is a Pure Java (JDK 26) port of [pi](https://github.com/earendil-works/pi), an AI coding agent. The project has 11 Maven modules (no JPMS; modules run on the classpath) with strict bottom-up dependencies:

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
- **docs/03-detailed-design.md** — class-level design: Entry/LaneRecord, AgentHarness, SessionStorage/Repository, SQLite schema, JSONL v4 format, TamboUI components, slash commands, CLI parameters
- **docs/04-implementation-plan.md** — Phase 0–6, 13–17 week MVP, risk matrix
- **docs/05-phase0-infrastructure-design.md** — Phase 0 detailed blueprint (this phase)

## Development Workflow

All code is written by AI, reviewed by humans. Each phase follows the 8-step process described in `docs/00-ai-driven-development-process.md`.

## Current Phase

Phase 0 — Infrastructure: project skeleton, build system, CI.
