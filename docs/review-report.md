# pi-java 设计文档审查报告

> 审查方式：三个并行代理分别深入 pi 源码、对照 pi-java 文档、检查内部一致性，生成此综合报告。

---

## 一、总评

| 维度 | 评估 |
|------|------|
| **模块覆盖** | 🔴 缺 2 个模块（sqlite-backend、evals） |
| **功能对齐** | 🔴 AgentHarness、存储层、协议层与 pi 源码严重不一致 |
| **内部一致性** | 🔴 4 份文档之间有 **30+ 处矛盾**（接口定义、TUI 方案、模块数量等） |
| **可实施性** | 🟡 修正上述问题后可进入实施 |

---

## 二、模块覆盖差距

| pi 包 | pi-java 模块 | 状态 |
|-------|-------------|------|
| `packages/telemetry` | `pi-java-telemetry` | 🟡 存在但未设计 schema 类型系统 |
| `packages/ai` | `pi-java-ai` | 🟡 Provider 数量矛盾（5 vs 40），API 抽象不对齐 |
| `packages/agent` | `pi-java-agent-core` | 🔴 AgentHarness 设计完全不对齐（见 §三） |
| `packages/tui` | `pi-java-tui` | 🔴 架构文档说 TamboUI，实施计划说从零构建（见 §五） |
| `packages/protocol` | `pi-java-protocol` | 🔴 仅覆盖 CBOR 帧格式，缺消息词汇、快照、转录模型 |
| `packages/client` | `pi-java-client` | 🟡 优先级 P2/P3 存根 |
| `packages/server` | `pi-java-server` | 🟡 优先级 P2/P3 存根 |
| `packages/coding-agent` | `pi-java-coding-agent` | 🔴 CLI 参数、slash 命令、工具设计大面积缺失（见 §四） |
| **`packages/session-backends/sqlite-node`** | **缺失** | 🔴 pi 的主存储后端，pi-java 无对应模块 |
| **`packages/evals`** | **缺失** | 🔴 pi 的评估框架，pi-java 无对应模块 |

---

## 三、AgentHarness 不对齐（关键）

pi 的 `AgentHarness` 是一个**多车道、操作记录日志、可手动步进的持久化运行时**。pi-java 的设计是一个简化的单线程循环，API 完全不一致。

### 3.1 pi 有而 pi-java 缺失的

| pi 能力 | 文件来源 | pi-java 状态 |
|---------|---------|-------------|
| 多车道（lane）模型 | `harness/agent-harness.ts` `lane()`/`createLane()`/`lanes()`/`moveLane()` | 🟡 有 `LaneConfig`/`LaneRole` 但是发明概念，非 pi 对应 |
| 操作记录（Entry + LaneRecord） | `harness/session/types.ts` 9 种 Entry + 9 种 Record | 🔴 pi-java 的 `Operation` 密封层次完全不对应 |
| 手动驱动模式 | `agent-harness.ts` `peekAction()`/`executeAction()`/`runToCompletion()` | 🔴 pi-java 的 `run`/`continue_`/`pause`/`stop` 是发明的 API |
| 快照/订阅 | `harness/events.ts` `watch()` → `WatchHandle<Snapshot>` | 🔴 完全缺失 |
| 11 个 Hook | `agent-harness.ts` `before_run`/`before_resume`/`transform_context`/`before_request`/`before_payload`/`after_response`/`before_tool`/`after_tool`/`before_compaction`/`before_navigation`/`before_run_end` | 🔴 F19 提到但从未设计 |
| steer/followUp/nextRun 队列 | `agent-harness.ts` | 🔴 缺失 |
| skill/promptFromTemplate API | `agent-harness.ts` | 🔴 缺失 |
| compaction 作为一等操作 | `harness/compaction/compaction.ts` `CompactionSettings` + `overflow` reason + `step_attempt` record | 🔴 pi-java 只有"简单截断" |
| 分支摘要操作 | `harness/compaction/branch-summarization.ts` | 🔴 缺失 |
| 重试策略 | `agent-harness.ts` retry policy + tool execution sequential/parallel | 🔴 缺失 |

### 3.2 状态机不对齐

| pi 协议定义 | pi-java 设计 |
|------------|-------------|
| `idle` / `turn` / `compaction` / `branch_summary` / `retry` | `IDLE` / `RUNNING` / `WAITING_FOR_TOOL` / `PAUSED` / `DONE` |

pi 没有"PAUSED"状态；暂停是通过 `SuspendedOperation` 实现（crash 或 deferred 原因）。

---

## 四、coding-agent 功能缺失

### 4.1 CLI 参数

pi 有约 **40 个参数 + 7 个子命令**（`install`/`remove`/`update`/`list`/`config`/`auth`/实验性 `server`/`client`），pi-java 的 `ArgsParser` 只设计了 6 个分支（`Version`/`ListModels`/`Auth`/`Print`/`Interactive`/`Rpc`）。

缺失的关键参数：`--continue/-c`、`--resume/-r`、`--session`、`--fork`、`--name`、`--models`、`--tools/-t`、`--exclude-tools`、`--thinking`、`--offline`、`--export`、`--extension`、`--skill`、`--theme`、`--approve/-a` 等。

### 4.2 Slash 命令

pi 有 **23 个内置命令**。pi-java 只提了 F23 "内置命令 + 可扩展注册"，从未枚举。

### 4.3 工具设计

pi 的工具是带选项的工厂模式（`createXTool(cwd, options)`），有 `ToolDefinition`（含 `renderCall`/`renderResult`/`promptSnippet`）、`AgentTool`（含 `prepareArguments`/`executionMode`）、工具分组（`createCodingToolDefinitions`/`createReadOnlyToolDefinitions`）、辅助模块（`edit-diff.ts`/`file-mutation-queue.ts`/`output-accumulator.ts`）。

pi-java 的工具是静态单例 `BuiltinTools.bash()`/`.read()` 等。缺失：选项结构、渲染定义、Coding vs ReadOnly 分组、辅助模块。

---

## 五、存储层不对齐（关键）

### 5.1 pi 有两层存储，pi-java 发明了一层

| pi 实际设计 | pi-java 设计 |
|------------|-------------|
| **Coding-agent 层**：JSONL v3，cwd 编码目录，`{type:"session", version, id, timestamp, cwd, parentSession}` header | — |
| **Harness 层**：`SessionStorage`/`SessionRepo` 接口，两个实现 — `JsonlSessionStorage`（v4: header + `entry`/`record`/`lane`/`fact` mutation）和 `SqliteSessionRepository`（12 张表 + FTS 搜索） | 发明的 `~/.pi-java/sessions/index.json` + `*.jsonl.lock` + `FileLock` |
| **写安全性**：JSONL 用 in-memory tail-promise 串行化；SQLite 用 `writer_leases` 表（TTL 30s + 心跳 10s） | 发明的文件锁（pi 中没有） |

### 5.2 JSONL 行格式不对齐

| pi v4 mutation 格式 | pi-java 格式 |
|---------------------|-------------|
| `{"kind":"entry","lane":"main","id":"...","type":"message","parent_id":"...","payload":{...}}` | `{seq:1, ts:..., op:user, text:...}` |
| `{"kind":"record","lane":"main","id":"...","run_id":"...","type":"operation_started","intent":{...}}` | —（缺失） |
| `{"kind":"lane","seq":1,"lane":"main","leaf_id":"..."}` | —（缺失） |
| `{"kind":"fact","seq":1,"fact":"name","value":"..."}` | —（缺失） |

### 5.3 缺失的存储 API

pi 的 `SessionStorage` 有 `getLanes/createLane/moveLane`、`appendEntry/appendRecord`、`findEntries/findEntriesOnBranch/findRecords/findOpenOperations`、`getLog`、`getName/setName/getLabel/setLabel`、`getStats`。pi-java 的 `SessionStore` 只有 `append/read/readFrom/listSessions/branch/delete/rename`。

---

## 六、协议层不对齐

pi 的 `packages/protocol` 远不止 CBOR 编解码：

- **消息词汇**：`ClientHello`（版本握手）、`RequestEnvelope`/`ResponseEnvelope`（id 匹配的请求/响应）、`EventEnvelope`
- **命令集**：`list`/`create`/`attach`/`detach`/`prompt`/`steer`/`abort`/`set_model`/`set_thinking`
- **快照模型**：`ServerSnapshot`（serverId, protocolVersion, revision, sessions, models）、`SessionSnapshot`（phase, model, thinkingLevel, attached, locked, revision, transcript, queuedSteer）
- **转录模型**：`TranscriptItem`（user/assistant/tool）、`TranscriptProgress`（item_started/assistant_delta/item_updated/item_finished）
- **帧格式**：4 字节大端长度前缀，最大 16 MiB

pi-java 只覆盖了帧格式和 CBOR 编解码器。

---

## 七、内部一致性（30+ 处矛盾）

### 关键矛盾

| 编号 | 问题 | 涉及文件 |
|------|------|---------|
| **INC-1** | TUI 方案矛盾：架构选 TamboUI，实施计划 Phase 3 从零构建 `DifferentialRenderer`/`UnixTerminal`/`VStack` | `02-architecture-design.md` vs `04-implementation-plan.md` |
| **INC-2** | TUI ↔ coding-agent 循环依赖：`PiTuiApp` 持有 `AgentSession`，coding-agent 又依赖 tui | `03-detailed-design.md` |
| **INC-3** | `Operation` 类型名三份文档三个版本：`UserMessageOp` vs `UserMessage` vs `UserMessage(Instant ts)` vs `UserMessage(Instant timestamp)` | `02` vs `03`（同一文档内还定义了两次） |
| **INC-4** | `AgentHarness` API 三份文档三个版本 | `02` §4.2 / `03` §2.2 |
| **INC-5** | `@FunctionalInterface` 标注在 4 个抽象方法的接口上（无法编译） | `02-architecture-design.md` |
| **INC-6** | `ProviderApi` 允许 `ResponsesApi`，但详细设计中不存在 `ResponsesApi` | `02` vs `03` |
| **INC-7** | `StreamApi` 不是 `ProviderApi` 子类型，无法通过 Provider SPI 获取 | `02` / `03` |
| **INC-8** | 1:1 约束 vs 首批 5-6 provider（自相矛盾） | `01-requirements-analysis.md` |
| **INC-9** | "JSONL 可以不同" vs "与 pi 1:1 对齐" | `01-requirements-analysis.md` |
| **INC-10** | 模块数 8/9/10 不一致 | `02` / `04` |
| **INC-11** | 命名空间 `dev.pi-java.*` 含连字符，JPMS 非法 | `01` vs `02` |
| **INC-12** | `StatusBar` 引用 `session.modelId()`，但 `SessionInfo` 无此字段 | `03-detailed-design.md` |
| **INC-13** | `ArgsParser` 手写 vs picocli | `03` vs `02`/`04` |
| **INC-14** | P0 会话功能排在最末阶段（Phase 4） | `01` vs `04` |
| **INC-15** | F8 HTTP 代理/F19 事件系统从未在实施计划中出现 | `01` vs `04` |
| **INC-16** | Phase 2 工时 3-4 周但任务合计 ~24 人天（约 5 周） | `04-implementation-plan.md` |

---

## 八、修复优先级

### P0 — 阻塞实施（必须先修）

1. **存储层重新设计**：对齐 pi 的 `SessionStorage`/`SessionRepo` 接口 + v4 JSONL 格式 + sqlite-node 后端
2. **AgentHarness 重新设计**：对齐 pi 的多车道、操作记录（Entry + LaneRecord）、手动驱动、快照、Hook、队列模型
3. **TUI 方案统一**：全部对齐 TamboUI（架构、详细设计、实施计划一致），消除循环依赖
4. **接口定义统一**：`Operation`/`Tool`/`AgentHarness` 在三份文档中保持一致

### P1 — 功能补齐（实施前修）

5. **模块补齐**：新增 `pi-java-session-backend-sqlite` 和 `pi-java-evals` 模块
6. **CLI 参数 + Slash 命令枚举**：补全 ~40 参数 + 23 命令
7. **协议层扩展**：补充消息词汇、命令集、快照/转录模型
8. **工具设计深化**：对齐 pi 的选项结构、ToolDefinition、AgentTool、分组
9. **解决 1:1 约束矛盾**：明确 provider 数量策略（全量 40 或明确声明差异）

### P2 — 细节修正（实施中修）

10. **命名空间修正**：JPMS 模块名去掉连字符
11. **Phase 2 工时重估**：与任务列表对齐
12. **P0 功能优先级调整**：会话持久化不要放在 Phase 4
13. **实施计划补充遗漏**：F8 HTTP 代理、F19 事件系统、F18 OTel 适配器
