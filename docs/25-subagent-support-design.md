# 25 — Subagent 支持设计

> 对齐基准：pi `packages/coding-agent/examples/extensions/subagent/`（README + index.ts + agents/*.md）。
> 关联：`docs/23`（run/turn/tool 生命周期事件，subagent 流式回传依赖其 `tool_execution_update`）、
> `docs/24`（扩展系统，subagent 以扩展形式落地）。
> 编制日期：2026-09-12。

## 0. 结论先行

1. **pi 的 subagent 不是内核能力**，而是官方**扩展示例**：`packages/coding-agent/src` 里搜
   `subagent` **0 命中**，只有 `examples/extensions/subagent/`。其 README 第 7 行写明实现形态：
   > **Isolated context**: Each subagent runs in a separate `pi` **process**

2. **pi-java 目前没有 subagent**，但有内核级 lane 机制（且只服务于 fork/clone，见 §2.3）。

3. **推荐路径 1**：以**扩展形式**实现 `subagent` 工具，内部 spawn `pi-java --mode json` 子进程。
   与 pi 完全对齐，且 pi-java 已有全部前置能力（§2.1）。

4. **前置依赖**：子 agent 的流式回传需要 `docs/23` 正在设计的 `tool_execution_update` 通道——
   当前 `ToolExecutor.executeRaw` 给 `onUpdate` 传的是 `null`。两者应一起排期（§5）。

---

## 1. pi 的 subagent 模型（基准）

### 1.1 构成

```
examples/extensions/subagent/
├── index.ts       # 扩展入口：注册 subagent 工具
├── agents.ts      # agent 发现
├── agents/        # scout.md / planner.md / reviewer.md / worker.md
└── prompts/       # implement.md / scout-and-plan.md / implement-and-review.md
```

### 1.2 Agent 定义 = markdown + YAML frontmatter

```markdown
---
name: scout
description: Fast codebase recon
tools: read, grep, find, ls, bash
model: claude-haiku-4-5
---

System prompt for the agent goes here.
```

发现位置：

| 位置 | 默认 | 说明 |
|---|---|---|
| `~/.pi/agent/agents/*.md` | ✅ 加载 | user 级 |
| `.pi/agents/*.md` | ❌ 需开启 | 项目级；repo 控制的 prompt，需 `agentScope: "project"|"both"` 且交互确认 |

### 1.3 工具契约

一个 `subagent` tool，三种模式：

| 模式 | 参数 | 说明 |
|---|---|---|
| Single | `{ agent, task }` | 一个 agent 一个任务 |
| Parallel | `{ tasks: [...] }` | 并发；**上限 8 任务、4 并发** |
| Chain | `{ chain: [...] }` | 串行，`{previous}` 占位符引用上一步输出 |

### 1.4 能力与边界

- 流式输出（工具调用与进度实时回传）、usage 统计（turns / tokens / cost / ctx）
- abort 传播：Ctrl+C 杀死子进程
- 并行模式每个任务返回给父模型的输出**上限 50 KB**，完整结果留在 tool details
- 失败处理：exit code != 0、`stopReason == "error"` 传播错误消息；chain 模式在首个失败步停止
- Agent 每次调用**重新发现**（允许会话中编辑 agent 定义）

---

## 2. pi-java 现状盘点

### 2.1 已具备的前置能力 ✅

| 能力 | 位置 | 说明 |
|---|---|---|
| **机器可读输出** | `PrintMode.runJson:51-61` | `--mode json` 逐行 `System.out.println(JsonEventMapper.toStreamEventWire(event))` |
| 模型选择 | `Args.java:58` | `--model`，支持 `provider/id` 与 `:thinking` |
| 工具白/黑名单 | `Args.java:77-78` | `--tools` / `--exclude-tools`（+ `noTools` / `noBuiltinTools`） |
| 系统提示词 | `Args.java:61-62` | `--system-prompt` / `--append-system-prompt` |
| 一次性 prompt | `Main.java:95-97` | `-p` 走 `PrintMode` |
| 工具接口 | `AgentTool.execute:79-85` | 5 参，含 `ToolUpdateCallback<TDetails> onUpdate` 与 `AbortSignal` |
| frontmatter 解析 | `PromptTemplates.parseFrontmatter:119` | 返回 `Parsed(frontmatter, body)`；⚠️ **package-private static** |
| 子进程 + 虚拟线程读流 | `DefaultShellExecutor:78-91` | 可作为进程管理参考实现 |
| 扩展注册工具 | `ExtensionContext.tools()` | subagent 以扩展形式挂载 |
| 中断信号 | `AbortSignal`（`ai/AbortSignal.java`） | 工具执行时可感知 |

**关键结论**：`pi-java --mode json --model M --tools a,b --system-prompt P -p "task"`
这条命令已经完全可用——路径 1 所需的子进程协议**不需要任何内核改动**。

### 2.2 缺失的部分 🔴

| 缺口 | 说明 |
|---|---|
| `SubagentTool` | 工具本身 |
| agent 发现与解析 | `~/.pi-java/agent/agents/*.md` 的扫描与 frontmatter 提取 |
| 并发编排 | single / parallel（8/4 限制）/ chain（`{previous}`） |
| 流式回传 | 子进程 stdout → `onUpdate`（**被 `executeRaw` 传 null 阻塞**，§5） |
| abort 传播 | 父 abort → kill 子进程 |
| usage 聚合 | turns / tokens / cost 汇总 |

### 2.3 lane 现状：有机制，但只服务 fork/clone

`AgentHarness.createLane(LaneConfig)` / `lanes()` / `moveLane()` 存在，`LaneConfig` 带
`activeTools` 与 `systemPrompt` override——**恰好是 subagent 一半的能力**。
但当前 lane 只被 `AgentSession.forkFromEntry` / `forkCopy` 用作会话分支，
且 `SessionRunner` 只驱动 `owner.laneName()` 单个 lane。

lane 若要做进程内 subagent，还缺 4 项：

| # | 缺口 |
|---|---|
| ① | `LaneConfig` 无 `model` 字段 |
| ② | 无多 lane 并发驱动（需多 `SessionRunner` 或 `runToCompletion(laneName)` 编排） |
| ③ | 无结果回传（子 lane transcript → `ToolResultMessage` 注入主 lane） |
| ④ | 无 lane 级 abort 父子联动 |

---

## 3. 方案对比

| 维度 | 路径 1：扩展 + 子进程（**推荐**） | 路径 2：内核级 lane subagent | 路径 3：MCP 式外部 agent |
|---|---|---|---|
| 与 pi 对齐 | ✅ 完全一致 | ❌ pi 没有 | 部分 |
| 隔离级别 | **进程级**（最彻底） | 仅上下文；共享 JVM 与工具实例 | 进程级 |
| 成本 | 中（~250–300 行） | 高（并发驱动 + 回传 + abort 联动） | 中高（需 MCP client） |
| 内核改动 | **零** | 大（`LaneConfig`、`SessionRunner`、`LaneState`） | 需 MCP 集成 |
| 依赖 | 零 | 零 | MCP SDK |
| GraalVM native | ✅ 兼容（子进程是 native 可执行文件） | ✅ | 需验证 |
| 主要风险 | 进程启动开销 | **bash 工具状态串扰**（共享实例）；`LaneState` 已 20+ 字段再扩复杂度高 | 生态依赖 |

**推荐路径 1**，理由：
1. pi 验证过的模型，行为对齐成本最低，贴合 `docs/19`–`24` 一贯的「对齐 pi」主线
2. **零内核改动**——作为 `PiExtension` 注册一个工具即可
3. pi-java 的 `--mode json` 输出比 pi 的 TUI 输出更规整，解析更容易

**路径 2 不在本期**：并发驱动多 lane 会显著抬升 `SessionRunner` / `LaneState` 复杂度，
而收益（省进程开销）在 subagent 这种低频场景不明显；共享 JVM 还会引入新的并发风险。
其 4 项缺口已在 §2.3 记录，留作后续选项。

---

## 4. 路径 1 详细设计

### 4.1 整体架构

```
父 agent（主 lane）
  └─ LLM 输出 subagent 工具调用
       └─ SubagentTool.execute(toolCallId, params, signal, onUpdate, ctx)
            ├─ 解析 agent 定义（SubagentRegistry）
            ├─ 编排（single / parallel / chain）
            └─ 每个任务：SubagentProcess
                 ├─ ProcessBuilder: pi-java --mode json --model M --tools T
                 │                   --system-prompt P -p "<task>"
                 ├─ 虚拟线程读 stdout（每行一个 JSON 事件）
                 │     → JsonEventMapper 解析 → onUpdate 流式回传
                 └─ signal 被 abort → destroyForcibly()
            └─ 汇总 → ToolResult（content + details: usage/per-task）
```

### 4.2 Agent 定义与发现

```java
public record SubagentDefinition(
    String name,
    String description,
    List<String> tools,      // null = 全部默认工具
    String model,            // null = 继承父
    String systemPrompt      // markdown 正文
) {}
```

发现逻辑（`SubagentRegistry`）：

| 位置 | 默认 | 对应 pi |
|---|---|---|
| `~/.pi-java/agent/agents/*.md` | ✅ | `~/.pi/agent/agents` |
| `.pi/agents/*.md` | ❌ 需 `subagent.projectAgents` 设置开启 + 确认 | `.pi/agents` |

- 复用 `PromptTemplates.parseFrontmatter`（`PromptTemplates.java:119`）提取 frontmatter；
  ⚠️ 该方法是 **package-private**，需提升为 `public` 或在 `prompt` 包内提供 `SubagentDefinitions`
- **每次调用重新发现**（对齐 pi「Agents discovered fresh on each invocation」），允许会话中改 agent
- 项目级 agent 属 repo 控制的 prompt，默认关闭；开启时需宿主确认（TUI/RPC 走 `ExtensionUI`，
  无 UI 时拒绝加载——对齐 pi `hasUI === false` 的处理）

### 4.3 SubagentTool

实现 `AgentTool<SubagentInput, SubagentDetails>`：

```java
@Override public String name() { return "subagent"; }
@Override public String label() { return "subagent"; }
@Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }

@Override
public ToolResult<SubagentDetails> execute(String toolCallId, SubagentInput params,
        AbortSignal signal, ToolUpdateCallback<SubagentDetails> onUpdate,
        ToolContext context) throws Exception {
    // 三模式分派
}
```

`inputSchema` 三模式（对齐 pi）：

| 模式 | 字段 |
|---|---|
| single | `agent`（enum: 已发现 agent 名）、`task` |
| parallel | `tasks: [{agent, task}]`（上限 8） |
| chain | `chain: [{agent, task}]`，task 内支持 `{previous}` |

> 建议：**单一工具 + 三选一字段**，与 pi 一致；不做成三个工具。

### 4.4 子进程管理

参考 `DefaultShellExecutor:78-91` 的虚拟线程读流模式：

```java
var pb = new ProcessBuilder(cmd);
pb.directory(Path.of(context.cwd()).toFile());
pb.redirectErrorStream(true);
var process = pb.start();

try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var future = executor.submit(() -> readAndParse(process, onUpdate));
    // abort 监听：signal.isAborted() → process.destroyForcibly()
    int exitCode = future.get(timeout);
}
```

- 可执行文件定位：优先 `ProcessHandle.current().info().command()`（当前进程路径），
  回落到 `pi-java`（PATH）
- **abort 传播**：`ToolContext` 外通过 `signal` 轮询；轮询间隔 ~50ms，命中即 `destroyForcibly()`

### 4.5 事件解析与流式回传

子进程 stdout 每行 = `JsonEventMapper.toStreamEventWire(event)`。解析后：

| 事件 | 处理 |
|---|---|
| `text_delta` / `thinking_delta` | 累积到当前任务的输出缓冲 |
| `toolcall_start` / `_end` | 记入 tools 列表（用于 UI 展示工具调用） |
| `usage` | 累计 tokens / cost |
| `done` / `error` | 任务结束，记录 `stopReason` |

每解析若干行（或每个关键事件）调用一次 `onUpdate.accept(partialResult)` 回传进度。

> ⚠️ **阻塞点**：当前 `ToolExecutor.executeRaw` 给 `onUpdate` 传 `null`，
> `AgentTool.execute` 拿不到回调 → 流式回传无法生效。见 §5。

### 4.6 三模式编排

| 模式 | 实现 |
|---|---|
| single | 直接跑一个 `SubagentProcess` |
| parallel | `Executors.newVirtualThreadPerTaskExecutor()`；**上限 8 任务、4 并发**（对齐 pi）；按完成顺序收集 |
| chain | 串行；每步的 task 文本把 `{previous}` 替换为上一步输出；**首个失败即停止**并报告失败步 |

### 4.7 结果回传与错误处理

- `ToolResult.content`：
  - single → 子 agent 最终文本
  - parallel → 每个任务一段（**每任务上限 50 KB**，对齐 pi；完整内容放 details）
  - chain → 最后一步输出
- `ToolResult.details`：`SubagentDetails(usage, perTask: [{agent, status, turns, tokens, cost, exitCode}])`
- 错误路径（对齐 pi）：

| 情况 | 处理 |
|---|---|
| exitCode != 0 | 返回错误 + stderr |
| `stopReason == "error"` | 传播错误消息 |
| `stopReason == "aborted"` | kill 子进程，抛异常（harness 包装为错误结果） |
| chain 某步失败 | 停止，报告失败步与已完成步 |

### 4.8 扩展注册

```java
public final class SubagentExtension implements PiExtension {
    @Override public String name() { return "subagent"; }
    @Override public void register(ExtensionContext ctx) {
        ctx.tools().register(SubagentTool.create(new SubagentRegistry(ctx.settings())));
    }
}
```

JAR 内 `META-INF/services/com.pijava.coding.agent.extension.PiExtension`（`docs/24` §1）。

---

## 5. 依赖关系（必须一起排期）

| 依赖 | 说明 | 出处 |
|---|---|---|
| **`tool_execution_update` 通道** | `ToolExecutor.executeRaw` 目前 `onUpdate` 传 `null`；需新增带回调的重载，否则子 agent 的流式回传完全不可见 | `docs/23` §4.5 ② |
| 扩展注册工具 | 已具备（`ExtensionContext.tools()`） | `docs/24` §1 |
| `parseFrontmatter` 可见性 | 目前 package-private；需提升或在 `prompt` 包内提供解析入口 | §4.2 |

> **建议排期**：先落地 `docs/23 §4.5` 的 `executeRaw` 回调重载（改动小、独立），
> 再做 subagent，否则做完了看不到流式效果。

---

## 6. 明确不做（本期）

| 项 | 理由 |
|---|---|
| 路径 2（内核级 lane subagent） | §3；4 项缺口已记录，留作后续 |
| TUI collapsed/expanded 视图（Ctrl+O） | 需 TUI 侧组件模型，与 `docs/24` §5.3 的「TUI 扩展 UI」同属 P2 |
| workflow prompts（`/implement` 等） | 属 prompt 模板范畴；pi 也是独立文件，可后补 |
| provider 层改动 | 与 `docs/22 §6` 一致的原则：无真实需求不做 |
| 子 agent 结果的持久化到会话树 | 子进程结果仅作为 toolResult 入主会话，不单独建 lane |

---

## 7. 实施步骤

1. `ToolExecutor.executeRaw` 新增带 `ToolUpdateCallback<?>` 的重载并透传（`docs/23 §4.5 ②`）
2. `SubagentDefinition` + `SubagentRegistry`（发现 + frontmatter 解析 + 项目级开关）
3. `SubagentProcess`（ProcessBuilder + 虚拟线程读流 + abort kill + 事件解析）
4. `SubagentTool`（inputSchema 三模式 + 编排 + 结果汇总 + 50KB cap）
5. `SubagentExtension`（`PiExtension` 实现 + SPI 注册）
6. 测试：`SubagentRegistryTest`（发现/覆盖/项目级关闭）、`SubagentProcessTest`（正常/非零退出/abort）、
   `SubagentToolTest`（single/parallel/chain、8 任务上限、chain 失败停止、50KB 截断）

## 8. 验收

- `mvn clean verify` 零错误零警告
- 手工：`--mode json` 下让主 agent 调 `subagent`，确认
  - 子进程启动、stdout 事件被解析、`onUpdate` 有回传（依赖步骤 1）
  - abort 时子进程被 kill
  - parallel 模式 8 任务上限生效、并发 ≤ 4
  - chain 模式 `{previous}` 正确替换、失败步停止
- 项目级 agent 默认不加载；开启后需确认
- native 构建下功能正常（子进程为 native 可执行文件）
