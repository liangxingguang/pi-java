# 20 — Agent Loop 优化方案（P0/P1 缺陷修复 + 防漂移机制）

> 上一篇：`docs/19-pi-agent-loop-behavior-diffs.md`（差异清单与根因）。
> 本篇给出落地方案。编制日期：2026-09-10。

## 0. 目标与根因回顾

`docs/19` 记录 5 处行为差异，根因是：**pi-java 对齐的是 pi 的 `harness/agent-harness.ts` 状态机骨架，
而该骨架几乎全部未实现**（`peekAction`/`executeAction`/`runToCompletion` 等均
`return this.unavailable(...)` → 抛 `HarnessNotImplemented`，见 `agent-harness.ts:355-357, 413-421`）。
pi 真正在用的是 `agent-loop.ts` 的 async 嵌套双层 while，**行为细节只存在于后者**。

因此优化分两层目标：

| 目标 | 手段 | 层 |
|---|---|---|
| 消掉已发现的差异 | 修 5 处缺陷 | L0 + L1 |
| 让差异不再产生 | 把「循环不变量」从人工约定变成机制 | L2 |

只做前者，下一个迭代还会漂出新的差异。

---

## 1. 分层总览

| 层 | 内容 | 工作量 | 优先级 |
|---|---|---|---|
| **L0 止血** | ① `executionMode` 参与并行判定 ② `anyTerminate` → `allTerminate` | 0.5 天 | **立即** |
| **L1 补语义** | ③ `length` 截断保护 ④ 参数 schema 运行时校验 ⑤ `before_tool` 的 `terminate` 通道 | 2–3 天 | 高 |
| **L2 防漂移** | ⑥ `peekAction` 不变量断言 ⑦ golden-trace 序列测试 ⑧ 行为条款映射表 | 2 天 | **必做** |
| **L3 可选** | ⑨ 补齐 pi `ActionInfo` 缺失动作，转移显式化 | 5 天+ | 观望 |

---

## 2. L0 止血（P0）

### 2.1 ① 让 `AgentTool.executionMode()` 参与批量判定

**问题**：`executionMode()` 已定义且内置工具已标注（`BashTool:76`、`WriteTool:52`、`EditTool:76` 为
`Sequential`；`ReadTool:58`、`LsTool:49`、`GrepTool:56`、`GlobTool:50` 为 `Parallel`），
但批量决策完全不读该字段。默认 `ToolExecution.defaultMode() == Parallel`（`ToolExecution.java:21`），
导致 bash/edit/write 被并发执行 → 文件写入竞态、shell cwd 交叉污染。

**改动**（`ActionExecutor.java:299-307`，对齐 pi `agent-loop.ts:419-424` 的「整批降级为串行」语义，不拆批次）：

```java
if (!lane.pendingToolCalls.isEmpty()) {
    if (ctx.toolExecution().get() instanceof ToolExecution.Parallel
            && lane.pendingToolCalls.size() > 1
            && !hasSequentialTool(lane.pendingToolCalls)) {
        var calls = List.copyOf(lane.pendingToolCalls);
        lane.pendingToolCalls.clear();
        yield new Action.ExecuteToolBatch(calls);
    }
    yield lane.pendingToolCalls.remove(0);
}
```

新增（放 Internal helpers 区；需 `import com.pijava.agent.tool.ExecutionMode`）：

```java
private boolean hasSequentialTool(List<Action.ExecuteTool> calls) {
    return calls.stream()
        .map(c -> ctx.toolRegistry() != null ? ctx.toolRegistry().get(c.toolName()) : null)
        .anyMatch(t -> t != null && t.executionMode() instanceof ExecutionMode.Sequential);
}
```

> `ToolExecutionPipeline.runRawBatch:100` 无需改动——不产生 `ExecuteToolBatch` 就不会进并行路径。

### 2.2 ② `anyTerminate` → `allTerminate`

**问题**：`ActionExecutor.java:625` 用 `anyTerminate |=`，批次内**任一**工具要求终止即终止整个 run；
pi `shouldTerminateToolBatch:582` 要求**全部**终止。后果：run 意外中断且无错误提示。

**改动**（`ActionExecutor.java:617-627`）：

```java
boolean allTerminate = !batch.calls().isEmpty();
for (int i = 0; i < batch.calls().size(); i++) {
    var call = batch.calls().get(i);
    var outcome = outcomes.get(i);
    toolPipeline.appendEntry(lane, call, outcome);
    lane.records.add(new LaneRecord.ToolStarted(
        UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
        "", 0, call.toolCallId(), call.toolName(), call.arguments(), "", ReplayKind.NEVER));
    allTerminate &= outcome.terminate();
}
if (allTerminate) {
```

串行路径 `executeTool:600` 的 `outcome.terminate() && lane.pendingToolCalls.isEmpty()` 语义与 pi 单调用场景一致，保留。

---

## 3. L1 补语义（P1）

### 3.1 ③ `stopReason == "length"` 的截断保护

**问题**：pi `agent-loop.ts:211-214, 381` 在输出触达 token 上限时把所有工具调用判为失败并回灌错误，
避免执行参数被截断的调用。pi-java 的 `HarnessUtils.determineOutcome:56-62` 把 `"length"` 归入
`"tool_use"` 并无保护地执行。

**改动**：在 `executeTryFinishRun` 的 `tool_use` 分支前插入：

```java
if ("tool_use".equals(status) && isLengthStop(lane)) {
    failTruncatedToolCalls(lane, lane.partial);
    lane.phase = RunPhase.ASSISTANT;
    return peekAction(laneName);
}
```

`failTruncatedToolCalls` 复用 `ToolExecutionPipeline.appendEntry:78` 的条目构造方式，content 与 pi 一致：

> `Tool call "<name>" was not executed: the response hit the output token limit, so its arguments may be truncated. Re-issue the tool call with complete arguments.`

**注意**：不可简单地把 `status` 改为 `"completed"`——pi 的语义是**回灌错误后继续 inner loop**
（`hasMoreToolCalls = !terminate == true`），模型拿到错误后自行重试；改为 completed 会让 run 直接结束。

### 3.2 ④ 参数 schema 运行时校验

**问题**：pi `agent-loop.ts:618` 有 `validateToolArguments(tool, preparedToolCall)`；pi-java 的
`inputSchema()` 只用于生成 LLM 工具定义（`ToolRegistry.toToolDefinitions:97`）与系统提示词
（`toSystemPromptFragment:106`），运行时从不读取。参数转换靠各工具手写 `prepareArguments`
（如 `ReadTool:60-70`，缺 `path` 时静默得 `null`）。

**约束**：`pi-java-agent-core/pom.xml` 只依赖 `jackson-databind`，且项目需出 GraalVM native image
（`mvn -Pnative package`）。**不引入 `json-schema-validator`**——反射重、native 配置成本高。

**方案**：自研子集校验器 `com.pijava.agent.tool.ToolArgumentsValidator`，仅支持 pi 工具实际用到的关键字：
`type` / `required` / `properties` / `enum` / `items` / `minimum` / `maximum`。

校验对象是**原始 `Map<String, Object>`**（schema 描述 JSON 形态，不是 `prepareArguments` 之后的 record）：

```java
// ToolRegistry.execute:87-93
var prepared = rawTool.prepareArguments(arguments);
ToolArgumentsValidator.validate(rawTool.inputSchema(), arguments);   // 失败抛 IllegalArgumentException
```

现有 `ToolExecutionPipeline.runRawSafely:153` 已会把 `IllegalArgumentException` 包装成错误结果回灌模型，
流水线无需改动。

### 3.3 ⑤ `before_tool` 增加 `terminate` 通道

**问题**：pi `agent-loop.ts:636-646` 允许 `beforeToolCall` 的 block 携带 `terminate: true`；
pi-java 的拒绝路径 `ToolOutcome.denied`（`ToolExecutionPipeline:195`）第三参恒为 `false`。
权限系统无法表达「拒绝并终止」，模型可能反复触发同一被拒调用。

**改动**：`BeforeToolResult` 加第三个组件（项目仍在 `0.1.0-SNAPSHOT`，可接受破损变更）：

```java
public record BeforeToolResult(boolean allowed, Map<String, Object> arguments, boolean terminate) {
    public BeforeToolResult(boolean allowed, Map<String, Object> arguments) {
        this(allowed, arguments, false);      // 兼容旧两参构造
    }
    public static BeforeToolResult denyAndTerminate(String reason) {
        return new BeforeToolResult(false, Map.of("reason", reason), true);
    }
}
```

透传链路：`HookSystem.fireBeforeTool:152` → `BeforeToolDecision.deny`（`ToolExecutionPipeline:175`）
→ `ToolOutcome.denied`（`:195`，第三参改为 decision 携带的 terminate）。

---

## 4. L2 防漂移（治根因，必做）

前五项是「把已知的坑填上」。但根因未除——骨架不提供行为细节，下次改状态机还会漂。建三道闸：

> ✅ **L2 已实现（2026-09-11）**：闸 1 `LoopInvariants`（65 行）+ `peekAction` 断言包装 +
> ASSISTANT 分支 abort 护栏（不变量 5 行为化）；闸 2 `AgentLoopL2Test`（9 用例：golden-trace ×3 +
> 不变量单元测试 ×5 + abort 行为 ×1）；闸 3 回填 `docs/19` §10。

### 4.1 闸 1：`peekAction` 出口的不变量断言

把循环不变量从隐含约定变成运行时可查（`-ea` 生效，生产零开销）。`peekAction` 现有 switch 提取为
`computeNextAction(laneName)`，外层包一层：

```java
private Action peekAction(String laneName) {
    var action = computeNextAction(laneName);
    assert invariantsHold(lane, action)
        : "invariant violated: " + laneDiagnostics(lane, action);
    return action;
}
```

不变量清单：

| # | 不变量 | 防的风险 |
|---|---|---|
| 1 | `IDLE` 且返回 `null` → `pendingToolCalls` 必须为空 | **工具调用被静默丢弃**（最难人工发现） |
| 2 | `ASSISTANT` → action 只能是 `AppendEntry` / `ExecuteTool(Batch)` / `StreamAssistant` | 相位与动作错配 |
| 3 | `CHECKPOINT` → action 只能是 `AppendEntry` / `TryFinishRun` | 同上 |
| 4 | 返回 `null` → `pendingWrites` 必须为空 | 条目未持久化就结束 run |
| 5 | `abortSignal.isAborted()` 为真 → 不再产出 `StreamAssistant` | 中断后仍发请求 |

### 4.2 闸 2：golden-trace 测试（断言 Action 序列本身）

不只断言「最终结果」，要断言「状态机走过的路径」。用固定 `StreamFn`（复用 `FauxProvider` 思路）
驱动一次 run，断言产出的 `Action` 序列：

```java
@Test
void toolThenFollowUpDrivesTwoRunsViaExplicitActions() {
    var actions = driveCollectingActions(harness, promptWithToolCall);
    assertThat(actions).containsExactly(
        new Action.AppendEntry("message", userEntryId),
        new Action.StreamAssistant("assistant", 0),
        new Action.ExecuteTool("call-1", "read", Map.of("path", "a.txt")),
        new Action.StreamAssistant("assistant", 1));
}
```

任何人改 `peekAction` 的分支顺序，序列立刻变化、测试立刻红。
比逐条移植 pi 的 `agent-loop.test.ts`（46KB）性价比高得多。

### 4.3 闸 3：行为条款映射表

在 `docs/19` 增补「`agent-loop.ts` 行为条款 → pi-java 落点」checklist：

| 条款 | pi 位置 | pi-java 落点 | 状态 |
|---|---|---|---|
| 批次终止需全部 terminate | `:582` | `ActionExecutor:625` | ✅ L0-② |
| 有 sequential 工具则整批串行 | `:419` | `ActionExecutor:299` | ✅ L0-① |
| length 停止则工具调用判失败 | `:211` | `HarnessUtils:56` + `ActionExecutor:527`（`failTruncatedToolCalls`） | ✅ L1-③ |
| 工具参数 schema 校验 | `:618` | `ToolArgumentsValidator` + `ToolRegistry:87` | ✅ L1-④ |
| before_tool block 可 terminate | `:636` | `BeforeToolResult.terminate` + `ToolExecutionPipeline:195` | ✅ L1-⑤ |

每轮对齐拿这张表过一遍，不靠记忆。此表已回填到 `docs/19` §10（L2 闸 3）。

---

## 5. L3 可选：转移显式化（观望）

> ✅ **L3 核心 3 项已实现（2026-09-11）**：`ApplyPendingWrite`（AppendEntry 重命名）+
> `ConsumeQueueItem` + `FinishOperation`。`commit_follow_up` 在 pi-java 无对应物
> （`RunEndContext` 无 followUp，followUp 由外部 `followUp()` 入队）；`hook`/`sleep` 记录为
> L3-B 推迟（request 钩子因流式阻塞无法拆、sleep 在 SessionRunner 跨模块耦合）。详见 §5.1。

pi 的 `ActionInfo`（`agent-harness.ts:182-196`，**14 变体/15 字面量**，对齐基准是 `harness-v2.md`
§15 manual-drive + 状态机 §5，非 `agent-loop.ts`——后者是 v3 兼容旧路径）比 pi-java 的 `Action`
多 6 个动作。pi-java 把其中 3 项压进了 `peekAction`/`executeTryFinishRun` 的隐式分支
（follow-up 续跑 = 状态机自转移），导致转移不可断言、语义藏在 switch 分支里。

### 5.1 L3 已做（3 项核心）

| 动作 | pi 语义 | pi-java 落点 |
|---|---|---|
| `apply_pending_write` | deferred write 落盘，abort 期间也允许 | `Action.ApplyPendingWrite`（原 `AppendEntry` 重命名） |
| `consume_queue_item` | 消费 steer/followUp 队列项 | `Action.ConsumeQueueItem`（IDLE 三处 drain → 显式 action，按 QueueMode 整体合并） |
| `finish_operation` | 写 operation_finished + 清 lane 当前 operation | `Action.FinishOperation`（正常终局 + shouldStop + tool terminate 统一出口） |

**顺带修 bug**：原 `executeTryFinishRun` 的 tool_use 分支在工具执行**前**写 `OperationFinished`，
现移除——`OperationFinished` 现只由 `executeFinishOperation` 一处写。

**`stop` 语义**：`Action.FinishOperation(outcome, stop)`。shouldStopAfterTurn 命中 / tool terminate →
`stop=true`（结束驱动，queued follow-up 不自动续跑，保留 pre-L3 的 return-null 路径）；普通终局 →
`stop=false`（经 IDLE 的 `ConsumeQueueItem` 在同一驱动内链入 follow-up）。

**驱动契约（实施中发现的硬约束）**：`executeAction` 的返回值即"下一个要执行的动作"，驱动器必须
**链式**使用（pi `runLoop` / `SessionRunner.drive` 均如此）。`AgentHarness.runToCompletion` 原为
**re-peek** 风格（执行后丢弃返回值、重新 `peekAction`）——对 `TryFinishRun → FinishOperation` 这类
**纯决策动作**（只返回后继、不改 phase）会死循环：re-peek 从不变的 CHECKPOINT 再产 `TryFinishRun`，
`FinishOperation` 永不执行。已改为链式对齐 `SessionRunner.drive`。

### 5.2 L3-B 推迟（本次不做）

| 动作 | 为什么推迟 |
|---|---|
| `hook` | 顶层化需拆分 `ToolExecutionPipeline`（钩子与工具执行混在一起）；request/compaction 钩子因流式阻塞无法拆出 |
| `sleep` | 全部产生于重试路径，重试在 `SessionRunner`（coding-agent 模块）；迁入状态机会把重试策略跨模块耦合 |
| `commit_follow_up` | pi-java `RunEndContext` 无 followUp，followUp 由外部 `followUp()` 入队——无对应物 |

### 5.3 收益

每个转移都是可断言的一等公民，闸 2 golden-trace 序列可直接对照 pi 骨架；
`FinishOperation` 成为 operation 终局唯一写点（5 处 OperationFinished 收敛为 1）。

---

## 6. 落地顺序

```
L0（0.5d） → L1（2-3d） → L2（2d） → [观察] → L3（可选）
   ↑ 两处几行改动、无新依赖，可立即动手
```

| 步骤 | 交付物 | 回归测试 |
|---|---|---|
| L0-① | `ActionExecutor.hasSequentialTool` | bash+read 同轮 → 断言串行（不产生 `ExecuteToolBatch`） |
| L0-② | `allTerminate` | 3 工具中 1 个 terminate → 断言 run 不结束 |
| L1-③ | `failTruncatedToolCalls` | stopReason=length → 断言工具未执行、transcript 有错误结果、run 继续 |
| L1-④ | `ToolArgumentsValidator` | 缺 required 字段 → 断言回灌错误结果而非 NPE |
| L1-⑤ | `BeforeToolResult.terminate` | `denyAndTerminate` → 断言 run 终止 |
| L2-⑥ | `invariantsHold` | 违反不变量的构造 → 断言 `AssertionError` |
| L2-⑦ | golden-trace 测试 | 见 §4.2 |

构造方式可参照 `HarnessToolExecutionSpansTest` 的批量路径。

---

## 7. 验收

- `mvn clean verify` 零错误零警告，全模块测试通过
- `docs/19` 的 5 项差异全部勾选修复，并补齐对应回归测试
- `docs/19` 增补 §4.3 的行为条款映射表
- 在 `docs/phase1-pi-code-mapping.md` 回填 agent-loop 对齐度

---

## 8. 注意事项与顺带发现的技术债

1. **文件行数已超限**：`ActionExecutor.java` 现 769 行、`AgentHarness.java` 现 609 行，
   均超 CLAUDE.md 的「文件 ≤ 500 行」规范。本次新增方法会加剧。
   L1 已完成三处拆分（`ActionExecutor` 850 → 635 行）：
   - compaction 相关（`compact` / `applyCompaction` / `compactTranscript` / `keptMessagesFrom` / `checkAutoCompact`）→ `CompactionExecutor`（121 行）
   - telemetry span 辅助（`openRunSpan` / `closeRunSpan` / `modelLabel` / `thinkingLabel` / `runDurationMs`）→ `RunSpanFactory`（73 行）
   - 上下文组装（`buildMessagesForLane` / `buildSystemPrompt` / `applyPendingTurnUpdate`）→ `ContextAssembler`（102 行）
   **剩余偏离**：`ActionExecutor` 仍 635 行 > 500。剩余为紧密耦合的 action 分派/流式核心
   （`executeStreamAssistant`/`executeTryFinishRun`/`executeTool(Batch)`），机械拆分弊大于利，
   留待后续；`AgentHarness`（609 行）同属已知技术债。
   **L2 增补（2026-09-11）**：新增 `LoopInvariants`（65 行，不变量谓词 + diagnostics）与
   `AgentLoopL2Test`（279 行，golden-trace + 不变量单元测试）。`ActionExecutor` 635 → 660 行
   （`computeNextAction` 提取 + `peekAction` 断言包装 + ASSISTANT 分支 abort 护栏），仍超 500。
   **L3 增补（2026-09-11）**：`Action` 变 7 态（+`ConsumeQueueItem`/`FinishOperation`，
   `AppendEntry`→`ApplyPendingWrite`）；`ActionExecutor` 660 → 683 行（3 个新 case +
   `executeConsumeQueueItem`/`executeFinishOperation`/`outcome()` helper + `FinishOperation` 的
   `stop` flag + tool_use 分支移除过早 OperationFinished），仍超 500 已知债。`LoopInvariants`
   不变量 5 → 7（+6 consume 仅 IDLE、+7 finish 仅 CHECKPOINT），65 → 72 行。
   `OperationFinished` 写点 5 处 → 1 处（仅 `executeFinishOperation`）。
   `AgentHarness` 609 → 615 行（`runToCompletion` 由 re-peek 改为链式，见 §5.1 驱动契约）。
   `AgentLoopL2Test` 279 → 361 行（describe + 3 条 golden 更新 + 2 条新 golden + 不变量 6/7 用例）。
2. **不要为 ④ 引入 JSON Schema 依赖**：native image 反射配置成本高，自研子集校验器足够覆盖内置工具。
3. **⑤ 是公开 API 破损变更**：`BeforeToolResult` 属 `com.pijava.agent.hook` 公开包，扩展实现者需同步；
   当前 `0.1.0-SNAPSHOT` 可接受，若已对外发布则改为新增 `BeforeToolResultV2` 或提供默认方法。
4. **① 的语义边界**：pi 是「批次内有 sequential 则整批串行」，不是「拆成两组分别执行」。
   实现时不要自作聪明地做分组并行。
