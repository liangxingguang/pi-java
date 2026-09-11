# 19 — pi ↔ pi-java 行为差异清单（agent-loop / 工具执行）

> 范围：以 pi `packages/agent/src/agent-loop.ts`（`runLoop` 及工具执行）为基准，逐项核对 pi-java
> `pi-java-agent-core` 的 `ActionExecutor` / `ToolExecutionPipeline` / `ToolRegistry` / `SessionRunner`。
> 编制日期：2026-09-10。路径基准：pi 侧相对 `D:\workplaceForai\pi`，pi-java 侧相对仓库根。
>
> 本篇只记录**行为差异**（会导致可观察结果不同）。结构性改写（控制流 → 状态机）见 §7，不计为缺陷。

## 0. 摘要

| # | 差异项 | 风险 | pi 位置 | pi-java 位置 |
|---|---|---|---|---|
| 1 | 批量工具 `terminate` 判定：`every` → `any` | **高**（run 提前结束） | `agent-loop.ts:582` | `ActionExecutor.java:625` |
| 2 | 工具级 `executionMode` 未参与并行判定 | **高**（bash/edit/write 被并发执行） | `agent-loop.ts:419-424` | `ActionExecutor.java:299-307` |
| 3 | 缺 `stopReason == "length"` 的截断保护 | 中（执行参数被截断的调用） | `agent-loop.ts:211-214, 381` | `HarnessUtils.java:56` + `ActionExecutor.java:527`（L1 已修） |
| 4 | 缺 `validateToolArguments` 运行时 schema 校验 | 中（非法参数静默降级） | `agent-loop.ts:618` | `ToolRegistry.java:87`（L1 已修） |
| 5 | `beforeToolCall` block 不支持 `terminate` | 低 | `agent-loop.ts:636-646` | `ToolExecutionPipeline.java:195`（L1 已修） |
| 6 | 重试循环位置上移（多一层） | 低（行为略强） | Agent 层 `_willRetryAfterAgentEnd` | `SessionRunner.java:82-157` |

---

## 1. 批量工具 `terminate` 判定：`every` → `any`

### pi

```582:584:packages/agent/src/agent-loop.ts
function shouldTerminateToolBatch(finalizedCalls: FinalizedToolCallOutcome[]): boolean {
	return finalizedCalls.length > 0 && finalizedCalls.every((finalized) => finalized.result.terminate === true);
}
```

只有**批次内全部**工具都要求终止，整个 run 才终止。

### pi-java

```617:627:pi-java-agent-core/src/main/java/com/pijava/agent/harness/ActionExecutor.java
        boolean anyTerminate = false;
        for (int i = 0; i < batch.calls().size(); i++) {
            var call = batch.calls().get(i);
            var outcome = outcomes.get(i);
            toolPipeline.appendEntry(lane, call, outcome);
            lane.records.add(new LaneRecord.ToolStarted(
                UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
                "", 0, call.toolCallId(), call.toolName(), call.arguments(), "", ReplayKind.NEVER));
            anyTerminate |= outcome.terminate();
        }
        if (anyTerminate) {
```

`anyTerminate |=` —— **任一**工具要求终止即终止整个 run。

### 影响

默认 `ToolExecution.defaultMode() == Parallel`（`ToolExecution.java:21`），同轮多工具调用走 `executeToolBatch`。
若一轮中 3 个工具只有 1 个返回 `terminate=true`（例如权限拒绝、致命错误），pi 会继续跑完剩余语义，
pi-java 直接结束 run——用户会看到回答突然中断且无错误提示。

### 建议

改为 `allTerminate`，与 pi 一致：

```java
boolean allTerminate = !batch.calls().isEmpty();
...
    allTerminate &= outcome.terminate();
```

注意串行路径 `executeTool:600` 的 `outcome.terminate() && lane.pendingToolCalls.isEmpty()` 语义与 pi 的单调用场景一致，可保留。

---

## 2. 工具级 `executionMode` 未参与并行判定

### pi

```419:425:packages/agent/src/agent-loop.ts
	const hasSequentialToolCall = toolCalls.some(
		(tc) => currentContext.tools?.find((t) => t.name === tc.name)?.executionMode === "sequential",
	);
	if (config.toolExecution === "sequential" || hasSequentialToolCall) {
		return executeToolCallsSequential(currentContext, assistantMessage, toolCalls, config, signal, emit);
	}
	return executeToolCallsParallel(currentContext, assistantMessage, toolCalls, config, signal, emit);
```

**工具自身可声明不可并发**，批次内只要有一个 sequential 工具就整批降级为串行。

### pi-java

`AgentTool.executionMode()` 已定义且各内置工具已标注：

- `Sequential`：`BashTool.java:76`、`WriteTool.java:52`、`EditTool.java:76`
- `Parallel`：`ReadTool.java:58`、`LsTool.java:49`、`GrepTool.java:56`、`GlobTool.java:50`

但批量决策处**完全不读该字段**：

```299:307:pi-java-agent-core/src/main/java/com/pijava/agent/harness/ActionExecutor.java
                if (!lane.pendingToolCalls.isEmpty()) {
                    if (ctx.toolExecution().get() instanceof ToolExecution.Parallel
                            && lane.pendingToolCalls.size() > 1) {
                        var calls = List.copyOf(lane.pendingToolCalls);
                        lane.pendingToolCalls.clear();
                        yield new Action.ExecuteToolBatch(calls);
                    }
                    yield lane.pendingToolCalls.remove(0);
                }
```

`ToolExecutionPipeline.runRawBatch:100` 同样无条件并行。

### 影响

默认并行模式下，模型一轮里同时调 `bash` 与 `read`/`edit` 时，bash 与 edit 会并发执行——
违反工具自身声明的串行约束，可能产生**文件写入竞态**（`edit` 与 `write` 同时改同一文件）或
**shell 状态错乱**（bash 的 cwd / 环境变量被并发调用交叉影响）。这是数据正确性风险，建议列为 P0。

### 建议

在 `peekAction` 组装 `ExecuteToolBatch` 前增加判定（需要把 toolName 映射回 `AgentTool`）：

```java
boolean hasSequential = calls.stream()
    .map(c -> ctx.toolRegistry().get(c.toolName()))
    .anyMatch(t -> t != null && t.executionMode() instanceof ExecutionMode.Sequential);
if (!hasSequential && parallel && calls.size() > 1) { yield new Action.ExecuteToolBatch(calls); }
```

---

## 3. 缺 `stopReason == "length"` 的截断保护

### pi

```208:214:packages/agent/src/agent-loop.ts
			if (toolCalls.length > 0) {
				// A "length" stop means the output was cut off by the token limit, so
				// every tool call in the message may carry truncated arguments. Fail
				// them all instead of executing potentially borked calls.
				const executedToolBatch =
					message.stopReason === "length"
						? await failToolCallsFromTruncatedMessage(toolCalls, emit)
						: await executeToolCalls(currentContext, message, config, signal, emit);
```

`failToolCallsFromTruncatedMessage:381` 把每个调用判为错误并附带
"Re-issue the tool call with complete arguments." 提示，让模型重试。

### pi-java

`ActionExecutor.executeTryFinishRun:527` 只按 `HarnessUtils.determineOutcome` 区分三种结果：

```56:62:pi-java-agent-core/src/main/java/com/pijava/agent/harness/HarnessUtils.java
    static String determineOutcome(LaneState lane) {
        if (lane.newestOwn == null) return "error";
        String sr = lane.newestOwn.stopReason();
        if (isErrorStopReason(sr)) return "error";
        if ("tool_use".equals(sr)) return "tool_use";
        return "completed";
    }
```

`"length"` 落到 `tool_use`（若带工具调用）并执行之，无任何截断保护。

### 影响

输出触达 token 上限时，工具参数 JSON 可能不完整。`ToolCallAccumulator` 一类实现通常做 best-effort
JSON salvage，因此**截断的参数可能仍解析成功并执行**——例如 `write` 写入内容残缺的文件、`edit` 应用不完整的替换。pi 明确规避了这一风险。

### 已修复（L1，2026-09-10）

`HarnessUtils.determineOutcome` 增加 `"length"` 分支（`isLengthStop`），`ActionExecutor.executeTryFinishRun`
在 `tool_use` 分支前先判 `"length"`：

```java
if ("length".equals(status)) {
    failTruncatedToolCalls(lane);   // 与 pi failToolCallsFromTruncatedMessage 对齐
    lane.phase = RunPhase.ASSISTANT;
    return peekAction(laneName);
}
```

`failTruncatedToolCalls` 把 partial 里每个工具调用写成一个 `isError=true` 的 `ToolResultMessage`
（文案含 "hit the output token limit … Re-issue the tool call with complete arguments."），
模型拿回错误后自行重试；run 不因此提前结束。回归测试见
`AgentLoopL1Test.lengthStopFailsToolCallsBackWithoutExecutingThem`。

---

## 4. 缺 `validateToolArguments` 运行时 schema 校验

### pi

```616:620:packages/agent/src/agent-loop.ts
		const preparedToolCall = prepareToolCallArguments(tool, preparedToolCall);
		const validatedArgs = validateToolArguments(tool, preparedToolCall);
		if (config.beforeToolCall) {
```

基于工具的 `inputSchema` 做 AJV 校验，非法参数在**进入钩子前**就被拦成错误结果。

### pi-java

`ToolRegistry.execute` 只做 `prepareArguments`，没有 schema 校验环节：

```87:93:pi-java-agent-core/src/main/java/com/pijava/agent/tool/ToolRegistry.java
        var rawTool = (AgentTool<Object, Object>) tool;
        var prepared = rawTool.prepareArguments(arguments);
        @SuppressWarnings("unchecked")
        var result = rawTool.execute(toolCallId, prepared, signal,
                                     (ToolUpdateCallback<Object>) onUpdate, context);
        return result;
```

`inputSchema()` 仅用于生成 LLM 工具定义（`ToolRegistry.toToolDefinitions:97`）与系统提示词（`toSystemPromptFragment:106`），
**运行时从不读取**。参数转换靠各工具手写，例如 `ReadTool.prepareArguments:60-70`：缺 `path` 时静默得到 `null`。

### 影响

模型给出缺字段/类型错误的参数时，pi 会返回结构化校验错误供模型自我纠正；pi-java 则把 null/默认值传给工具，
可能产生 NPE 或静默的错误行为，且模型拿不到 schema 级反馈。

### 已修复（L1，2026-09-10）

新增 `ToolArgumentsValidator`（`pi-java-agent-core/.../tool/`）：基于 `inputSchema()` 的 JSON Schema
子集校验（type / required / properties 递归 / items 元素类型），在 `ToolRegistry.execute` 的
`prepareArguments` 之后、`AgentTool.execute` 之前调用，失败抛 `IllegalArgumentException`
（`ToolExecutionPipeline.runRawSafely` 已把它包装成 `"Tool error: …"` 错误结果回灌模型）。

关键边界：参数含 `_raw`（流适配器对截断/畸形 JSON 的回收路径，如 BashTool）时**跳过 required 检查**，
避免破坏该恢复机制。回归测试见 `AgentLoopL1Test.invalidArgumentsAreFedBackAsErrorsInsteadOfExecuted`、
`validArgumentsStillExecute`。

---

## 5. `beforeToolCall` block 不支持 `terminate`

### pi

```636:646:packages/agent/src/agent-loop.ts
			if (beforeResult?.block) {
				const result = createErrorToolResult(beforeResult.reason || "Tool execution was blocked");
				if (beforeResult.terminate === true) {
					result.terminate = true;
				}
				return {
					kind: "immediate",
					result,
					isError: true,
				};
			}
```

钩子拒绝调用时可同时要求终止 run。

### pi-java

拒绝路径的 `terminate` 恒为 `false`：

```195:201:pi-java-agent-core/src/main/java/com/pijava/agent/harness/ToolExecutionPipeline.java
        static ToolOutcome denied(Action.ExecuteTool et) {
            return new ToolOutcome(
                List.of(new ContentBlock.ToolResultContent(
                    et.toolCallId(), et.toolName(),
                    List.of(new ContentBlock.TextContent("Tool call denied by hook")), true)),
                true, false);
        }
```

（`beforeToolDecision:88-96` 只传递 `allowed` 与改写后的 `arguments`，无 terminate 通道。）

### 影响

`before_tool` 钩子无法表达「拒绝并终止」——例如权限系统判定"用户已选择一律拒绝"时，
pi 能立刻结束 run，pi-java 会继续请求模型、可能反复触发同一被拒调用。

### 已修复（L1，2026-09-10）

`BeforeToolResult` 增加第三分量 `terminate`（保留 2 参兼容构造器与 `proceed()` 别名），
新增 `denyAndTerminate(reason)` 工厂。`ToolExecutionPipeline.beforeToolDecision` 拒绝时把
`beforeResult.terminate()` 透传到 `BeforeToolDecision.deny(call, terminate)`，
`ToolOutcome.denied(et, terminate)` 最终落到 `ToolFinished.terminate` 与 OperationFinished 判定。

注意：拒绝结果的容器是 `ContentBlock.ToolResultContent`（携带 call id/name），外层内层都是内容块。
回归测试见 `AgentLoopL1Test.denyAndTerminateEndsTheRun`、`plainDenyDoesNotTerminate`。

---

## 6. 重试循环位置上移（行为略强，非缺陷）

| | pi | pi-java |
|---|---|---|
| 位置 | Agent 层 `_willRetryAfterAgentEnd`（调用 `agentLoopContinue`） | `SessionRunner.drive` 的 `do { } while (shouldRetry)` |
| 实现 | — | `SessionRunner.java:82-157`；指数退避 `retryDelayMs:233`；`abortableSleep:238` |
| 上限 | — | `MAX_RETRIES = 3`，`BASE_DELAY_MS = 2_000` |
| 排除 | `isContextOverflow`（交压缩） | `isRetryableError:219`，同一份 `CONTEXT_OVERFLOW_MARKERS` 列表 |

pi-java 在 harness 之外多包一层，且显式支持 `abort_retry` 中止退避。语义不弱于 pi，仅结构不同，记录备查。

---

## 7. 非缺陷：循环结构的等价改写

pi 的 `runLoop` 是嵌套双层 while：

```169:174:packages/agent/src/agent-loop.ts
	// Outer loop: continues when queued follow-up messages arrive after agent would stop
	while (true) {
		let hasMoreToolCalls = true;

		// Inner loop: process tool calls and steering messages
		while (hasMoreToolCalls || pendingMessages.length > 0) {
```

pi-java 因 `driveMode = Manual`（默认，`AgentSession.java:262`）不在 harness 内写 while，改为三层驱动：

| 层 | pi | pi-java |
|---|---|---|
| 重试 | Agent 层 | `SessionRunner.drive` do-while（§6） |
| inner（工具/steering） | `runLoop:174` | `SessionRunner.java:114-116` 的 `while (action != null) executeAction` |
| outer（follow-up 续跑） | `runLoop:170` + `:263-268` 的 `continue` | `ActionExecutor.peekAction:278-294` 的 Idle 分支 + `executeTryFinishRun:584-587` 的 `drainFollowUp` → `runQueued` |

`runQueued` 返回新 Action 使 L2 的 `while` 不退出，等价于 pi outer loop 的 `continue`。
队列优先级 steer → nextRun → followUp 与 pi「steering 先于 follow-up」一致，属**等价改写**。

---

## 8. 相关：输入侧未接线项（非 agent-loop 差异，一并记录）

| 项 | 现状 |
|---|---|
| `@file` 引用 | `ArgsParser.java:201` 收集进 `Args.fileArgs`，main 代码无消费者，未展开 |
| Prompt 模板 | `PromptTemplates.formatPromptTemplateInvocation:221` 已实现但无调用点；`AgentSession.assemble:225` 仅装配注册表 |

---

## 9. 修复优先级建议

1. **P0** §2 `executionMode` 未生效（并发写文件 / shell 状态错乱，数据正确性）
2. **P0** §1 `anyTerminate` → `allTerminate`（run 意外中断，用户可见）
3. **P1** §3 `length` 截断保护（可能写入残缺内容）
4. **P1** §4 schema 运行时校验（模型自我纠正链路缺失）
5. **P2** §5 before_tool 的 terminate 通道

每项的验收：在 `pi-java-agent-core` 补对应单元测试（可参照 `HarnessToolExecutionSpansTest` 的批量路径构造方式），
并在 `docs/phase1-pi-code-mapping.md` 中回填对齐度。

---

## 10. L2 防漂移（2026-09-11）：行为条款映射表

闸 3 落地（`docs/20` §4.3）：把 pi `agent-loop.ts` 的行为条款固化为
「条款 → pi-java 落点」checklist，每轮对齐拿这张表过一遍，不靠记忆。
前五项在 L0/L1 已闭环，后两项是 L2 的防漂移机制。

| 条款 | pi 位置 | pi-java 落点 | 状态 |
|---|---|---|---|
| 批次终止需全部 terminate | `agent-loop.ts:582` | `ActionExecutor.executeToolBatch`（`allTerminate`） | ✅ L0-② |
| 有 sequential 工具则整批串行 | `agent-loop.ts:419-424` | `ActionExecutor` ASSISTANT 分支（`hasSequentialTool`） | ✅ L0-① |
| length 停止则工具调用判失败 | `agent-loop.ts:211-214, 381` | `HarnessUtils.determineOutcome`/`isLengthStop` + `ActionExecutor.failTruncatedToolCalls` | ✅ L1-③ |
| 工具参数 schema 校验 | `agent-loop.ts:618` | `ToolArgumentsValidator` + `ToolRegistry.execute` | ✅ L1-④ |
| before_tool block 可 terminate | `agent-loop.ts:636-646` | `BeforeToolResult.terminate` + `ToolExecutionPipeline` | ✅ L1-⑤ |
| 中断后不再发 LLM 请求 | abort 语义 | `ActionExecutor` ASSISTANT 分支 abort 护栏 + `LoopInvariants` 不变量 5 | ✅ L2-⑥ |
| 每个 action 出口满足循环不变量 | —（pi-java 独有机制） | `LoopInvariants.hold`（`peekAction` 断言包装，`-ea` 生效） | ✅ L2-⑥ |
| 状态机路径可断言 | —（pi-java 独有机制） | `AgentLoopL2Test` golden-trace（断言 Action 序列本身） | ✅ L2-⑦ |

闸 1（`LoopInvariants` + `peekAction` 断言包装 + abort 护栏）与闸 2（`AgentLoopL2Test`
golden-trace / 不变量单元测试）见 `docs/20` §4.1/§4.2；`AgentLoopL2Test` 9 用例全绿。

### 10.1 L3 转移显式化（2026-09-11）

把 3 个隐式转移变成一等公民 `Action`，与 pi 骨架逐条对应。`Action` 5 态 → 7 态。

| 条款 | pi 位置 | pi-java 落点 | 状态 |
|---|---|---|---|
| `apply_pending_write` — deferred write 落盘 | `harness-v2.md` §15 `fx.applyPendingWrite` | `Action.ApplyPendingWrite`（原 `AppendEntry` 重命名） | ✅ L3 |
| `consume_queue_item` — 消费 steer/followUp 队列 | `harness-v2.md` §15 `fx.consumeQueueItem` | `Action.ConsumeQueueItem`（IDLE 三处 drain → 显式 action，按 QueueMode 整体合并） | ✅ L3 |
| `finish_operation` — 写 operation_finished + 清 operation | `harness-v2.md` §15 `fx.finishOperation` | `Action.FinishOperation`（正常终局 + shouldStop + tool terminate 统一出口） | ✅ L3 |
| tool_use 不再提前写 OperationFinished | —（pi-java 原缺陷） | `executeTryFinishRun` tool_use 分支移除过早写；`OperationFinished` 仅 `executeFinishOperation` 一处 | ✅ L3 |
| finish 后是否续跑 queued run（`stop` 语义） | `harness-v2.md` §15（shouldStop 后不自动开下一 run） | `Action.FinishOperation(outcome, stop)`：`stop=true`（shouldStop 命中 / tool terminate）→ 结束驱动、queued follow-up 留给未来驱动；`stop=false`（普通终局）→ 经 IDLE 的 `ConsumeQueueItem` 在同一驱动内链入 follow-up | ✅ L3 |
| 驱动循环契约（链式执行返回值） | pi `runLoop`：`action = executeAction(action)` | 所有驱动器必须链式使用 `executeAction` 返回值；`runToCompletion` 原为 re-peek（丢弃返回值重 peek），对纯决策动作 `TryFinishRun→FinishOperation` 死循环，已改为链式对齐 `SessionRunner.drive` | ✅ L3 |
| `commit_follow_up` — before_run_end 钩子返回 follow-up | `harness-v2.md` §15 `fx.commitRunEndFollowUp` | pi-java `RunEndContext` 无 followUp，followUp 由外部 `followUp()` 入队 | 无对应物（L3-B） |
| `hook` — 每个 fx.runHook 停泊 | `harness-v2.md` §15 GatedEffects | 顶层化需拆分 `ToolExecutionPipeline`；request 钩子因流式阻塞无法拆 | 推迟（L3-B） |
| `sleep` — 重试退避 | `harness-v2.md` §15 `fx.sleep` | 重试在 `SessionRunner`（coding-agent 模块），迁入状态机跨模块耦合 | 推迟（L3-B） |

