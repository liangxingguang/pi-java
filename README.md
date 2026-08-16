# pi-java

**pi-java** is a pure-Java port of [pi](https://github.com/earendil-works/pi), an AI coding agent and runtime. It reimplements pi's three layers — the coding-agent CLI, the generic agent runtime, and the multi-provider LLM API gateway — using idiomatic Java (records, sealed interfaces, pattern matching, virtual threads).

> **Status**: MVP (Phase 0–5) complete; Phase 6 (ecosystem) in progress.

## Features

- **Multi-provider LLM gateway** — Anthropic, OpenAI, Google, Mistral, and DeepSeek behind a unified `StreamApi`, with SSE streaming and a programmable `FauxProvider` for offline replay.
- **Manual-drive agent runtime** — `AgentHarness` as a state machine (`peekAction` / `executeAction`), multi-lane orchestration, lifecycle hooks, compaction, and skills.
- **Built-in tools** — `bash`, `read`, `write`, `edit`, `grep`, `ls`, `glob`, with parallel execution and approval gating.
- **Terminal UI** — a Ratatui-style TUI built on [TamboUI](https://tamboui.dev/), with interactive chat, settings, and slash commands.
- **Durable sessions** — SQLite + JSONL v4 double-track storage, FTS5 search, writer leases, branch cache, and crash recovery.
- **Native distribution** — GraalVM native-image binaries with sub-100ms startup.

## Requirements

- **JDK 25** (GraalVM for JDK 25 recommended — required for native image)
- **Maven 3.9+** (or use the bundled `./mvnw` wrapper)

## Build

```bash
./mvnw clean verify        # compile + test + Checkstyle + SpotBugs
./mvnw test -pl pi-java-ai # run a single module's tests
./mvnw checkstyle:check    # code style
```

## Usage

```bash
# print mode (one-shot prompt)
./mvnw -pl pi-java-tui -am exec:java -Dexec.args='-p "hello"'

# interactive TUI
./mvnw -pl pi-java-tui -am exec:java
```

On Windows there is a one-click launcher:

```bat
run.cmd                 :: interactive TUI
run.cmd --list-models   :: list built-in models
run.cmd -p "hello"      :: print mode
```

## Native image

```bash
./mvnw -Pnative package   # produces pi-java-dist/target/pi-java(.exe)
```

Native builds are gated behind the `native` profile. Windows native linking requires Visual Studio 2022 Build Tools (or CI, which pre-installs them). See [`docs/10-phase5-native-design.md`](docs/10-phase5-native-design.md).

## Modules

Dependencies flow bottom-up: `telemetry ← ai ← agent ← coding-agent ← tui`, with `protocol ← client/server`.

| Module | Purpose |
|---|---|
| `pi-java-telemetry` | metrics / tracing contracts |
| `pi-java-ai` | LLM API layer — providers, streaming, model catalog, `pi-ai` CLI |
| `pi-java-agent-core` | agent runtime — `AgentHarness`, tools, sessions |
| `pi-java-session-backend-sqlite` | SQLite session storage (ServiceLoader SPI) |
| `pi-java-tui` | TamboUI terminal UI |
| `pi-java-protocol` / `pi-java-client` / `pi-java-server` | CBOR protocol + remote session (Phase 6) |
| `pi-java-coding-agent` | `pi-java` CLI entry point + `AgentSession` |
| `pi-java-evals` | provider conformance / smoke / extension tests |
| `pi-java-dist` | GraalVM native-image aggregation (Phase 5) |
| `pi-java-bom` | bill of materials — unified version management |

## Design documents

Development follows an AI-driven, phase-based process. Each phase begins with a design document in [`docs/`](docs/) — see [`docs/00-ai-driven-development-process.md`](docs/00-ai-driven-development-process.md) and the implementation plan [`docs/04-implementation-plan.md`](docs/04-implementation-plan.md).

## Contributing

All code is AI-written and human-reviewed. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
