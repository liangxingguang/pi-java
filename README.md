# pi-java

**A pure-Java (JDK 25) reimplementation of [pi](https://github.com/earendil-works/pi)** — an AI coding agent and runtime. It rebuilds pi's three layers in idiomatic Java (records, sealed interfaces, pattern matching, virtual threads): the coding-agent CLI, the generic agent runtime, and the multi-provider LLM gateway.

English | [简体中文](README_zh-CN.md)

## Highlights

- **Multi-provider gateway** — Anthropic, OpenAI, Google, Mistral and DeepSeek behind a unified `StreamApi`, plus OpenAI-compatible catalogs (xAI, Moonshot/Kimi, z.ai, OpenRouter, and user-defined `models.json` providers). SSE streaming, tool calls, images, OAuth, and a programmable `FauxProvider` for offline replay.
- **Agent runtime** — `AgentHarness` as an explicit state machine (`peekAction` / `executeAction`), multi-lane orchestration, lifecycle hooks, context compaction, skills, prompt templates, and extension hooks.
- **Built-in tools** — `bash`, `powershell`, `read`, `write`, `edit`, `grep`, `find`, and `ls`, with parallel execution, approval gating, and output truncation.
- **Extensions** — a `PiExtension` SPI with lifecycle events, commands, tools, UI slots, model providers, and a growing example library in [`pi-java-examples`](pi-java-examples/README.md).
- **Terminal UI** — a Ratatui-style TUI built on [TamboUI](https://tamboui.dev/): interactive chat, settings, slash commands, and theming.
- **Web UI** — a browser client served by the same agent session, with a gateway token and `--mode web --port <port>`.
- **Durable sessions** — SQLite + JSONL double-track storage, full-text search, branch caching, and crash recovery.
- **Remote sessions** — CBOR protocol with client/server modules and an RPC mode (`--mode rpc`).
- **Self-contained distribution** — a shaded fat jar plus a jlink-reduced JRE, packaged per platform by `dist.sh` / `install.sh` / `install.cmd`.

## Requirements

- **JDK 25**
- **Maven 3.9+** — or use the bundled wrapper (`./mvnw`, `mvnw.cmd`)

## Build

```bash
./mvnw clean verify            # compile + test + Checkstyle + SpotBugs
./mvnw test -pl pi-java-ai     # run one module's tests
./mvnw checkstyle:check        # code style only
```

## Quick start

```bash
# one-shot print mode
./mvnw -pl pi-java-tui -am exec:java -Dexec.args='-p "hello"'

# interactive TUI
./mvnw -pl pi-java-tui -am exec:java
```

Provider credentials come from `~/.pi-java/auth.json` or the provider's environment variable (for example `ANTHROPIC_API_KEY`). `PI_JAVA_CODING_AGENT_DIR` overrides the config directory (default `~/.pi-java/agent`). Use `-p ... --list-models` to inspect the built-in model catalog.

On Windows, `run.cmd` wraps the same commands (`run.cmd`, `run.cmd --list-models`, `run.cmd -p "hello"`).

## Web UI

```bash
./mvnw -pl pi-java-dist -am clean package -DskipTests
java -jar pi-java-dist/target/pi-java.jar --mode web --port 8787
```

Then open `http://localhost:8787`. The first start generates a gateway token at `~/.pi-java/web/gateway-token` (or set `PI_JAVA_WEB_TOKEN`); paste it into the browser prompt.

The `web-ui.sh` and `web-ui.cmd` launchers build and start the server for you. They pin this machine's JDK and Maven paths at the top of the script — edit `JAVA_HOME` if yours differ.

## Installation and packaging

```bash
./install.sh [dir]     # fat jar + jlink JRE, default ~/.local/pi-java
./install.cmd [dir]    # same on Windows, default D:\soft\pi-java
./dist.sh all          # per-platform .tar.gz packages (fat jar + JRE + launcher)
```

The fat jar is produced by `pi-java-dist` as `pi-java.jar` and is the supported distribution format.

## Modules

Dependencies flow bottom-up; each module depends only on the modules listed.

| Module | Purpose | Depends on |
|---|---|---|
| `pi-java-bom` | Bill of Materials — unified version management | — |
| `pi-java-telemetry` | metrics / tracing contracts | — |
| `pi-java-ai` | provider gateway: providers, streaming, model catalog, `pi-ai` CLI | `telemetry` |
| `pi-java-mcp` | MCP client: JSON-RPC, transports, tool adapters | `ai` |
| `pi-java-agent-core` | agent runtime: harness, tools, sessions, compaction | `ai` |
| `pi-java-session-backend-sqlite` | SQLite session storage (ServiceLoader SPI) | `agent-core` |
| `pi-java-coding-agent` | `pi-java` CLI entry point + `AgentSession` | `agent-core`, `mcp`, `session-backend-sqlite` |
| `pi-java-tui` | terminal UI on TamboUI | `agent-core`, `coding-agent` |
| `pi-java-web` | browser UI and gateway server | `agent-core`, `ai`, `coding-agent` |
| `pi-java-protocol` | CBOR codec and framing | — |
| `pi-java-client` | remote session client | `protocol` |
| `pi-java-server` | remote session server | `protocol`, `client`, `agent-core` |
| `pi-java-evals` | evaluation framework — conformance, smoke, extension tests | `agent-core`, `coding-agent` |
| `pi-java-examples` | extension examples (not bundled into `dist`) | `coding-agent`, `tui` |
| `pi-java-dist` | fat-jar assembly and distribution launchers | `coding-agent`, `tui`, `session-backend-sqlite`, `web` |

## Documentation

Development is AI-driven: every work package starts with a design document in [`docs/`](docs/), which must be reviewed before code is written.

| Document | Content |
|---|---|
| [`docs/00-ai-driven-development-process.md`](docs/00-ai-driven-development-process.md) | the eight-step development process |
| [`docs/04-open-items-register.md`](docs/04-open-items-register.md) | open items and standing decisions |
| [`docs/05-module-alignment-map.md`](docs/05-module-alignment-map.md) | quantified module alignment and gaps |
| [`docs/06-current-alignment-plan.md`](docs/06-current-alignment-plan.md) | current layer order, status, and review gates |
| [`docs/07-examples-alignment.md`](docs/07-examples-alignment.md) | examples alignment rules and capabilities |
| [`docs/08-pi-rebaseline-master-plan.md`](docs/08-pi-rebaseline-master-plan.md) | the full re-baseline design against `56cae7823` |

## Contributing

All code is AI-written and human-reviewed. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
