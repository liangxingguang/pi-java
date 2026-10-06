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

当前主线是**按「对齐包」推进**，不再按 Phase：每个包先写 `docs/NN-xxx-design.md`（从 `docs/03-detailed-design.md` 对应章节扩展，带 pi 的 `file:line` 与逐字代码），用户审核通过后才许写代码；闭环时回填该包文首 banner ＋ `docs/05` 台账 ＋ `docs/08` 的任务表。

## 设计文档

| 文档 | 内容 |
|------|------|
| `docs/01-requirements-analysis.md` | 35 功能需求 + 10 非功能需求 |
| `docs/02-architecture-design.md` | 11 模块结构、分层依赖、核心接口 |
| `docs/03-detailed-design.md` | 类级设计：Entry/LaneRecord、AgentHarness、SessionStorage/Repository、SQLite schema、JSONL v4 格式、TamboUI 业务组件、23 slash 命令、~40 CLI 参数 |
| `docs/04-implementation-plan.md` | Phase 0–6、13–17 周 MVP、风险矩阵 |
| `docs/05-open-items-register.md` | 未结项台账（「还剩什么没对齐」的唯一权威） |
| `docs/06-module-alignment-map.md` | 模块对齐地图（量化）；明细在 `docs/map/` |
| `docs/07-gap-inventory.md` | 模块缺口清单与补全计划 |
| `docs/08-ai-next-work-items-design.md` | `pi-java-ai` 下一步待做清单与设计入口 |
| `docs/09-request-side-thinking-cache-eager-tools-design.md` | 包 09：思考级别 clamp · Responses 缓存门 · Anthropic 工具流式（✅ 已闭环） |
| `docs/10-google-mistral-thinking-design.md` | 包 10：Google / Mistral 车道的思考配置（✅ 已闭环） |
| `docs/11-pi-reanchor-ruling.md` | pi 换锚裁决（`3390bd936` → `200387122`）；锚点事实在 `docs/map/ANCHOR.md` |
| `docs/12-jsonl-wire-format-alignment-design.md` | 包 12：JSONL 线格式对齐（✅ 已闭环） |
| `docs/13-context-edit-alignment-design.md` | 包 13：context_edit 对齐（✅ 已闭环） |
| `docs/14-pi-native-message-spellings-design.md` | 包 14：pi 原生消息拼写/双向互读（✅ 已闭环） |
| `docs/15-entry-discriminant-single-source-design.md` | 包 15：Entry 判别常量单一事实源（✅ 已闭环） |
| `docs/16-runid-usage-query-ruling-design.md` | 包 16：runId/usage 查询裁决（✅ 已闭环） |
| `docs/17-compaction-cutpoint-projection-design.md` | 包 17：压缩切点改读 context_edit 后投影（✅ 已闭环） |
| `docs/18-split-turn-summary-design.md` | 包 18：split-turn 的 turn-prefix 二次摘要（✅ 已闭环） |
| `docs/19-retry-omission-edit-design.md` | 包 19：环 A 重试前持久 omission edit（✅ 已闭环） |
| `docs/20-projected-index-entry-resolution-design.md` | 包 20：省略目标定位的投影下标兜底（✅ 已闭环） |
| `docs/21-history-summarization-prompt-alignment-design.md` | 包 21：历史摘要主 prompt 与请求参数对齐（✅ 已闭环） |
| `docs/22-summarization-thinking-level-alignment-design.md` | 包 22：摘要请求透传会话思考级别（✅ 已闭环） |
| `docs/23-custom-instructions-channel-design.md` | 包 23：压缩 customInstructions 通道接线（✅ 已闭环） |

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
- [模块对齐地图（量化）](docs/06-module-alignment-map.md) — 逐模块加权完成度 + 缺口 + 计划；明细在 `docs/map/`
  （⚠️ 2026-09-20：原 `docs/phase1-pi-code-mapping.md` 已删 —— 它映射到 pi **已删除**的目录结构、
    其「~92% 完成度」出自被此后 49% 的提交超越的旧文档，且与本项目判据（行为）不可通约）

## 运行环境
jdk:D:\soft\jdk\graalvm-jdk-25
maven：D:\soft\apache-maven-3.9.9
