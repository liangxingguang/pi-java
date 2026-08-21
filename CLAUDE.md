# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**pi-java** — Pure Java (JDK 25) 复刻 [pi](https://github.com/earendil-works/pi) AI 编码代理。代码在 `D:\workplaceForai\pi-java`，参考原项目在 `D:\workplaceForai\pi`。

11 个 Maven 模块，依赖方向自下而上：

```
telemetry ← ai ← agent ← coding-agent
                         ← tui
              agent ← session-backend-sqlite
              coding-agent ← evals
              protocol ← client
              protocol ← server
```

命名空间：`com.pijava`（包名） / `com.pi-java`（Maven groupId）。

## 技术栈

JDK 25 · Maven 4.x · Jackson (JSON/CBOR) · [TamboUI](https://tamboui.dev/) (终端 UI，源自 Ratatui) · SQLite (`xerial/sqlite-jdbc`) · Picocli · JUnit 5 + AssertJ

## 构建设计阶段

commands: `mvn clean verify`, `mvn test -pl <module>`, `mvn checkstyle:check`, `mvn -Pnative package`（GraalVM Native Image）

## 开发流程

所有代码由 AI 写，人只审核。每个 Phase 按 8 步推进。详见 `docs/00-ai-driven-development-process.md`。

每个阶段第一项任务是编写 `docs/XX-phaseN-xxx-design.md`（从 `03-detailed-design.md` 对应章节扩展）。阶段设计文档是实施蓝图，后续整理为开发教程。

## 设计文档

| 文档 | 内容 |
|------|------|
| `docs/01-requirements-analysis.md` | 35 功能需求 + 10 非功能需求 |
| `docs/02-architecture-design.md` | 11 模块结构、分层依赖、核心接口 |
| `docs/03-detailed-design.md` | 类级设计：Entry/LaneRecord、AgentHarness、SessionStorage/Repository、SQLite schema、JSONL v4 格式、TamboUI 业务组件、23 slash 命令、~40 CLI 参数 |
| `docs/04-implementation-plan.md` | Phase 0–6、13–17 周 MVP、风险矩阵 |

## 编码规范

- **Erasable Java（代数数据类型）**：优先用 `record` + `sealed interface` + `switch` 模式匹配表达 ADT；`enum` 与 sealed 按数据形状取舍——**纯常量闭集用 `enum`**（判别字面量、错误码、状态、选项开关；需 snake_case/字符串序列化时用 `@JsonValue`，不手写 switch），**变体携带不同字段或行为时用 `sealed interface` + `record`**。不绝对禁用 `enum`（Java `enum` 是常态构造，无 pi TS `enum` 的运行时问题）；也不为凑「无 enum」而用 sealed 空 record 硬造纯常量枚举。
- **无 `@SuppressWarnings`**（除非有注释说明）
- **文件 ≤ 500 行**，超过则拆分
- **Commit 粒度**：每个可独立编译的模块完成即 commit（200–500 行/次）
- **Commit 格式**：`{feat,fix,docs}({module}): <message>`，如 `feat(ai): implement Anthropic provider streaming`
- **Stage 显式路径**：`git add <path>`，永远不用 `git add -A` 或 `git add .`
- **不 push main**，不 force push
- **PR 前自验证**：`mvn clean verify` 零错误零警告、测试通过、无 `System.out.println` 残留

## SDK 入口点（实现后）

- `pi-java-coding-agent`: `com.pijava.coding.agent.Main.main()` — `pi-java` CLI 入口
- `pi-java-ai`: `com.pijava.ai.cli.AiCli` — `pi-ai` 模型管理 CLI
- `pi-java-agent-core`: `com.pijava.agent.AgentHarness` — Agent 运行时主类
- `pi-java-tui`: `com.pijava.tui.PiTuiApp` — 交互模式入口

## pi 项目代码路径
D:\workplaceForai\pi

## pi ↔ pi-java 对照表
- [Phase 1-6 代码对照表](docs/phase1-pi-code-mapping.md) — 逐文件/包级对齐度 + 差异说明（Phase 1-6 全模块）

## 运行环境
jdk:D:\soft\jdk\graalvm-jdk-25
maven：D:\soft\apache-maven-3.9.9
