# pi-java

**pi 的纯 Java（JDK 25）复刻版** —— 一个 AI 编码代理与运行时。它用惯用的 Java 语言特性（record、sealed interface、模式匹配、虚拟线程）重建 pi 的三层：coding-agent CLI、通用 Agent 运行时，以及多 provider 的 LLM 网关。

[English](README.md) | 简体中文

> **状态** —— 项目按层滚动对齐 pi，当前锁定锚点 `56cae7823`。L0–L6 层（telemetry、ai、mcp、agent-core、session/SQLite、coding-agent、evals）已完成对齐闭环（少量平台窄差登记在 `docs/04`）；L8 层（examples 示例库）实施中；L7 层（剩余 TUI/web 面）尚未开始。当前执行顺序与状态见 [`docs/06-current-alignment-plan.md`](docs/06-current-alignment-plan.md)。

## 特性

- **多 provider 网关** —— Anthropic、OpenAI、Google、Mistral、DeepSeek 统一在一个 `StreamApi` 之下，并支持 OpenAI 兼容目录（xAI、Moonshot/Kimi、z.ai、OpenRouter，以及用户自定义的 `models.json` provider）。含 SSE 流式、工具调用、图片、OAuth，以及可编程的 `FauxProvider` 用于离线回放。
- **Agent 运行时** —— `AgentHarness` 是一个显式状态机（`peekAction` / `executeAction`），支持多 lane 编排、生命周期钩子、上下文压缩、技能、提示模板与扩展钩子。
- **内置工具** —— `bash`、`powershell`、`read`、`write`、`edit`、`grep`、`find`、`ls`，支持并行执行、审批门与输出截断。
- **扩展机制** —— `PiExtension` SPI 覆盖生命周期事件、命令、工具、UI 槽位与模型 provider；示例库见 [`pi-java-examples`](pi-java-examples/README.md)。
- **终端 UI** —— 基于 [TamboUI](https://tamboui.dev/) 的 Ratatui 风格 TUI：交互对话、设置、slash 命令与主题。
- **Web UI** —— 与同一 agent 会话相连的浏览器客户端，带网关 token，使用 `--mode web --port <port>` 启动。
- **持久化会话** —— SQLite + JSONL 双轨存储、全文检索、分支缓存与崩溃恢复。
- **远程会话** —— CBOR 协议与 client/server 模块，并提供 RPC 模式（`--mode rpc`）。
- **自包含分发** —— 阴影打包的 fat jar 加 jlink 精简 JRE，由 `dist.sh` / `install.sh` / `install.cmd` 按平台打包。

## 环境要求

- **JDK 25**
- **Maven 3.9+** —— 或使用仓库自带的 wrapper（`./mvnw`、`mvnw.cmd`）

## 构建

```bash
./mvnw clean verify            # 编译 + 测试 + Checkstyle + SpotBugs
./mvnw test -pl pi-java-ai     # 只跑单个模块的测试
./mvnw checkstyle:check        # 只做代码风格检查
```

## 快速开始

```bash
# 一次性 print 模式
./mvnw -pl pi-java-tui -am exec:java -Dexec.args='-p "hello"'

# 交互式 TUI
./mvnw -pl pi-java-tui -am exec:java
```

Provider 凭证来自 `~/.pi-java/auth.json` 或对应 provider 的环境变量（例如 `ANTHROPIC_API_KEY`）。`PI_JAVA_CODING_AGENT_DIR` 可覆盖配置目录（默认 `~/.pi-java/agent`）。用 `-p ... --list-models` 查看内置模型目录。

Windows 下 `run.cmd` 封装了同样的命令（`run.cmd`、`run.cmd --list-models`、`run.cmd -p "hello"`）。

## Web UI

```bash
./mvnw -pl pi-java-dist -am clean package -DskipTests
java -jar pi-java-dist/target/pi-java.jar --mode web --port 8787
```

然后打开 `http://localhost:8787`。首次启动会在 `~/.pi-java/web/gateway-token` 生成网关 token（也可设置 `PI_JAVA_WEB_TOKEN`），在浏览器提示框里粘贴即可。

`web-ui.sh` 与 `web-ui.cmd` 会自动完成构建并启动服务。两个脚本顶部固定了本机的 JDK 与 Maven 路径，若你的路径不同请修改 `JAVA_HOME`。

## 安装与打包

```bash
./install.sh [dir]     # fat jar + jlink JRE，默认 ~/.local/pi-java
./install.cmd [dir]    # Windows 同上，默认 D:\soft\pi-java
./dist.sh all          # 按平台生成 .tar.gz 包（fat jar + JRE + 启动器）
```

`pi-java-dist` 产出的 `pi-java.jar` 是受支持的分发格式。

## 模块

依赖自下而上；每个模块只依赖表中列出的模块。

| 模块 | 用途 | 依赖 |
|---|---|---|
| `pi-java-bom` | Bill of Materials —— 统一版本管理 | — |
| `pi-java-telemetry` | metrics / tracing 契约 | — |
| `pi-java-ai` | provider 网关：provider、流式、模型目录、`pi-ai` CLI | `telemetry` |
| `pi-java-mcp` | MCP 客户端：JSON-RPC、传输层、工具适配 | `ai` |
| `pi-java-agent-core` | Agent 运行时：harness、工具、会话、压缩 | `ai` |
| `pi-java-session-backend-sqlite` | SQLite 会话存储（ServiceLoader SPI） | `agent-core` |
| `pi-java-coding-agent` | `pi-java` CLI 入口 + `AgentSession` | `agent-core`、`mcp`、`session-backend-sqlite` |
| `pi-java-tui` | 基于 TamboUI 的终端 UI | `agent-core`、`coding-agent` |
| `pi-java-web` | 浏览器 UI 与网关服务 | `agent-core`、`ai`、`coding-agent` |
| `pi-java-protocol` | CBOR 编解码与分帧 | — |
| `pi-java-client` | 远程会话客户端 | `protocol` |
| `pi-java-server` | 远程会话服务端 | `protocol`、`client`、`agent-core` |
| `pi-java-evals` | 评测框架 —— 一致性、冒烟、扩展示例测试 | `agent-core`、`coding-agent` |
| `pi-java-examples` | 扩展示例（不进 `dist`） | `coding-agent`、`tui` |
| `pi-java-dist` | fat jar 组装与分发启动器 | `coding-agent`、`tui`、`session-backend-sqlite`、`web` |

## 文档

开发由 AI 驱动：每个工作包先写 [`docs/`](docs/) 下的设计文档，审核通过后才写代码。

| 文档 | 内容 |
|---|---|
| [`docs/00-ai-driven-development-process.md`](docs/00-ai-driven-development-process.md) | 八步开发流程 |
| [`docs/04-open-items-register.md`](docs/04-open-items-register.md) | 开放项与有效裁决 |
| [`docs/05-module-alignment-map.md`](docs/05-module-alignment-map.md) | 逐模块量化对齐与缺口 |
| [`docs/06-current-alignment-plan.md`](docs/06-current-alignment-plan.md) | 当前层顺序、状态与审核门 |
| [`docs/07-examples-alignment.md`](docs/07-examples-alignment.md) | examples 对齐口径与能力清单 |
| [`docs/08-pi-rebaseline-master-plan.md`](docs/08-pi-rebaseline-master-plan.md) | 针对 `56cae7823` 的换锚总设计 |

## 贡献

所有代码由 AI 编写、人工审核。见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

[MIT](LICENSE)
