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
│  │  • LaneState { phase, messages, pendingWrites }   │    │
│  │  • StreamFn（通过 HarnessConfig 注入）            │    │
│  │  • peekAction() → ActionInfo                     │    │
│  │  • executeAction(ActionInfo) → ActionInfo | null  │    │
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

    /** 流开始。Agent Loop 用它初始化消息槽位。 */
    record Start() implements StreamEvent {}

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

    /** 工具调用开始。contentIndex 定位消息中的内容块索引。 */
    record ToolCallStart(int contentIndex, String id, String name) implements StreamEvent {}

    /** 工具调用参数增量。 */
    record ToolCallDelta(int contentIndex, String id, String jsonDelta) implements StreamEvent {}

    /** 工具调用结束。 */
    record ToolCallEnd(int contentIndex, String id, String name, Map<String, Object> arguments) implements StreamEvent {}

    // ── 元事件（已有的，不变）───────────────────────────────

    record UsageInfo(long inputTokens, long outputTokens) implements StreamEvent {}
    record StreamDone(String stopReason, UsageInfo usage) implements StreamEvent {}
    record StreamError(Throwable error) implements StreamEvent {}
}
```

### 2.3 `partial` 字段

每个 `StreamEvent` 添加 `partial()` 方法，返回当前消息的完整快照：

```java
public sealed interface StreamEvent {
    /**
     * 当前 AssistantMessage 的完整快照。
     * Agent Loop 用此做"原地替换"——收到事件就用 partial 覆盖
     * context 的最后一条消息，不用自己拼接增量。
     * 对于 Start 事件返回 null。
     */
    AssistantMessage partial();
}
```

`AssistantMessage` 是 `pi-java-agent-core` 中定义的类型，区别于 Phase 1 的 `Message.AssistantMessage`：

```java
public record AssistantMessage(
    String id,
    List<ContentBlock> content,
    UsageInfo usage,
    String stopReason
) {}
```

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

### 3.1 枚举定义

```java
/**
 * 思考深度六级刻度。
 * 对齐 pi 的 ThinkingLevel 设计。
 */
public enum ThinkingLevel {
    OFF,      // 不思考
    MINIMAL,  // ~1024 tokens
    LOW,      // ~2048 tokens
    MEDIUM,   // ~8192 tokens
    HIGH,     // ~16384 tokens
    XHIGH;    // 模型最大值

    /**
     * 回退逻辑：先向上找（思考更多通常比更少安全），找不到再向下。
     * 对齐 pi clampThinkingLevel()。
     */
    public static ThinkingLevel clamp(ThinkingLevel requested,
                                       Set<ThinkingLevel> supported) {
        if (supported.contains(requested)) return requested;
        // 先向上找
        for (var level : values()) {
            if (level.ordinal() > requested.ordinal()
                    && supported.contains(level)) {
                return level;
            }
        }
        // 再向下找
        for (int i = values().length - 1; i >= 0; i--) {
            if (values()[i].ordinal() < requested.ordinal()
                    && supported.contains(values()[i])) {
                return values()[i];
            }
        }
        return OFF; // 兜底
    }
}
```

### 3.2 ThinkingLevelMap

每个模型自带翻译表，声明"我这台模型每级对应什么参数"：

```java
/**
 * 模型自带的思考级别翻译表。
 * 对齐 pi Model.thinkingLevelMap。
 */
public record ThinkingLevelMap(
    Map<ThinkingLevel, ThinkingConfig> levelMap
) {
    public ThinkingConfig forLevel(ThinkingLevel level) {
        return levelMap.getOrDefault(clamp(level, levelMap.keySet()),
                                     ThinkingConfig.OFF);
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

    String id();
    long seq();
    String parentId();
    Instant timestamp();

    /** 用户/助手/工具消息。Phase 2a 只用 user + assistant 角色。 */
    record Message(
        String id, long seq, String parentId, Instant timestamp,
        String role,           // "user" | "assistant" | "tool"
        List<ContentBlock> blocks
    ) implements Entry {}

    /** 模型变更（Phase 2c 使用）。 */
    record ModelChange(
        String id, long seq, String parentId, Instant timestamp,
        String provider, String modelId
    ) implements Entry {}

    /** 思考级别变更（Phase 2a 使用——初始化时写入）。 */
    record ThinkingLevelChange(
        String id, long seq, String parentId, Instant timestamp,
        String level       // "off" | "minimal" | "low" | "medium" | "high" | "xhigh"
    ) implements Entry {}

    /** 活跃工具集变更（Phase 2b 使用）。 */
    record ActiveToolsChange(
        String id, long seq, String parentId, Instant timestamp,
        List<String> toolNames
    ) implements Entry {}

    /** 上下文压缩记录（Phase 2c 使用）。 */
    record Compaction(
        String id, long seq, String parentId, Instant timestamp,
        String reason,        // "overflow" | "manual"
        int entriesBefore,
        int entriesAfter
    ) implements Entry {}

    /** 分支摘要（Phase 2c 使用）。 */
    record BranchSummary(
        String id, long seq, String parentId, Instant timestamp,
        String summary
    ) implements Entry {}

    /** 自定义事件（扩展用）。 */
    record Custom(
        String id, long seq, String parentId, Instant timestamp,
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
 * 车道级别的内部操作记录，用于调试和审计。
 * 对齐 pi 的 LaneRecord sealed union。
 * Java sealed 类型要求一次性声明全部子类型（编译期检查），
 * 但各 Phase 按需构造实例。
 */
public sealed interface LaneRecord {
    long seq();
    Instant timestamp();

    // ═══ Phase 2a 构造 ═══════════════════════════════════

    /** 一次操作（run / resume）开始。 */
    record OperationStarted(
        long seq, Instant timestamp,
        String runId,
        String intent       // 用户意图摘要
    ) implements LaneRecord {}

    /** 中止请求。 */
    record AbortRequested(
        long seq, Instant timestamp,
        String reason
    ) implements LaneRecord {}

    /** 操作完成。 */
    record OperationFinished(
        long seq, Instant timestamp,
        String runId,
        String status       // "completed" | "aborted" | "error"
    ) implements LaneRecord {}

    /** 单次 LLM 调用尝试。 */
    record StepAttempt(
        long seq, Instant timestamp,
        int stepIndex,
        long inputTokens,
        long outputTokens
    ) implements LaneRecord {}

    /** Token 用量记录。 */
    record UsageRecord(
        long seq, Instant timestamp,
        long inputTokens,
        long outputTokens,
        String modelId
    ) implements LaneRecord {}

    // ═══ Phase 2b 构造 ═══════════════════════════════════

    /** 工具开始执行（Phase 2b）。 */
    record ToolStarted(
        long seq, Instant timestamp,
        String toolCallId,
        String toolName,
        Map<String, Object> arguments
    ) implements LaneRecord {}

    // ═══ Phase 2c 构造 ═══════════════════════════════════

    /** 队列入队（Phase 2c）。 */
    record QueueEnqueued(
        long seq, Instant timestamp,
        String queueType,    // "steer" | "followUp" | "nextRun"
        String content
    ) implements LaneRecord {}

    /** 队列取消（Phase 2c）。 */
    record QueueCancelled(
        long seq, Instant timestamp,
        String queueType
    ) implements LaneRecord {}

    /** 写操作延迟（Phase 2c）。 */
    record WriteDeferred(
        long seq, Instant timestamp,
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
    public ActionInfo peekAction();

    /**
     * 执行 peekAction 返回的动作，等待其完成。
     * 对于 StreamAssistant：内部调用 StreamFn 消费事件流，更新 LaneState。
     * 返回下一个待执行的 ActionInfo，或 null 表示 run 结束。
     */
    public ActionInfo executeAction(ActionInfo action);

    // ── 操作 ─────────────────────────────────────────────

    /** 发起一次新的 run。Phase 2a 只接受文本 prompt。 */
    public ActionInfo run(String prompt);

    /** 中止当前 run。 */
    public void abort();

    // ── 车道（Phase 2c 完善）─────────────────────────────

    public LaneHandle lane();                          // 默认车道
    public LaneHandle createLane(LaneConfig config);   // Phase 2c
    public List<LaneHandle> lanes();

    // ── 快照/订阅（Phase 2c 完善）───────────────────────

    public WatchHandle<LaneSnapshot> watch();

    // ── 模型/配置（Phase 2a 基础版）─────────────────────

    public ModelId<?> getModel();
    public void setModel(ModelId<?> model);
    public ThinkingLevel getThinkingLevel();
    public void setThinkingLevel(ThinkingLevel level);

    // ── Hook 注册（Phase 2c 完善）───────────────────────

    public <T> void on(String hookName, Consumer<T> handler);

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

    /** 思考深度级别。 */
    ThinkingLevel thinkingLevel,

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

### 6.3 ActionInfo 类型

对齐 pi 的 `ActionInfo` 联合类型。Phase 2a 只需要 3 种：

```java
/**
 * 描述一个待执行的副作用。由 peekAction() 返回。
 * Phase 2a 子集；Phase 2b/2c 逐步扩充。
 * 对齐 pi 的 ActionInfo 联合类型（agent-harness.ts:182）。
 */
public sealed interface ActionInfo {

    /** 调用 LLM 获取下一个响应 chunk。 */
    record StreamAssistant(
        String step,     // "assistant"（Phase 2a）/ "compaction"（Phase 2c）
        int attempt
    ) implements ActionInfo {}

    /**
     * 追加一条 Entry 到转录。
     * 对齐 pi 的引用模式：entry 已由 harness 内部创建在 pendingWrites 中，
     * 此 action 只传递标识（entryType + entryId），不传递完整对象。
     */
    record AppendEntry(
        String entryType,  // "message" | "thinking_level_change" | ...
        String entryId
    ) implements ActionInfo {}

    /**
     * 尝试结束当前 run。
     * 对齐 pi 的 try_finish_run：可能因 lane 状态不符而拒绝。
     */
    record TryFinishRun(
        String outcome    // "completed" | "failed"
    ) implements ActionInfo {}

    /** 执行工具（Phase 2b 使用）。 */
    record ExecuteTool(
        String toolCallId,
        String toolName
    ) implements ActionInfo {}
}
```

### 6.4 AgentHarness 内部状态

```java
// AgentHarness 内部维护的 lane 状态
class LaneState {
    RunPhase phase;              // idle | assistant | checkpoint
    List<Entry> transcript;      // 当前 lane 的 Entry 链
    String runId;
    int stepIndex;
    List<Message> messages;      // 当前轮次的 LLM 消息历史
    AssistantMessage partial;    // 正在构建的 assistant 消息

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

        // 2. 驱动循环：peekAction → executeAction 直到 null
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

§2.3 的 `partial()` 方法已确保每个事件携带当前 AssistantMessage 的完整快照。AgentHarness 消费事件时只需：

1. 收到 `Start` → 初始化消息槽位
2. 收到任何携带 `partial` 的事件 → 用 `partial` 覆盖 LaneState 中的当前消息
3. 收到 `StreamDone` → 取最后一个 `partial`，设置 `stopReason` + `usage`
4. 收到 `StreamError` → 设置 `stopReason: "error"`

```java
// AgentHarness.executeAction(StreamAssistant) 内部逻辑（简化版）
ActionInfo executeStreamAssistant(ActionInfo.StreamAssistant sa) {
    var messages = buildMessages(laneState);  // 从 LaneState 构建消息列表
    var options = buildStreamOptions();        // 从 ThinkingLevel 翻译

    var iter = streamFn.stream(messages, getModel(), options);

    while (iter.hasNext()) {
        var event = iter.next();
        switch (event) {
            case StreamEvent.Start s ->
                laneState.partial = null;  // 重置

            case StreamEvent.StreamDone sd ->
                laneState.partial = sd.partial();  // 最终快照

            case StreamEvent.StreamError se ->
                laneState.partial = se.partial();  // 错误快照（含 stopReason="error"）

            default -> {
                // 所有增量事件（TextDelta、ThinkingDelta、ToolCallDelta 等）
                // partial 已包含完整快照，直接覆盖即可
                if (event.partial() != null) {
                    laneState.partial = event.partial();
                }
            }
        }
    }

    // 根据最终 partial 的 stopReason 决定下一步
    laneState.newestOwn = deriveNewestOwn(laneState.partial);
    return transitionToCheckpoint();
}
```

**设计对比**：pi 也使用 `partial` 做原地替换（`agent-harness.ts` 中 `stream_assistant` 处理逻辑）。手动拼接 `ContentBlock` 的方案（如 Phase 1 文档的 `foldEvents`）不再需要——`partial` 机制消除了增量拼接代码。

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
 * 便捷流式调用，自动处理 ThinkingLevel 翻译。
 * 对齐 pi 的 streamSimple() 包装。
 *
 * Phase 2a 职责：ThinkingLevel → Provider 参数翻译
 * Phase 2c 扩展：发请求前检测上下文是否溢出，溢出则触发 compaction
 */
public class StreamSimple {

    /**
     * 流式调用 LLM。
     * 1. 根据 ThinkingLevel + ThinkingLevelMap 自动翻译成各 Provider 的具体参数
     *    （Anthropic: thinking.budget_tokens; OpenAI: reasoning_effort）
     * 2. 发送请求并返回事件流
     *
     * TODO Phase 2c：发请求前调用 ContextEstimator + OverflowDetector，
     *      溢出时触发 compaction 而非直接发送请求
     */
    public static StreamIterator<StreamEvent> stream(
            ModelInfo model,
            List<Message> messages,
            ThinkingLevel reasoning,
            StreamFn streamFn);
}
```

## 10. 测试策略（P2a-9）

### 10.1 测试分层

| 层级 | 内容 | 依赖 |
|------|------|------|
| StreamEvent 序列化测试 | 12 种事件的 Jackson 序列化/反序列化（含 partial 字段） | 无 |
| Entry/LaneRecord 测试 | 7 种 Entry + 9 种 LaneRecord 的构建和字段验证 | 无 |
| ThinkingLevel 测试 | clamp 回退逻辑 | 无 |
| AgentHarness 状态机测试 | peekAction/executeAction 状态转换 + StreamAssistant 内部调用 | FauxProvider |
| AgentLoop 集成测试 | 文本对话：user → assistant（FauxProvider.text("Hi!")） | FauxProvider |
| AgentLoop 错误测试 | LLM 返回 error → partial 快照含 stopReason="error" | FauxProvider.error() |
| partial 快照消费测试 | 流事件携带 partial → harness 直接覆盖，无需手动拼接 | FauxProvider |
| 上下文溢出检测测试 | 模拟溢出场景（三重检测逻辑） | 无 |
| streamSimple 翻译测试 | ThinkingLevel → Provider 参数映射 | 无 |

### 10.2 覆盖率目标

- Entry/LaneRecord 类型：≥ 95%（纯数据类）
- AgentHarness 状态机：≥ 90%
- AgentLoop：≥ 85%
- ThinkingLevel + 上下文管理：≥ 90%

## 11. 包结构

```
com.pijava.agent/
├── harness/
│   ├── AgentHarness.java          ← 状态机主体
│   ├── HarnessConfig.java         ← 创建配置（含 StreamFn + 模型 + 系统提示等）
│   ├── LaneState.java             ← 内部 lane 状态（package-private，含 NewestOwn）
│   ├── ActionInfo.java            ← peekAction 返回值 sealed 类型
│   ├── StreamFn.java              ← LLM 调用函数接口（通过 HarnessConfig 注入）
│   ├── StreamOptions.java         ← 流式请求额外选项
│   └── Hook.java                  ← Hook 注册（Phase 2c）
├── loop/
│   └── AgentLoop.java             ← 外层驱动循环（不持有 StreamFn）
├── entry/
│   ├── Entry.java                 ← 7 种 Entry 密封接口
│   └── ProvisionedEntry.java      ← 已预备待写入的 Entry（pendingWrites 元素）
├── record/
│   └── LaneRecord.java            ← 9 种 LaneRecord 密封接口
├── thinking/
│   ├── ThinkingLevel.java         ← 六级枚举
│   ├── ThinkingLevelMap.java      ← 模型翻译表
│   └── ThinkingConfig.java        ← Provider 特定参数
├── context/
│   ├── ContextEstimator.java      ← token 估算
│   └── OverflowDetector.java      ← 溢出检测（Phase 2a 定义 + 单测，2c 集成）
├── stream/
│   ├── StreamEvent.java           ← 在 pi-java-ai 模块中更新（12 种事件 + partial）
│   └── StreamSimple.java          ← 便捷函数（Phase 2a：thinking 翻译；2c：溢出 + compaction）
└── message/
    └── AssistantMessage.java      ← Agent 层消息类型
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
