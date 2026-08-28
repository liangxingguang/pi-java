# 16 — Agent Loop 语义对齐设计（shouldStopAfterTurn / prepareNextTurn / 图片输入 / reset·continue）

> 目标：补齐 pi-java `AgentHarness` 相对 pi `Agent`（`packages/agent/src/agent.ts` + `agent-loop.ts`）的 4 个功能缺口，使两边作为可编程运行时语义对等。

## 0. 背景与缺口

pi 的 harness（`agent-harness.ts`）是未实现的规格骨架；其真正运行的运行时是 `Agent` 类。对比结论（2026-08-28 实测）：核心循环已对等，缺口 4 项：

| # | pi 侧 | pi-java 现状 |
|---|---|---|
| 1 | `shouldStopAfterTurn` — 每轮后条件终止回调 | 无此概念，`TryFinishRun` 只做 outcome 判定 |
| 2 | `prepareNextTurn` / `prepareNextTurnWithContext` — 轮间原子切换 model/thinkingLevel/context | 只有 `setModel()` 等外部 setter，异步有竞态窗口 |
| 3 | `prompt/steer/followUp` 支持 `ImageContent` | `run()` 系入口只收 `String` |
| 4 | `reset()` / `continue()` 显式入口 | 只有 `seedTranscript`（Phase 4 恢复用） |

已确认的设计决策：

1. **钩子走 HookSystem**（不做成 HarnessConfig 回调字段）——与现有 11 种钩子同机制，TUI/RPC 可运行时注册。
2. **全入口带图**——`run`/`steer`/`followUp`/`nextRun` 全部加重载。
3. **reset/continue 在 Harness 层**——pi 的语义就是运行时语义，coding-agent 层后续按需组合。

## 1. 钩子一：`should_stop_after_turn`

### 语义（对齐 pi `agent-loop.ts:247-257`）

每轮 turn 结束（`turn_end` 已发、工具结果已入 transcript）后询问：是否就此终止整个 run？返回 `true` → 跳过后续轮次，run 以 completed 结束。

### 接口

```java
// hook/ShouldStopAfterTurnContext.java
/** @param assistantMessage 本轮最终助手消息（含 stopReason）
 *  @param toolResults     本轮工具结果消息（可能为空） */
public record ShouldStopAfterTurnContext(
    String lane, String runId,
    AssistantMessage assistantMessage,
    List<Message> toolResults          // toolResult role 消息
) {}

// hook/ShouldStopAfterTurnHook.java
@FunctionalInterface
public interface ShouldStopAfterTurnHook {
    /** 返回 true = 终止 run；null = 弃权（交给下一个钩子/默认不终止） */
    Boolean shouldStopAfterTurn(ShouldStopAfterTurnContext ctx);
}
```

### HookSystem 扩展

```java
public AutoCloseable onShouldStopAfterTurn(String laneName, ShouldStopAfterTurnHook hook)
public boolean fireShouldStopAfterTurn(String laneName, ShouldStopAfterTurnContext ctx)
// 链式语义：首个返回非 null 的钩子生效；全部 null 或无钩子 → false。
// 钩子抛异常 → recordHookError，视为弃权（与其他钩子的非致命语义一致）。
```

### 触发点

`ActionExecutor.executeTryFinishRun(...)` 内、调用 `HarnessUtils.determineOutcome(lane)` 判定 completed 后、真正落 finish 记录前：

```
outcome = completed
  └─ fireShouldStopAfterTurn(ctx) == true
       └─ run 仍按 completed 结束（不产生新轮次）
outcome != completed（aborted/error）→ 不触发钩子（pi 亦如此：error/aborted 直接 return）
```

> pi 中 `shouldStopAfterTurn` 为 true 时发 `agent_end` 但不发额外的 finish 记录——pi-java 的 `TryFinishRun(completed)` 路径恰好就是"结束 run"，语义收敛点一致。停止原因不区分"自然完成"与"钩子终止"，与 pi 一致。

## 2. 钩子二：`prepare_next_turn`

### 语义（对齐 pi `agent-loop.ts:226-245`）

每轮 turn 结束后、shouldStop 判定之前，允许修改**下一轮**的 model / thinkingLevel。pi 返回 `AgentLoopTurnUpdate { context?, model?, thinkingLevel? }`；pi-java 本设计只取 `model` + `thinkingLevel` 两项（context 换轨由既有 `transform_context` 钩子承担，职责不重叠）。

### 接口

```java
// hook/PrepareNextTurnContext.java
public record PrepareNextTurnContext(
    String lane, String runId,
    AssistantMessage assistantMessage,
    List<Message> toolResults
) {}

// hook/TurnUpdate.java
/** @param thinkingLevel "off"|"minimal"|"low"|"medium"|"high"|"xhigh"；null = 不变 */
public record TurnUpdate(ModelId<?> model, String thinkingLevel) {}

// hook/PrepareNextTurnHook.java
@FunctionalInterface
public interface PrepareNextTurnHook {
    /** 返回 null = 不修改；非 null = 合入下一轮配置 */
    TurnUpdate prepareNextTurn(PrepareNextTurnContext ctx);
}
```

### HookSystem 扩展

```java
public AutoCloseable onPrepareNextTurn(String laneName, PrepareNextTurnHook hook)
public TurnUpdate firePrepareNextTurn(String laneName, PrepareNextTurnContext ctx)
// 链式传递（同 fireBeforePayload 模式）：每个非 null 返回值逐字段覆盖累积结果。
// 钩子抛异常 → recordHookError，该钩子视为返回 null。
```

### 触发点与应用（原子切换）

`executeTryFinishRun` 内、`should_stop_after_turn` 判定**之前**（pi 顺序：先 prepareNextTurn 后 shouldStop，agent-loop.ts:232→248）：

```
update = firePrepareNextTurn(laneName, ctx)
if update != null:
    lane.pendingTurnUpdate = update        // 挂到 LaneState，下一轮 StreamAssistant 消费
```

下一轮 `executeStreamAssistant` 开头（abort 检查后、auto-compact 前）：

```
if lane.pendingTurnUpdate != null:
    apply: state.model / state.thinkingLevel 若变化
           → 写 Entry.ModelChange / Entry.ThinkingLevelChange 进 transcript+pendingWrites
             （pi-java 特有的可审计性；pi 不写 entry）
    lane.pendingTurnUpdate = null
```

**原子性论证**：应用点在 `executeAction` 的单线程驱动内完成，不存在 pi 外部 setter 的异步竞态窗口——这正是状态机架构相对 pi 回调架构的结构性优势。切换可见于下一轮请求构建（`buildMessagesForLane` 读 `ctx.model().get()`）。

**作用域（对齐 pi 语义）**：pi 的 `prepareNextTurn` 修改的是 `runLoop` 局部 config，只作用于**本 run 内**的后续轮次，run 结束即失效，不跨 run。pi-java 对应实现：`pendingTurnUpdate` 在 run 结束（`TryFinishRun` 完成）时**清除**；思考级别/模型的状态回写只发生在本 run 消费该 update 的那一轮。若业务需要跨 run 的持久变更，调用方应在钩子里同时调用 harness 的 setter（现有 API 已覆盖）。中途 abort 时 pendingTurnUpdate 随 run 终止一并清除——pi 的局部 config 同样随 run 消亡。

> thinkingLevel 变更 entry 只在值真正变化时写；model 变更同理（比较 `ModelId` 等价性）。

## 3. 图片输入

### 参数类型

新增 `agent` 模块自有类型（不直接复用 `com.pijava.ai.message.ContentBlock.ImageContent`，避免消息层类型泄漏到 harness 公共 API）。对齐 pi 的 `ImageContent`（ai/types.ts:354-358，仅 base64 形态）：

```java
// harness/PromptImage.java（放 harness 包，随 HarnessConfig 一起对外）
/** @param mimeType image/png|image/jpeg|image/gif|image/webp|image/bmp
 *  @param data     base64 编码图片数据（pi 的 ImageContent.data） */
public record PromptImage(String mimeType, String data) {
    public ContentBlock.ImageContent toContentBlock() {
        return new ContentBlock.ImageContent(mimeType, data);
    }
}
```

### 入口重载（AgentHarness）

```java
public Action run(String prompt, List<PromptImage> images)          // 默认 lane
public Action run(String laneName, String prompt, List<PromptImage> images)
// steer/followUp/nextRun 同构：
public String steer(String laneName, String prompt, List<PromptImage> images)
public String followUp(String laneName, String prompt, List<PromptImage> images)
public String nextRun(String laneName, String prompt, List<PromptImage> images)
// images 为 null 或空 → 等价于现有纯文本重载（不新增歧义）
```

### 数据流

1. **run**：`ActionExecutor.run(laneName, prompt, images)` 构造 user entry 时：
   `content = [TextContent(prompt)] + images.map(toContentBlock())`（pi 语义：text 在前、图片随后，agent.ts:402-406）。
2. **队列**：`QueueManager` 队列元素从 `String` 升为 `QueuedPrompt(String text, List<PromptImage> images)` record。
   `steer(...)` 纯文本重载内部包 `new QueuedPrompt(prompt, List.of())`，现有调用方（TUI/RPC/coding-agent）零改动。
3. **注入**：`ActionExecutor.injectUserMessages` 与 `peekAction` 的 Idle 分支构造 user entry 时同样合并 content。
4. **持久化**：`Entry.Message` → `Message.UserMessage(List<ContentBlock>)`，`SessionJson`/`EntryJsonCodec` 已支持 `ImageContent` 序列化（ReadTool 路径已验证），无存储层改动。

### 校验

- `mimeType` 必须以 `image/` 开头，否则 `IllegalArgumentException`（入口即抛，不进 transcript）。
- `data` 非空校验同上。
- 不做解码验证（与 pi 一致，pi 也不 decode）。

## 4. `reset(lane)` / `continue(lane)`

### `reset`（对齐 pi `agent.ts:332-345`）

```java
/** 清空 lane transcript、三条队列、partial 与 run 状态。运行中拒绝。 */
public void reset(String laneName)
```

- 运行中（`lane.phase` 非 Idle）→ 抛 `IllegalStateException`（pi: "Agent is already processing..."）。
- 清理：`lane.transcript.clear()`、`queueManager.clearAll(laneName)`（steer+nextRun+followUp 三队列）、`lane.partial = null`、`lane.pendingToolCalls.clear()`、`lane.pendingWrites.clear()`、`lane.pendingTurnUpdate = null`、`lane.runId/leafId` 复位。
-不清 `seedTranscript` 能力——reset 后仍可再次 seed（Phase 4 恢复路径不变）。

### `continue`（对齐 pi `agent.ts:360-388` + `agentLoopContinue`）

```java
/** 从当前 transcript 末尾续跑一轮：不加新 user entry，直接进入 assistant 流。 */
public Action continueRun(String laneName)
```

命名用 `continueRun`（`continue` 是 Java 关键字）。

- 前置校验（对齐 pi `agentLoopContinue`，agent-loop.ts:70-76）：
  - transcript 为空 → `IllegalStateException("Cannot continue: no messages in context")`
  - 最后一条 entry 是 assistant message → `IllegalStateException("Cannot continue from message role: assistant")`
- 执行：复用既有 run 启动路径但**跳过 user entry 追加**——新增 `ActionExecutor.runContinue(laneName)`：占用 lane（phase → Assistant）、分配 runId、发 run start 事件，然后让 `peekAction` 自然产出 `StreamAssistant("assistant", 0)`。
- 队列语义（对齐 pi `continue()` 对 assistant 末尾的特殊处理，agent.ts:371-384）：continueRun 遇 assistant 末尾时**不做** pi 的"排空 steering/followUp 队列"分支——pi-java 的队列排空已由 `peekAction` Idle 分支统一处理，重复实现会造成双路径。调用方需要该语义时走 `peekAction` 即可（已有）。

### 与事件流的衔接

continueRun 启动的 run 与普通 run 共享同一事件序列（`run_start` → 流事件 → `run_end`），RPC/TUI 无需特判。

## 5. 错误处理汇总

| 场景 | 行为 |
|---|---|
| shouldStop 钩子抛异常 | recordHookError（UsageRecord cause=hook），视为弃权（false） |
| prepareNextTurn 钩子抛异常 | recordHookError，视为返回 null |
| PromptImage mimeType 非法 / data 空 | 入口抛 `IllegalArgumentException` |
| reset 时 lane 运行中 | `IllegalStateException` |
| continueRun transcript 空末尾 assistant | `IllegalStateException`（消息对齐 pi） |
| pendingTurnUpdate 指向的 model 为 null | 钩子返回值中 model=null 表示不改 model |

## 6. 测试计划（TDD，每批先行测试）

**批次 A：钩子×2**（`HookSystem` + `ActionExecutor`）
- shouldStop：无钩子 → run 正常多轮；钩子 true → 当轮后 run completed、后续无 LLM 调用；钩子 false/null → 不影响；钩子异常 → run 继续。
- prepareNextTurn：钩子改 model → 下一轮请求携带新 model 且 transcript 有 ModelChange entry；改 thinkingLevel 同理；model 未变不写 entry；run 结束后 update 失效（下一 run 不受影响）。
- 顺序：prepareNextTurn 先于 shouldStop 判定（同轮两者都注册时）。

**批次 B：图片输入**
- run+images → user entry content = [text, image...]；JSONL 往返保真。
- steer/followUp/nextRun 带图 → 注入的 user entry 含图。
- 非法 mimeType/data → 入口拒绝。
- 纯文本重载行为不变（回归）。

**批次 C：reset/continue**
- reset：Idle 态清空全量状态；运行中抛异常；reset 后可重新 seed+run。
- continueRun：空 transcript 抛；末尾 assistant 抛；末尾 user → 直接 StreamAssistant 且 transcript 无新 user entry；continue 产生的 run 事件序列完整。
- continueRun 在 toolResult 末尾（工具中断后续跑）场景。

**回归**：`mvn clean verify` 全绿；现有 TUI/RPC/coding-agent 调用方零改动编译通过。

## 7. 交付顺序与 Commit 切分

1. `feat(agent): add should_stop_after_turn hook`（批次 A-1）
2. `feat(agent): add prepare_next_turn hook with atomic turn switch`（批次 A-2）
3. `feat(agent): image input for run/steer/followUp/nextRun`（批次 B）
4. `feat(agent): add reset and continueRun entry points`（批次 C）

预计 4 个 commit，每批独立可编译。涉及文件集中在 `pi-java-agent-core`：`hook/`（+4 接口 +2 context +1 TurnUpdate）、`harness/HookSystem` 调用方 `ActionExecutor`、`AgentHarness`、`LaneState`、`QueueManager`、`HarnessUtils`。

## 8. 明确不做（YAGNI）

- `TurnUpdate.context`（context 换轨已有 `transform_context` 钩子）
- pi `continue()` 的 assistant-末尾排空队列分支（peekAction 已覆盖）
- 图片 URL 形态（pi 的 `ImageContent` 仅 base64 一种，URL 是 pi-java P6-19 的 provider 层扩展，与本设计无关）
- prepareNextTurn 的 `prepareNextTurnWithContext` 双轨签名（Java 单一 context 参数签名已覆盖）
