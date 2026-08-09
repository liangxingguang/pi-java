# Contributing to pi-java

## AI-Driven Development

pi-java follows an AI-driven development process where all code is written by AI and reviewed by humans. See `docs/00-ai-driven-development-process.md` for the full 8-step process.

## Commit Conventions

- **Format**: `{feat,fix,docs}({module}): <message>`
- **Example**: `feat(ai): implement Anthropic provider streaming`
- **Granularity**: 200–500 lines per commit, each independently compilable module
- **Stage explicitly**: `git add <path>`, never `git add -A` or `git add .`

## Pull Requests

1. All PRs must pass `mvn clean verify` with zero errors and zero warnings
2. All tests must pass
3. No `System.out.println` residue
4. Checkstyle and SpotBugs must report zero violations
5. Do not push to `main` directly — always use a feature branch
6. Do not force push

## Phase Design Docs

Each phase begins with a design document (`docs/XX-phaseN-xxx-design.md`) extracted and expanded from the corresponding section of `03-detailed-design.md`. The design doc serves as both an implementation blueprint and (later) a development tutorial.

## Before Submitting

```bash
./mvnw clean verify     # zero errors, zero warnings
./mvnw checkstyle:check # zero violations
./mvnw spotbugs:check   # zero bugs
./mvnw test             # all tests pass
```
