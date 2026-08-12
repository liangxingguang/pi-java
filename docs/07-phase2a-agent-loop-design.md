# Phase 2a: Agent 循环最小版 — 阶段设计文档

> **目标**：跑通 `pi-java -p "hello"` → LLM → 返回响应的完整链路。不需要工具调用。
> **工时**：2 周（9 项任务）
> **输入文档**：`03-detailed-design.md` §2、`04-implementation-plan.md` §4、`phase1-pi-code-mapping.md`
> **前置阶段**：Phase 1（`pi-java-ai` 可用）

---

## 1. 架构概览

```
┌─ pi-java-agent-core ─────────────────────────────────────┐
│                                                          │
│  AgentLoop                                               │
│  ┌──────────────────────────────────────────────────┐    │
│  │  while (action = harness.peekAction()) {         │    │
│  │    action = harness.executeAction(action);       │    │
│  │    // harness 内部处理 StreamFn 调用 + entry 写入 │    │
│  │  }                                               │    │
│  └──────────────────────────────────────────────────┘    │
│         │ peekAction / executeAction                     │
│  ┌──────▼───────────────────────────────────────────┐    │
│  │  AgentHarness（状态机 + StreamFn 持有者）          │    │
│  │  • LaneState { phase, transcript, pendingWrites }  │    │
│  │    └─ buildMessages() 从 entries 构建消息列表     │    │
│  │  • StreamFn（通过 HarnessConfig 注入）            │    │
│  │  • peekAction() → Action                         │    │
│  │  • executeAction(Action) → Action | null          │    │
│  │    └─ StreamAssistant → 内部调用 LLM + 消费事件流  │    │
│  └──────────────────────────────────────────────────┘    │
│         │ StreamFn                                       │
│         ▼                                                │
│  ┌──────────────────────────────────────────────────┐    │
│  │  pi-java-ai（Phase 1）                            │    │
│  │  ChatApi.streamBlocking() → StreamIterator        │    │
│  └──────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
```

**核心设计决策（对齐 pi）：**
- **手动驱动模式**：AgentHarness 是状态机，不自己跑循环。外层（`AgentLoop` 或 Phase 3 CLI/TUI）调用 `peekAction()` + `executeAction()` 推着走
- **错误编码进流不 throw**：LLM 错误 → `StreamError` 事件 → `stopReason: "error"`，不会被异常打断
- **无 JPMS**：直接 Maven 依赖 `pi-java-ai`，classpath 运行

## 2. 事件协议补齐（P2a-1）

### 2.1 当前 vs 目标

```
Phase 1（7 种）                         Phase 2a（12 种）
═══════════════                         ═══════════════
StreamEvent                             StreamEvent
├── TextDelta                           ├── Start              ← 新增
├── ToolCallStart                       ├── TextStart          ← 新增
├── ToolCallDelta                       ├── TextDelta
├── ToolCallEnd                         ├── TextEnd            ← 新增
├── UsageInfo                           ├── ThinkingStart      ← 新增
├── StreamDone                          ├── ThinkingDelta
└── StreamError                         ├── ThinkingEnd        ← 新增
                                        ├── ToolCallStart
                                        ├── ToolCallDelta
                                        ├── ToolCallEnd
                                        ├── UsageInfo
                                        ├── StreamDone
                                        └── StreamError

                                        + 每个事件携带 partial: AssistantMessage  ← 新增
```

### 2.2 新增事件类型

```java
public sealed interface StreamEvent {

    /**
     * 当前 AssistantMessage 的完整快照。
     * 所有 12 种子类型都通过 record 构造器参数或方法体实现此方法。
     */
    AssistantMessage partial();

    /** 流开始。Agent Loop 用它初始化消息槽位。 */
    record Start(AssistantMessage partial) implements StreamEvent {}

    // ── 文本块（text_start → text_delta* → text_end）─────────

    /** 文本块开始。携带 contentIndex 定位消息中的第几个内容块。 */
    record TextStart(int contentIndex) implements StreamEvent {}

    /** 文本增量。contentIndex 定位消息中的内容块索引。 */
    record TextDelta(int contentIndex, String delta) implements StreamEvent {}

    /** 文本块结束。携带完整文本和 contentIndex。 */
    record TextEnd(int contentIndex, String text) implements StreamEvent {}

    // ── 思考块（thinking_start → thinking_delta* → thinking_end）─

    /** 思考块开始。 */
    record ThinkingStart(int contentIndex) implements StreamEvent {}

    /** 思考增量。独立的思考事件，不再复用 TextDelta。 */
    record ThinkingDelta(int contentIndex, String delta) implements StreamEvent {}

    /** 思考块结束。 */
    record ThinkingEnd(int contentIndex, String thinking) implements StreamEvent {}

    // ── 工具调用 ─────────────────────────────────────────

    /**
     * 工具调用开始。contentIndex 定位消息中的内容块索引。
     * 对齐 pi：toolcall_start 只含 contentIndex + partial，
     * 工具名/ID 在 toolcall_end 中到达。
     */
    record ToolCallStart(int contentIndex) implements StreamEvent {}

    /** 工具调用参数增量。 */
    record ToolCallDelta(int contentIndex, String id, String jsonDelta) implements StreamEvent {}

    /** 工具调用结束。携带完整 ToolCall 信息。 */
    record ToolCallEnd(int contentIndex, String id, String name, Map<String, Object> arguments) implements StreamEvent {}

    // ── 元事件 ───────────────────────────────────────────

    record UsageInfo(long inputTokens, long outputTokens) implements StreamEvent {}

    /**
     * 流正常结束。
     * 对齐 pi 的 done 事件：携带最终 AssistantMessage 快照 + 停止原因。
     * partial 由 sealed interface 的 partial() 方法提供——所有 12 种事件都携带。
     */
    record StreamDone(String reason, UsageInfo usage, AssistantMessage partial) implements StreamEvent {}

    /**
     * 流出错。
     * 对齐 pi 的 error 事件：携带截至错误时的 AssistantMessage 快照 +
     * reason 判别器（"aborted" | "error"）+ 原始异常。
     * partial 由 sealed interface 的 partial() 方法提供——所有 12 种事件都携带。
     */
    record StreamError(String reason, Throwable error, AssistantMessage partial) implements StreamEvent {}
}
```

### 2.3 `partial` 字段

每个 `StreamEvent` 添加 `partial()` 方法，返回当前消息的完整快照：

```java
public sealed interface StreamEvent {
    /**
     * 当前 AssistantMessage 的完整快照。
     * 对齐 pi：所有 12 种事件都携带 partial。
     * Agent Loop 用此做"原地替换"——收到事件就用 partial 覆盖
     * context 的最后一条消息，不用自己拼接增量。
     */
    AssistantMessage partial();
}
```

`AssistantMessage` 定义在 `pi-java-ai` 模块（`com.pijava.ai.message`）中，**不是** `pi-java-agent-core` 的类型。
这避免了 `pi-java-ai` → `pi-java-agent-core` 的反向依赖（依赖方向是 `ai ← agent-core`）。
`pi-java-agent-core` 可直接复用此类型：

```java
// pi-java-ai 模块：com.pijava.ai.message.AssistantMessage
public record AssistantMessage(
    String id,
    List<ContentBlock> content,
    UsageInfo usage,
    String stopReason
) {}
```

**与 Phase 1 `Message.AssistantMessage` 的区别**：Phase 1 的 `Message.AssistantMessage` 是 LLM 的静态响应；
`AssistantMessage` 是流式构建中的**可变快照**——每收到一个事件就更新一次。两者字段相似但用途不同，
后续可考虑统一。

### 2.4 协议适配器变更

4 个适配器需要在流开始时发送 `Start` 事件，并填充 `partial` 字段。以 Anthropic 为例：

```java
// 流开始
publisher.submit(new StreamEvent.Start(partial));
// text 开始
publisher.submit(new StreamEvent.TextStart(contentIndex, partial));
// text delta（contentIndex 替代 type 字段；thinking 用独立的 ThinkingDelta）
publisher.submit(new StreamEvent.TextDelta(contentIndex, delta, partial));
// text 结束
publisher.submit(new StreamEvent.TextEnd(contentIndex, text, partial));
```

## 3. ThinkingLevel 系统（P2a-2）

### 3.1 类型定义

对齐 pi 的 `ThinkingLevel`（6 级刻度）和 `ModelThinkingLevel`（`"off" | ThinkingLevel`）两层设计。
Java 用 `sealed interface` + `record` 实现，符合 Erasable Java 规范。

```java
/**
 * 思考深度六级刻度。
 * 对齐 pi 的 ThinkingLevel："minimal"|"low"|"medium"|"high"|"xhigh"|"max"。
 * OFF 不是 ThinkingLevel，是独立的 ModelThinkingLevel.Off。
 */
public sealed interface ThinkingLevel {
    record Minimal() implements ThinkingLevel {}  // ~1024 tokens
    record Low() implements ThinkingLevel {}      // ~2048 tokens
    record Medium() implements ThinkingLevel {}   // ~8192 tokens
    record High() implements ThinkingLevel {}     // ~16384 tokens
    record XHigh() implements ThinkingLevel {}    // 模型最大值

    /** 六级刻度的自然排序（用于 clamp 回退）。 */
    static List<ThinkingLevel> ordered() {
        return List.of(new Minimal(), new Low(), new Medium(),
                       new High(), new XHigh());
    }

    /**
     * 回退逻辑：先向上找（思考更多通常比更少安全），找不到再向下。
     * 对齐 pi clampThinkingLevel()。
     */
    static ThinkingLevel clamp(ThinkingLevel requested,
                                Set<ThinkingLevel> supported) {
        var ordered = ordered();
        if (supported.contains(requested)) return requested;
        int idx = ordered.indexOf(requested);
        // 先向上找
        for (int i = idx + 1; i < ordered.size(); i++) {
            if (supported.contains(ordered.get(i))) return ordered.get(i);
        }
        // 再向下找
        for (int i = idx - 1; i >= 0; i--) {
            if (supported.contains(ordered.get(i))) return ordered.get(i);
        }
        return new Minimal(); // 兜底
    }
}

/**
 * 模型思考级别：关闭 或 启用某级别。
 * 对齐 pi 的 ModelThinkingLevel = "off" | ThinkingLevel。
 */
public sealed interface ModelThinkingLevel {
    record Off() implements ModelThinkingLevel {}
    record Enabled(ThinkingLevel level) implements ModelThinkingLevel {}

    static ModelThinkingLevel off() { return new Off(); }
    static ModelThinkingLevel of(ThinkingLevel level) { return new Enabled(level); }
}
```

### 3.2 ThinkingLevelMap

每个模型自带翻译表，声明"我这台模型每级对应什么参数"：

```java
/**
 * 模型自带的思考级别翻译表。
 * 对齐 pi Model.thinkingLevelMap。
 * 只包含模型支持的 ThinkingLevel（不含 Off——Off 直接对应 ThinkingConfig.OFF）。
 */
public record ThinkingLevelMap(
    Map<ThinkingLevel, ThinkingConfig> levelMap
) {
    public ThinkingConfig forLevel(ModelThinkingLevel level) {
        return switch (level) {
            case ModelThinkingLevel.Off o  -> ThinkingConfig.OFF;
            case ModelThinkingLevel.Enabled e ->
                levelMap.getOrDefault(ThinkingLevel.clamp(e.level(), levelMap.keySet()),
                                      ThinkingConfig.OFF);
        };
    }
}

public record ThinkingConfig(
    boolean enabled,
    OptionalInt budgetTokens,    // Anthropic: thinking.budget_tokens
    Optional<String> effort      // OpenAI: reasoning_effort
) {
    public static final ThinkingConfig OFF =
            new ThinkingConfig(false, OptionalInt.empty(), Optional.empty());
}
```

### 3.3 ModelInfo 扩展

Phase 1 的 `ModelInfo` 新增字段：

```java
public record ModelInfo(
    ModelId<?> id,
    String displayName,
    Set<ModelCapability> capabilities,
    int maxInputTokens,
    int maxOutputTokens,
    boolean deprecated,
    PricingInfo pricing,
    ThinkingLevelMap thinkingLevelMap    // ← 新增
) {}
```

## 4. Entry 类型系统（P2a-3）

完整对齐 pi 的 7 种 Entry 子类型：

```java
/**
 * Entry 共享标识字段。Java sealed 子类型组合此 header，消除字段重复。
 */
public record EntryHeader(
    String id,
    long seq,
    String parentId,
    Instant timestamp
) {}

/**
 * 用户可见的持久化事件，出现在转录（transcript）中。
 * 对齐 pi 的 Entry sealed union。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Entry.Message.class, name = "message"),
    @JsonSubTypes.Type(value = Entry.ModelChange.class, name = "model_change"),
    @JsonSubTypes.Type(value = Entry.ThinkingLevelChange.class, name = "thinking_level_change"),
    @JsonSubTypes.Type(value = Entry.ActiveToolsChange.class, name = "active_tools_change"),
    @JsonSubTypes.Type(value = Entry.Compaction.class, name = "compaction"),
    @JsonSubTypes.Type(value = Entry.BranchSummary.class, name = "branch_summary"),
    @JsonSubTypes.Type(value = Entry.Custom.class, name = "custom")
})
public sealed interface Entry {

    EntryHeader header();

    /** 用户/助手/工具消息。Phase 2a 只用 user + assistant 角色。 */
    record Message(
        EntryHeader header,
        String role,           // "user" | "assistant" | "tool"
        List<ContentBlock> blocks
    ) implements Entry {}

    /** 模型变更（Phase 2c 使用）。 */
    record ModelChange(
        EntryHeader header,
        String provider, String modelId
    ) implements Entry {}

    /** 思考级别变更（Phase 2a 使用——初始化时写入）。
     *  level 值为 ModelThinkingLevel 的字符串表示：
     *  "off" | "minimal" | "low" | "medium" | "high" | "xhigh"
     */
    record ThinkingLevelChange(
        EntryHeader header,
        String level
    ) implements Entry {}

    /** 活跃工具集变更（Phase 2b 使用）。 */
    record ActiveToolsChange(
        EntryHeader header,
        List<String> toolNames
    ) implements Entry {}

    /** 上下文压缩记录（Phase 2c 使用）。 */
    record Compaction(
        EntryHeader header,
        String reason,        // "overflow" | "manual"
        int entriesBefore,
        int entriesAfter
    ) implements Entry {}

    /** 分支摘要（Phase 2c 使用）。 */
    record BranchSummary(
        EntryHeader header,
        String summary
    ) implements Entry {}

    /** 自定义事件（扩展用）。 */
    record Custom(
        EntryHeader header,
        String kind,
        Map<String, Object> data
    ) implements Entry {}
}
```

**Phase 2a 实际使用的 Entry 类型**：`Message`（user + assistant）、`ThinkingLevelChange`（初始化时写入）。其余 5 种类型定义完整但 Phase 2a 不调用——代码中不出现这些构造即为正常，下游 `switch` 可以用 `default` 分支处理。

## 5. LaneRecord 类型系统（P2a-4）

对齐 pi 的 9 种子类型。Java `sealed interface` 要求所有子类型在编译期声明，因此必须一次性定义全部 9 种——但 Phase 2a 只构造其中 5 种实例。

```java
/**
 * LaneRecord 共享标识字段。
 */
public record RecordHeader(long seq, Instant timestamp) {}

/**
 * 车道级别的内部操作记录，用于调试和审计。
 * 对齐 pi 的 LaneRecord sealed union。
 * Java sealed 类型要求一次性声明全部子类型（编译期检查），
 * 但各 Phase 按需构造实例。
 */
public sealed interface LaneRecord {
    RecordHeader header();

    // ═══ Phase 2a 构造 ═══════════════════════════════════

    /** 一次操作（run / resume）开始。 */
    record OperationStarted(
        RecordHeader header,
        String runId,
        String intent       // 用户意图摘要
    ) implements LaneRecord {}

    /** 中止请求。 */
    record AbortRequested(
        RecordHeader header,
        String reason
    ) implements LaneRecord {}

    /** 操作完成。 */
    record OperationFinished(
        RecordHeader header,
        String runId,
        String status       // "completed" | "aborted" | "error"
    ) implements LaneRecord {}

    /** 单次 LLM 调用尝试。 */
    record StepAttempt(
        RecordHeader header,
        int stepIndex,
        long inputTokens,
        long outputTokens
    ) implements LaneRecord {}

    /** Token 用量记录。 */
    record UsageRecord(
        RecordHeader header,
        long inputTokens,
        long outputTokens,
        String modelId
    ) implements LaneRecord {}

    // ═══ Phase 2b 构造 ═══════════════════════════════════

    /** 工具开始执行（Phase 2b）。 */
    record ToolStarted(
        RecordHeader header,
        String toolCallId,
        String toolName,
        Map<String, Object> arguments
    ) implements LaneRecord {}

    // ═══ Phase 2c 构造 ═══════════════════════════════════

    /** 队列入队（Phase 2c）。 */
    record QueueEnqueued(
        RecordHeader header,
        String queueType,    // "steer" | "followUp" | "nextRun"
        String content
    ) implements LaneRecord {}

    /** 队列取消（Phase 2c）。 */
    record QueueCancelled(
        RecordHeader header,
        String queueType
    ) implements LaneRecord {}

    /** 写操作延迟（Phase 2c）。 */
    record WriteDeferred(
        RecordHeader header,
        String entryId
    ) implements LaneRecord {}
}
```

**设计说明**：为什么定义 9 种但 Phase 2a 只用 5 种？Java `sealed interface` 的 `permits` 子句必须在编译期列出所有子类型，无法跨 Phase 增量添加。这是 Java 类型系统与分阶段开发的折中：**定义一次，按需构造**。`switch` 表达式用 `default` 分支处理未激活的子类型。

## 6. AgentHarness 骨架（P2a-5）

### 6.1 状态机

对齐 pi 的 `RunPhase` 枚举：

```
idle → run(prompt) → assistant(ready) → assistant(effect_pending)
                         ↑                        │
                         │     stream_assistant    │
                         │                        ↓
                         │              checkpoint(may_finish)
                         │                        │
                         │              ┌─────────┘
                         │              ▼
                         │         stopReason?
                         │         ├─ "stop" → idle
                         │         ├─ "toolUse" → (Phase 2b)
                         │         ├─ "error" → idle（记录错误）
                         │         └─ "length" → idle
                         │
                         └── (Phase 2b) tool results → assistant
```

Phase 2a 只走到 `stopReason` 为止，没有工具调用回环。

**设计说明 — Java 适配层**：pi 没有显式的 `RunPhase` 枚举。pi 通过 `LaneState.operation` 是否为 `null` + `operation.step.kind` 来推导状态。Java 引入 `RunPhase` 枚举是因为 Java 没有 TypeScript 的 nullable 联合类型 + discriminated union 那样方便的模式匹配。`RunPhase` 是 `LaneState` 的派生值，不是独立的状态存储。

### 6.2 核心类

```java
/**
 * 手动驱动的 Agent 运行时。
 * 对齐 pi 的 AgentHarness + AgentLane 接口。
 * StreamFn 通过 HarnessConfig 注入，executeAction(StreamAssistant) 内部调用 LLM。
 *
 * Phase 2a 只暴露手动驱动 API。多车道/lane/watch/hook 延后到 Phase 2c。
 */
public class AgentHarness implements AutoCloseable {

    // ── 构造 ─────────────────────────────────────────────

    /**
     * 创建新的 AgentHarness。
     * @param config 包含 StreamFn、模型、系统提示、工具集等
     */
    public static AgentHarness create(HarnessConfig config);

    // ── 手动驱动（Phase 2a 核心）─────────────────────────

    /**
     * 查看下一个待执行的动作，不改变状态。
     * 返回 null 表示当前无可执行动作（run 结束）。
     */
    public Action peekAction();

    /**
     * 执行 peekAction 返回的动作，等待其完成。
     * 对于 StreamAssistant：内部调用 StreamFn 消费事件流，更新 LaneState。
     * 返回下一个待执行的 Action，或 null 表示 run 结束。
     * 对齐 pi：executeAction 也返回下一个 ActionInfo（pi 中返回 Promise<ActionInfo|undefined>）。
     */
    public Action executeAction(Action action);

    // ── 操作 ─────────────────────────────────────────────

    /**
     * 发起一次新的 run。Phase 2a 只接受文本 prompt。
     *
     * <p><b>单轮语义</b>：每次 run() 都会重置 lane 状态（含 transcript.clear()），
     * 表示一个全新的独立会话。多轮连续（历史累积）由 Phase 3 的
     * steer/followUp 队列承载，而非重复调用 run()。这与 pi 的 run() 追加
     * 行为对应到 Phase 3 的多轮机制，Phase 2 的 run() 保持单轮。</p>
     */
    public Action run(String prompt);

    /** 中止当前 run。 */
    public void abort();

    // ── 结果 ─────────────────────────────────────────────

    /** 返回最近一次 run 的最终 assistant 消息。 */
    public AssistantMessage lastAssistantMessage();

    // ── 模型/配置（Phase 2a 基础版）─────────────────────

    public ModelId<?> getModel();
    public void setModel(ModelId<?> model);
    public ModelThinkingLevel getThinkingLevel();
    public void setThinkingLevel(ModelThinkingLevel level);

    @Override
    public void close();
}
```

#### HarnessConfig

```java
/**
 * AgentHarness 创建配置。
 * 对齐 pi 的 AgentHarnessOptions。
 */
public record HarnessConfig(
    /** LLM 流式调用函数。executeAction(StreamAssistant) 内部使用。 */
    StreamFn streamFn,

    /** 当前模型标识。 */
    ModelId<?> model,

    /** 思考模式（Off 或启用某级别）。 */
    ModelThinkingLevel thinkingLevel,

    /** 系统提示。Phase 2a 为固定字符串，Phase 2c 扩展为模板。 */
    String systemPrompt,

    /** 活跃工具集（Phase 2b 使用）。 */
    Set<String> activeTools,

    /** 最大输入 token 数（用于溢出检测）。 */
    int maxInputTokens
) {
    public HarnessConfig {
        activeTools = Set.copyOf(activeTools);
    }

    public static Builder builder() { /* ... */ }
}
```

### 6.3 Action 类型

对齐 pi 的 `ActionInfo` 联合类型。Phase 2a 子集；Phase 2b/2c 逐步扩充。

**与已有骨架的关系**：已有 `AgentHarness` 骨架使用名称为 `Action` 的 sealed interface。Phase 2a 保留同名并扩充子类型。

```java
/**
 * 描述一个待执行的副作用。由 peekAction() 返回。
 * Phase 2a 子集；Phase 2b/2c 逐步扩充。
 * 对齐 pi 的 ActionInfo 联合类型（agent-harness.ts:182）。
 */
public sealed interface Action {

    /**
     * 调用 LLM 获取下一个响应 chunk。
     * step 取值对齐 pi：
     *   "assistant"      — Phase 2a
     *   "compaction"     — Phase 2c
     *   "branch_summary" — Phase 2c
     */
    record StreamAssistant(
        String step,     // "assistant" | "compaction" | "branch_summary"
        int attempt
    ) implements Action {}

    /**
     * 追加一条 Entry 到转录。
     * 对齐 pi 的引用模式：entry 已由 harness 内部创建在 pendingWrites 中，
     * 此 action 只传递标识（entryType + entryId），不传递完整对象。
     * Phase 2a 单 lane 同步写入；pi 的 apply_pending_write 在 Phase 2c 多 lane 时加入。
     */
    record AppendEntry(
        String entryType,  // "message" | "thinking_level_change" | ...
        String entryId
    ) implements Action {}

    /**
     * 尝试结束当前 run。
     * 对齐 pi 的 try_finish_run：可能因 lane 状态不符而拒绝。
     * pi 的 finish_operation 是独立的操作级 action；Phase 2a 单 lane 合并为一个 TryFinishRun。
     * Phase 2c 多 lane 时拆分为 try_finish_run + finish_operation。
     */
    record TryFinishRun(
        String outcome    // "completed" | "failed"
    ) implements Action {}

    /** 执行工具（Phase 2b 使用）。 */
    record ExecuteTool(
        String toolCallId,
        String toolName
    ) implements Action {}
}
```

### 6.4 AgentHarness 内部状态

```java
// AgentHarness 内部维护的 lane 状态
class LaneState {
    RunPhase phase;              // idle | assistant | checkpoint
    List<Entry> transcript;      // 当前 lane 的 Entry 链（消息的唯一数据源）
    String runId;
    int stepIndex;
    AssistantMessage partial;    // 正在构建的 assistant 消息（来自最后一个 partial 快照）

    /**
     * 最新自有 entry 的快照信息。
     * 对齐 pi LaneState.newestOwn。
     * checkpoint 阶段用 stopReason 判断 TryFinishRun 的 outcome。
     */
    NewestOwn newestOwn;

    List<ProvisionedEntry> pendingWrites;  // 待写入的 Entry（AppendEntry action 引用）
    // Phase 2b 扩展：
    // List<ToolCall> pendingToolCalls;
    // Phase 2c 扩展：
    // Queue<String> steerQueue, followUpQueue;

    // ═══════════════════════════════════════════════════════
    // 对齐 pi：消息列表不从 LaneState 存储，而是从 transcript
    // entries 在每次 LLM 请求前动态构建。
    // pi reducer.ts:79-109 中 LaneState 不持有裸消息列表。
    // ═══════════════════════════════════════════════════════

    /**
     * 从 transcript entries 构建 LLM 消息列表。
     * 每次 StreamAssistant 时调用，确保消息反映最新的 Entry 状态。
     */
    List<Message> buildMessages();
}

/**
 * 最新自有 entry 的摘要信息。
 * 对齐 pi LaneState.newestOwn。
 */
record NewestOwn(
    String entryId,
    String entryType,    // "message" | "thinking_level_change" | ...
    String role,         // "user" | "assistant" | "tool"（仅 message 类型）
    String stopReason    // "stop" | "toolUse" | "error" | "length" | null
) {}
```

### 6.5 peekAction() 状态转换

```
当前状态              peekAction() 返回
────────────────────  ──────────────────────
idle                   null（没有 run 在跑，等外层调用 run()）
assistant(ready)       StreamAssistant("assistant", 0)
assistant(pending)     等待 STREAM_ASSISTANT 执行完成后的结果
checkpoint             AppendEntry（有待写入的 Entry）
checkpoint(空)         TryFinishRun（没有待写入的 Entry）
```

## 7. Agent Loop（P2a-6）

### 7.1 核心循环

`AgentLoop` 是薄驱动层，只负责 `peekAction()` → `executeAction()` 循环。
**StreamFn 已通过 `HarnessConfig` 注入 `AgentHarness`**，`executeAction(StreamAssistant)` 内部完成 LLM 调用 + 事件消费。Loop 不需要知道 LLM 调用细节。

**为什么需要 AgentLoop**（设计说明）：pi 的 `agentLoop()` 是 `agent-harness.ts` 内的一个函数。
Java 化时提取为独立类，因为：
1. 它是 Phase 3 CLI/TUI 的连接点——TUI 的事件循环调用 `AgentLoop`，非 TUI 模式（`-p`）也用它
2. 独立类便于单元测试（不依赖 harness 的其他方法如 `runToCompletion()`）
3. 同时 `AgentHarness.runToCompletion()`（02-design 已定义）在 Phase 2c 实现，内部复用 AgentLoop 逻辑

```java
/**
 * Agent 循环——外层驱动逻辑。
 * 对齐 pi 的 agentLoop() 函数。
 * StreamFn 由 AgentHarness 内部持有（通过 HarnessConfig 注入），Loop 不直接接触。
 */
public class AgentLoop {

    private final AgentHarness harness;

    public AgentLoop(AgentHarness harness) {
        this.harness = harness;
    }

    /**
     * 运行一次完整的 turn：用户消息 → LLM → 响应。
     * Phase 2a 无工具调用。
     */
    public AssistantMessage run(String userPrompt) {
        // 1. 发起 run——写入 user Message Entry + ThinkingLevelChange Entry
        var action = harness.run(userPrompt);

        // 2. 驱动循环：executeAction 返回下一个 action，null 表示结束
        while (action != null) {
            action = harness.executeAction(action);
        }

        // 3. 返回最终的 assistant 消息
        return harness.lastAssistantMessage();
    }
}
```

**关键设计点**：
- `run()` 方法内部写入 `ThinkingLevelChange` Entry（如果 thinkingLevel 非默认值），确保初始化 Entry 在 `StreamAssistant` 之前持久化
- `executeAction(StreamAssistant)` 是阻塞调用——内部用虚拟线程消费事件流，直到 `StreamDone` 或 `StreamError`
- `executeAction(AppendEntry)` 将 pendingWrites 中的 Entry 标记为已写入
- `executeAction(TryFinishRun)` 检查 `newestOwn.stopReason` 决定是否真正结束

### 7.2 StreamFn 接口

```java
/**
 * 流式调用 LLM 的函数签名。
 * 对齐 pi 的 StreamFn 类型。
 * 通过 HarnessConfig 注入 AgentHarness，AgentLoop 不直接持有。
 */
@FunctionalInterface
public interface StreamFn {
    /**
     * 发送流式请求。
     * 契约：不 throw 异常，错误编码在 StreamEvent.StreamError 中。
     * @param messages  上下文消息列表（由 AgentHarness 从 LaneState 构建）
     * @param model     模型标识
     * @param options   额外选项（含 thinking 参数、maxTokens 等）
     */
    StreamIterator<StreamEvent> stream(
        List<Message> messages,
        ModelId<?> model,
        StreamOptions options
    );
}

/**
 * 流式请求的额外选项。
 * 对齐 pi 的 SimpleStreamOptions。
 */
public record StreamOptions(
    OptionalInt maxTokens,
    OptionalDouble temperature,
    ThinkingConfig thinking,        // 由 ThinkingLevel 翻译而来
    List<ToolDefinition> tools      // Phase 2b 使用
) {
    public static StreamOptions defaults() {
        return new StreamOptions(OptionalInt.empty(), OptionalDouble.empty(),
                                 ThinkingConfig.OFF, List.of());
    }
}
```

### 7.3 事件消费逻辑（executeAction 内部）

`executeAction(StreamAssistant)` 内部调用 `StreamFn`，逐事件消费。**核心策略：使用 `partial` 快照，不做手动拼接。**

§2.3 的 `partial()` 方法已确保**所有 12 种事件（包括 Start）** 携带当前 AssistantMessage 的完整快照。AgentHarness 消费事件时只需：

1. 收到 `Start` → 用 `partial` 初始化消息槽位
2. 收到其他事件 → 用 `partial` 覆盖 LaneState 中的当前消息
3. 收到 `StreamDone` → 取 `partial`（最终完整消息）+ `reason`
4. 收到 `StreamError` → 取 `partial`（截至错误的快照）+ `reason`（"aborted" | "error"）

```java
// AgentHarness.executeAction(StreamAssistant) 内部逻辑（简化版）
Action executeStreamAssistant(Action.StreamAssistant sa) {
    var messages = laneState.buildMessages();  // 从 transcript entries 动态构建
    var options = buildStreamOptions();         // 从 ModelThinkingLevel 翻译

    var iter = streamFn.stream(messages, getModel(), options);

    while (iter.hasNext()) {
        var event = iter.next();
        // 所有事件都携带 partial（包括 Start），直接覆盖
        if (event.partial() != null) {
            laneState.partial = event.partial();
        }
    }

    // 根据最终 partial 的 stopReason 决定下一步
    laneState.newestOwn = deriveNewestOwn(laneState.partial);
    return transitionToCheckpoint();
}
```

**设计对比**：pi 也使用 `partial` 做原地替换（`agent-harness.ts` 中 `stream_assistant` 处理逻辑）。
所有 12 种事件都携带 `partial: AssistantMessage`——包括 `start`。手动拼接 `ContentBlock` 的方案不再需要。

## 8. 上下文管理（P2a-7）

### 8.1 Token 估算

```java
/**
 * 估算上下文消息的 token 数。
 * 对齐 pi estimateContextTokens()。
 * 简化版：字符数 / 4（英文约 4 char/token）× 语言系数。
 */
public class ContextEstimator {
    private static final double CHARS_PER_TOKEN = 3.5; // 中英文混合估算

    /** 估算消息列表的总 token 数。 */
    public static long estimateTokens(List<Message> messages);

    /** 估算是否可能溢出。返回需要压缩的消息数。 */
    public static int checkOverflow(List<Message> messages,
                                     int maxInputTokens,
                                     double safetyMargin);
}
```

### 8.2 溢出检测

```java
/**
 * 上下文溢出检测。
 * 对齐 pi isContextOverflow() 三重检测。
 *
 * Phase 2a：仅定义类和方法签名，编写单元测试。
 * Phase 2c：在 streamSimple() + compaction 流程中集成。
 */
public class OverflowDetector {

    /**
     * 三重检测：
     * 1. 错误消息模式匹配（如 "context length"、"too long"）
     * 2. token 数对比（估算 tokens > 模型窗口 × 安全系数）
     * 3. 输出为零 + stopReason 为 "length"
     *
     * Phase 2a 产出：方法实现 + 单元测试，不在 AgentLoop 中调用。
     * Phase 2c 集成点：streamSimple() 发请求前调用；executeAction 收到 error 后调用。
     */
    public static boolean isOverflow(Throwable error,
                                      String stopReason,
                                      UsageInfo usage,
                                      int maxInputTokens);
}
```

## 9. streamSimple() 便捷函数（P2a-8）

```java
/**
 * 便捷流式调用，自动处理 ThinkingLevel 翻译 + 上下文溢出检测。
 * 对齐 pi 的 streamSimple() 包装。
 *
 * Phase 2a 职责：
 *   1. ThinkingLevel → Provider 参数翻译（通过 ThinkingLevelMap）
 *   2. 发请求前调用 ContextEstimator + OverflowDetector 检测是否溢出
 *   3. 溢出时返回 OverflowDetector 的诊断信息（stopReason="overflow"），
 *      由 AgentLoop 的上层调用者决定如何处理
 *
 * Phase 2c 扩展：溢出时自动触发 compaction（而非仅报告）
 */
public class StreamSimple {

    /**
     * 流式调用 LLM。
     * 1. 根据 ModelThinkingLevel + ThinkingLevelMap 自动翻译成各 Provider 的具体参数
     *    （Anthropic: thinking.budget_tokens; OpenAI: reasoning_effort）
     * 2. 调用 ContextEstimator.checkOverflow() 预检测；若溢出，注入溢出标记到请求
     * 3. 发送请求并返回事件流
     * 4. 请求结束后调用 OverflowDetector.isOverflow() 后检测
     *
     * TODO Phase 2c：溢出时自动触发 compaction 而非仅标记
     */
    public static StreamIterator<StreamEvent> stream(
            ModelInfo model,
            List<Message> messages,
            ModelThinkingLevel reasoning,
            StreamFn streamFn);
}
```

## 10. 测试策略（P2a-9）

### 10.1 测试分层

| 层级 | 内容 | 依赖 |
|------|------|------|
| StreamEvent 序列化测试 | 12 种事件的 Jackson 序列化/反序列化（含 partial 字段） | 无 |
| Entry/EntryHeader 测试 | 7 种 Entry + EntryHeader 的构建和字段验证 | 无 |
| LaneRecord/RecordHeader 测试 | 9 种 LaneRecord + RecordHeader 的构建和字段验证 | 无 |
| ThinkingLevel 测试 | sealed interface + clamp 回退逻辑 + ModelThinkingLevel 开关 | 无 |
| AgentHarness 状态机测试 | peekAction/executeAction 状态转换 + StreamAssistant 内部调用 | FauxProvider |
| AgentLoop 集成测试 | 文本对话：user → assistant（FauxProvider.text("Hi!")） | FauxProvider |
| AgentLoop 错误测试 | LLM 返回 error → partial 快照含 stopReason="error" | FauxProvider.error() |
| partial 快照消费测试 | 所有 12 种事件携带 partial → harness 直接覆盖 | FauxProvider |
| 上下文溢出检测测试 | 模拟溢出场景（三重检测逻辑） | 无 |
| streamSimple 翻译+溢出测试 | ModelThinkingLevel → Provider 参数映射 + 溢出预检测 | 无 |

### 10.2 覆盖率目标

- Entry/LaneRecord 类型：≥ 95%（纯数据类）
- AgentHarness 状态机：≥ 90%
- AgentLoop：≥ 85%
- ThinkingLevel + 上下文管理：≥ 90%

## 11. 包结构

```
# pi-java-agent-core 模块（com.pijava.agent）
com.pijava.agent/
├── harness/
│   ├── AgentHarness.java          ← 状态机主体
│   ├── HarnessConfig.java         ← 创建配置（含 StreamFn + 模型 + 系统提示等）
│   ├── LaneState.java             ← 内部 lane 状态（package-private，含 NewestOwn）
│   ├── Action.java                ← peekAction 返回值 sealed 类型
│   ├── StreamFn.java              ← LLM 调用函数接口（通过 HarnessConfig 注入）
│   └── StreamOptions.java         ← 流式请求额外选项
├── loop/
│   └── AgentLoop.java             ← 外层驱动循环（不持有 StreamFn）
├── entry/
│   ├── Entry.java                 ← 7 种 Entry 密封接口
│   ├── EntryHeader.java           ← Entry 共享标识（id, seq, parentId, timestamp）
│   └── ProvisionedEntry.java      ← 已预备待写入的 Entry（pendingWrites 元素）
├── record/
│   ├── LaneRecord.java            ← 9 种 LaneRecord 密封接口
│   └── RecordHeader.java          ← LaneRecord 共享标识（seq, timestamp）
├── thinking/
│   ├── ThinkingLevel.java         ← sealed interface + 5 个 record 子类型
│   ├── ModelThinkingLevel.java    ← Off | Enabled(ThinkingLevel)
│   ├── ThinkingLevelMap.java      ← 模型翻译表
│   └── ThinkingConfig.java        ← Provider 特定参数
├── context/
│   ├── ContextEstimator.java      ← token 估算
│   └── OverflowDetector.java      ← 溢出检测（Phase 2a 定义 + 单测 + streamSimple 集成）
└── stream/
    └── StreamSimple.java          ← 便捷函数（thinking 翻译 + 溢出检测）

# ── 以下类型在 pi-java-ai 模块（com.pijava.ai）───────────
# pi-java-ai/
#   ├── stream/
#   │   └── StreamEvent.java       ← 12 种事件 + partial: AssistantMessage
#   └── message/
#       └── AssistantMessage.java  ← 流式快照类型（pi-java-ai 模块，避免反向依赖）
```

## 12. 里程碑与验收

```bash
# 1. 全量编译
mvn clean verify
# → BUILD SUCCESS

# 2. 12 种 StreamEvent 序列化往返
mvn test -pl pi-java-ai -Dtest=StreamEventTest

# 3. Agent Loop 端到端（FauxProvider 模拟）
mvn test -pl pi-java-agent-core -Dtest=AgentLoopTest

# 4. 命令行跑通
pi-java -p "Hello, who are you?"
# → 返回 LLM 响应文本
```

---

## 13. Phase 2a 不做

- 工具调用（→ Phase 2b）
- 多车道（→ Phase 2c）
- Hook/Event 系统（→ Phase 2c）
- 手动驱动 `executeAction`/`runToCompletion` 全功能（→ Phase 2c）
- 持久化 SessionStorage（→ Phase 4）
