# 50 - L8 扩展上下文与会话只读面补齐设计（B284 / B285）

> 【背景摘要】本文档是 `docs/27` §3 的 W2/W3 前置包：把 `ExtensionContext` 缺失的 10 个成员与 `ExtensionSessionView` 缺失的只读成员按 pi 锚点 `56cae7823` 逐条落地，并明确晚绑定、只读边界、示例消费点和测试口径。
>
> **文档状态：已批准（2026-11-08）。** 用户裁决：B284 按实际新增 10 项记账；B285 选 A（新增 9 项，其余 4 项不做）；接受 D233（无 `findOfType/classify`）与 D234（无 `newSession.setup`）。

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 上游文档：`docs/27` §3.1/§3.2、`docs/04` B284/B285、`docs/06` L8
- 本包性质：W2/W3 的**前置依赖**，先于 W2 实施；不改变 W1 已闭环的五件 provider 移植
- 用户既有裁决：示例只移植注册形状与流式事件，不复刻真实 OAuth 网络；Chord 组按 R5 出分母

## 0. 取证口径

1. pi 侧签名与行为逐条取自 `packages/coding-agent/src/core/extensions/types.ts`、`agent-session.ts`、`model-registry.ts`、`session-manager.ts` 与交互模式 `newSession` 实现。
2. Java 侧以 `pi-java-coding-agent` 的 `extension/`、`core/` 与 `pi-java-agent-core` 的 `session/`、`harness/`、`compaction/` 为准。
3. 只补**示例真实消费**或**可直接如实委托**的成员；不为凑接口写空实现或假数据。
4. 本包不含 W2/W3 的示例移植本身；示例验收测试仍归 W2/W3。

## 1. 现状差额

### 1.1 B284：`ExtensionContext`（账 11 项，实际新增 10 项）

`ExtensionContext.java` 现有 `getThinkingLevel()` / `setThinkingLevel()`，故 B284 账上的 `thinkingLevel` 已存在，**只需复核不需要新增**。其余 10 项全部缺失：

| # | pi 成员（`types.ts`） | Java 现状 | 消费示例 | W |
|---|---|---|---|---|
| 1 | `model: Model \| undefined`（只读属性） | 无 | `handoff`、`custom-footer`、`border-status-editor`、`preset`、`qna`、`subagent` | W2/W3/W5 |
| 2 | `modelRegistry: ModelRegistry` | 无 | `custom-compaction`、`handoff`、`jev-router`、`preset`、`qna`、`summarize` | W2/W3 |
| 3 | `getContextUsage(): ContextUsage \| undefined` | 无 | `trigger-compact`、`border-status-editor` | W2/W5 |
| 4 | `compact(options)` | `AgentSession.compact(...)` 存在，未上抛 | `trigger-compact` | W2 |
| 5 | `reload(): Promise<void>` | `AgentSession.reload()` 存在，未上抛 | `reload-runtime` | W2 |
| 6 | `shutdown(): Promise<void>` | 无（`closeForReplacement("quit", null)` 可复用） | `shutdown-command` | W7 |
| 7 | `isIdle(): boolean` | `AgentSession.isRunning()` 存在 | `send-user-message` | W2 |
| 8 | `getSystemPrompt(): string` | `harness.currentSystemPrompt()` 存在 | `system-prompt-header` | W2 |
| 9 | `isProjectTrusted(): boolean` | `SettingsManager.isProjectTrusted()` 存在 | `subagent` | W4 |
| 10 | `newSession(options): Promise<{cancelled}>` | 无 | `handoff` | W2 |

**B284 范围更正**：账上「余 11 成员」中 `thinkingLevel` 已存在（非缺失），实施与销号时按 **10 个新增成员**记账，避免重复翻账。

不在 B284 范围（`docs/27` 已定口径）：pi 的 `signal`、`abort()`、`hasPendingMessages()`、`scopedModels()`。前三个在本锚点 `examples/` 零命中；`scopedModels` Java 已有同名能力。若后续示例出现消费，另开新账。

### 1.2 B285：`ExtensionSessionView`（15 成员，1 已存在）

pi 的 `ReadonlySessionManager` 是 15 成员；Java `ExtensionSessionView` 当前只有 `getEntries()`。按“真实消费点”统计：

| # | pi 成员 | 示例消费 | Java 来源 | 本包处置 |
|---|---|---|---|---|
| 1 | `getEntries()` | 10+ 处 | `AgentSession.accumulatedEntries()` | 已有 |
| 2 | `getBranch()` | `custom-footer`、`handoff`、`qna`、`summarize`、`tic-tac-toe`、`todo`、`tools` | `Session.findEntriesOnBranch(OLDEST_FIRST, none)` | 新增（必须） |
| 3 | `getEntry(id)` | 零命中（仅 `getLeafEntry` 内部需要） | `Session.getEntry(id)` | 新增（内部支撑） |
| 4 | `getLabel(id)` | `bookmark` | `Session.getLabel(id)` | 新增（必须） |
| 5 | `getLeafEntry()` | `git-checkpoint` | `getEntry(getLeafId())` | 新增（必须） |
| 6 | `getSessionFile()` | `handoff` | `AgentSession.sessionFile()` | 新增（必须） |
| 7 | `getCwd()` | 零命中 | `AgentSession.cwd()` | 新增（直接委托） |
| 8 | `getSessionId()` | 零命中 | `AgentSession.sessionId()` | 新增（直接委托） |
| 9 | `getLeafId()` | 零命中 | `Session.getLeafId()` | 新增（直接委托） |
| 10 | `getTree()` | 零命中 | `AgentSession.getTree()` | 新增（直接委托） |
| 11 | `getSessionName()` | 零命中（pi 的 manager 与 API 根均有；Java `ExtensionContext.getSessionName()` 已有） | — | **不做**，登记为“根上下文已有” |
| 12 | `getSessionDir()` | 零命中 | 无现成字段（需新装配态） | **不做**（见 §3.3 裁决请求） |
| 13 | `getHeader()` | 零命中 | Java 元数据无 `version` 字段 | **不做**（见 §3.3 裁决请求） |
| 14 | `buildContextEntries()` | 零命中（Java 内部已有 `ContextEntries.contextEntries`） | 内部 API | **不做**（见 §3.3 裁决请求） |
| 15 | `buildSessionProjection()` | 零命中（Java 内部已有 `ContextEntries.projectEntries`） | 内部 API | **不做**（见 §3.3 裁决请求） |

结论：B285 新增 **9 个**只读成员（#2–#10）；#11 归位到根上下文，不重复；#12–#15 作为**建议不做项**提交裁决（§3.3）。若用户要求全 15 成员字面覆盖，则需追加装配态与 2 个值类型，代价见 §3.3。

## 2. B284 设计

### 2.1 新增值类型（`extension/` 包）

```java
/** pi CheckModel 的只读投影：只含示例实际读取的字段。 */
public record ExtensionModelInfo(
        ModelId<?> id, String name, int contextWindow, int maxTokens) {
    public String provider() { return id.provider(); }
    public String modelId() { return id.modelName(); }

    static ExtensionModelInfo from(ModelInfo info)   // 目录模型
    static ExtensionModelInfo from(VirtualModelDefinition def) // 虚拟模型
}

/** pi ContextUsage；tokens/percent 在不可得时为 null。 */
public record ExtensionContextUsage(Integer tokens, int contextWindow, Double percent) {}

/** pi CompactOptions 的同步方言。 */
public record ExtensionCompactOptions(
        String customInstructions,
        Consumer<CompactionResult> onComplete,
        Consumer<RuntimeException> onError) {
    public static ExtensionCompactOptions none();
}

/** pi ModelRegistry.complete 的选项。 */
public record ExtensionCompleteOptions(
        OptionalInt maxTokens,
        Optional<ThinkingLevel> reasoning,
        Optional<CacheRetention> cacheRetention,
        Optional<String> sessionId,
        @Nullable CompactionAbortToken signal) {   // pi AbortSignal 对位
    public static ExtensionCompleteOptions defaults();
    public boolean aborted();
}

/** pi NewSessionOptions 的两字段子集（setup 见 §3.3）。 */
public record ExtensionNewSessionOptions(
        @Nullable String parentSession,
        @Nullable Consumer<ExtensionContext> withSession) {}

/** pi 的 {cancelled} 回执。 */
public record ExtensionNewSessionResult(boolean cancelled) {}
```

`ExtensionModelInfo` 只放 `id/name/contextWindow/maxTokens`：`custom-footer`/`border-status-editor` 读 `provider/id/contextWindow`，`handoff`/`qna`/`summarize` 把模型整体交给 `complete`，`subagent` 只拆 `provider/id` 拼 CLI。不引入 `cost/input/compat` 等零消费者字段。

### 2.2 `ExtensionModelRegistry`（只读门面）

```java
public interface ExtensionModelRegistry {
    Optional<ExtensionModelInfo> find(String provider, String modelId);
    boolean hasConfiguredAuth(ExtensionModelInfo model);
    Message.AssistantMessage complete(
            ExtensionModelInfo model, Context context, ExtensionCompleteOptions options);
}
```

语义逐条：

1. `find`：先查 `VirtualModelRegistry.list()`（虚拟模型优先，与 pi runtime 同一模型表），再查 `ModelCatalog.find(ModelId.of(provider, modelId))`；命中后投影为 `ExtensionModelInfo`。未命中返回 `Optional.empty()`，**不回退到当前模型**。
2. `hasConfiguredAuth`：与 `ExtensionSurfaceRegistries.setModel` 的既有凭证门同源——`virtualModels().isAuthExempt(provider)` 或 `Credentials.resolveCredential(provider)` 非空。不写任何全局默认、不触发登录。
3. `complete`：纯读调用，不追加会话记录、不发会话事件；由 `AgentSession` 宿主的 `ModelCompletion` 缝执行 `StreamFn`，用 §2.6 的收集器把流事件收成一条 `Message.AssistantMessage`；`options.signal` 已中止时返回 `stopReason=aborted` 的同形消息（与 `LlmSummaryGenerator.normalizeAborted` 一致）。
4. **不提供** `findOfType` / `classify`（pi 的 classifier 模型类型在 Java AI 层不存在；W1 的 `jev-router` 已按无分类器回退实现）。登记窄差，见 §3.2。

实现类 `DefaultExtensionModelRegistry` 放 `extension/` 包，从 `DefaultExtensionContext.requireToolControl()` 晚绑定取当前 `AgentSession`，因此 fork/switch 后自动指向新会话。

### 2.3 `ExtensionContext` 新增方法

全部以 `default` 方法加入，保持既有测试假实现可编译；`DefaultExtensionContext` 逐一覆写。未绑定宿主时按既有纪律抛 `IllegalStateException("extension context not bound: <member>")`。

```java
default Optional<ExtensionModelInfo> getModel();
default ExtensionModelRegistry modelRegistry();
default Optional<ExtensionContextUsage> getContextUsage();
default void compact(ExtensionCompactOptions options);
default void reload();
default void shutdown();
default boolean isIdle();
default String getSystemPrompt();
default boolean isProjectTrusted();
default ExtensionNewSessionResult newSession(ExtensionNewSessionOptions options);
```

逐条实现与语义：

| 成员 | Java 实现 | 语义要点 |
|---|---|---|
| `getModel()` | `harness().getModel()` → 虚拟表/目录解析 → `ExtensionModelInfo`；解析不到 `empty` | pi 的 `ctx.model` 是只读属性，Java 用方法名 `getModel()`；无会话抛 |
| `modelRegistry()` | 返回**单例** `DefaultExtensionModelRegistry`（晚绑定到当前会话） | 只读门面，不缓存模型快照 |
| `getContextUsage()` | `getModel()` 空或 `contextWindow <= 0` ⇒ `empty`。否则 `branch = getBranch()`、`entries = ContextEntries.contextEntries(branch)`、`projected = projectEntries(entries)`；若分支含 `Compaction` 且其后没有任何“非 aborted/error、`calculateContextTokens(usage) > 0`”的助手消息 ⇒ `ExtensionContextUsage(null, cw, null)`；否则 `est = estimateProjectedContextTokens(projected, branch)`，`tokens = (int) Math.round(est.tokens())`，`percent = tokens/cw*100` | 逐字对位 `agent-session.ts:4318-4361`：`contextWindow<=0` 返回 undefined；压缩后无新用量时 `tokens/percent` 为 null |
| `compact(opts)` | `owner.compact(opts.customInstructions())`；成功回调 `onComplete`，异常回调 `onError` | Java 压缩为同步；回调在返回前触发。示例只依赖“压缩完成后通知”，可观测结局一致 |
| `reload()` | `owner.reload()` | 与 `ExtensionLoading.reload()` 同源；不重开会话 |
| `shutdown()` | `owner.closeForReplacement("quit", null)` | 幂等：abort 在途 → `session_shutdown{quit}` → 关独占资源；不二次关闭 |
| `isIdle()` | `!owner.isRunning()` | `isRunning` 已含 L5 W1 的启动窗，等价 pi `_isStreaming` 的忙窗 |
| `getSystemPrompt()` | `owner.harness().currentSystemPrompt()`；`null` → `""` | 读当前有效系统提示，不重算 |
| `isProjectTrusted()` | `owner.services().settings().isProjectTrusted()` | 与 MCP/工具面同一信任源 |
| `newSession(opts)` | 见 §2.4 | 返回 `{cancelled}`；`withSession` 收到替换后的上下文 |

### 2.4 `newSession` 的宿主缝

pi 语义（`agent-session-runtime.ts` 的 `newSession`）：

1. 发 `session_before_switch{reason:"new", targetSessionFile:null}`；取消 ⇒ 返回 `{cancelled:true}`，不建会话。
2. 用 `parentSession` 建新会话（写 header 的 parent，不复制消息）。
3. 拆解旧会话（abort → `session_shutdown{new, 新 id}` → 关独占资源）。
4. 新会话接管；若给了 `withSession`，回调收到替换后的上下文。

Java 落点 `SessionDiscoveryFork`，新增：

```java
ExtensionNewSessionResult newSession(ExtensionNewSessionOptions options) {
    if (fireBeforeSwitch("new", null)) {
        return new ExtensionNewSessionResult(true);
    }
    var previousId = owner.sessionId();
    var replacement = AgentSessionFactory.createReplacing(
            owner.sessionArgs(), previousId, options.parentSession());
    closeForReplacement("new", replacement.sessionId());   // 旧侧拆解（幂等口）
    var slash = owner.currentSlashContext();
    slash.onSwitchSession().accept(replacement);           // TUI/RPC/Web 既有换会话路径
    if (options.withSession() != null) {
        options.withSession().accept(replacement.extensionContext());
    }
    return new ExtensionNewSessionResult(false);
}
```

约束：

- `currentSlashContext()` 由 `PromptPipeline.slashContextSupplier` 提供；TUI 已在该供应器里注入 `this::switchSession`（`PiTuiApp.java:413-415`），RPC/Web 各自的会话命令沿用同一 `SlashContext.onSwitchSession` 回调，**不新造第二套替换机制**。
- `withSession` 在 `accept` 之后执行，确保新上下文的 UI 已绑定（`handoff` 的 `setEditorText` 依赖这一点）。
- `setup` 选项不实现：本锚点 `examples/` 零消费，且它要求可写 `SessionManager`，与只读面纪律冲突；登记窄差 §3.2。

### 2.5 宿主的 `ModelCompletion` 缝

`AgentSession` 增包私有字段与 setter，由 `SessionWiring.wireSession` 注入（复用该处已有的 `recordingStreamFn`）：

```java
@FunctionalInterface
interface ModelCompletion {   // core 包
    Message.AssistantMessage complete(ModelId<?> model, Context context,
                                     StreamOptions options, BooleanSupplier aborted);
}
```

`AgentSession.completeModel(...)` 公开一层委托，`DefaultExtensionModelRegistry.complete` 调用它。未注入（`--no-extensions` 之外的手工装配）抛 `IllegalStateException("model completion not bound")`。

`StreamOptions` 由 `ExtensionCompleteOptions` 一对一映射：`maxTokens`、`reasoning`、`cacheRetention`、`sessionId`；`thinkingBudgets` 与 `identity` 缺席（不改变请求身份，符合“不复刻真实网络”的裁决）。

### 2.6 流收集器

新增 `com.pijava.agent.compaction.AssistantResponseCollector`：把 `StreamFn` 的 `StreamIterator` 事件收成单条 `AssistantMessage`，逐事件检查 `BooleanSupplier aborted`，中止时关闭迭代器并返回归一化 aborted 消息。`LlmSummaryGenerator` 的单次调用改为委托该收集器（消除重复的事件循环），既有 compaction 测试即回归证据。

不新增第二个重试策略：`modelRegistry.complete` 只做一次调用，重试语义留在压缩器。

## 3. B285 设计

### 3.1 `ExtensionSessionView` 新增签名

```java
public interface ExtensionSessionView {
    List<Entry> getEntries();            // 已有
    List<Entry> getBranch();             // root → leaf
    Entry getEntry(String id);           // 未命中 null
    String getLabel(String targetId);    // 无标签 null
    Entry getLeafEntry();                // 无叶 null
    String getSessionFile();             // 内存会话 null
    String getCwd();
    String getSessionId();
    String getLeafId();                  // 无叶 null
    List<SessionTreeNode> getTree();
}
```

实现类 `DefaultExtensionSessionView implements ExtensionSessionView` 替换 `DefaultExtensionContext` 里的 lambda，全部经 `requireToolControl()` 晚绑定：

| 成员 | 实现 |
|---|---|
| `getBranch` | `owner.session().findEntriesOnBranch(new EntryQuery(null,null,EntryOrder.OLDEST_FIRST,null,null), BranchBounds.none())`；已核 `walkToRoot` 产出 leaf→root，交由查询按 `OLDEST_FIRST` 反转为 root→leaf |
| `getEntry` | `owner.session().getEntry(id)` |
| `getLabel` | `owner.getLabel(targetId)`（`Session.getLabel` 的一行委托） |
| `getLeafEntry` | `getLeafId() == null ? null : getEntry(getLeafId())` |
| `getSessionFile` | `owner.sessionFile()` |
| `getCwd` | `owner.cwd()` |
| `getSessionId` | `owner.sessionId()` |
| `getLeafId` | `owner.session().getLeafId()` |
| `getTree` | `owner.getTree()` |

### 3.2 窄差登记（实施时写入 `docs/04` 与 `docs/map/04-extensions.md`）

| 编号 | 内容 | 理由 |
|---|---|---|
| D233 | `modelRegistry` 不提供 `findOfType` / `classify` | pi-java AI 层无 classifier 模型类型；`jev-router` 按 W1 已批的 Terra 回退运行 |
| D234 | `newSession` 不提供 `setup` 选项 | 本锚点 examples 零消费；需可写 SessionManager，与只读门面冲突 |

其余方言差异在示例中不可观测，不登记窄差，仅在本稿留痕：`compact` 同步回调；`reload`/`newSession` 在 Java 单一接口上对工具上下文也可见（pi 仅命令上下文），示例不触发。

### 3.3 建议不做项（需用户裁决）

| pi 成员 | 建议 | 理由 | 若要求做的代价 |
|---|---|---|---|
| `getSessionDir()` | 不做 | 本锚点示例零消费；Java 无“有效会话目录”装配态，`sessionFile.parent()` 在 `--session-dir` 平铺/恢复旧会话两臂都不可靠 | 在 `AgentSession` 增加 `sessionDir` 字段并由工厂/恢复路径写入，另加 1 测 |
| `getHeader()` | 不做 | 示例零消费；Java `SessionMetadata` 无 pi header 的 `version` 字段，只能返回缺字段的近似结构 | 新增 `ExtensionSessionHeader` 并从 JSONL 头解析 `version` |
| `buildContextEntries()` | 不做 | 示例零消费；`ContextEntries.contextEntries` 是 agent-core 内部 API，透出会把压缩投影类型变成扩展 ABI | 暴露内部 `ProjectedEntry`/`Entry` 结构并锁定版本 |
| `buildSessionProjection()` | 不做 | 同上；且 pi 的投影还含 thinkingLevel/model，需额外装配 | 新增 `ExtensionSessionProjection` 值类型与投影装配 |

裁决选项：

- **A（推荐）**：B285 只做 §3.1 的 9 项；上表 4 项按“示例零消费者 + 内部 API”不做，在 docs/04 记明“B285 范围”。
- **B**：要求 15 成员字面全覆盖，则本包扩大为 13 项新增 + 4 个值类型/装配点，W2/W3 延后。

### 3.4 `getContextUsage` 的取值边界（逐字对位 `agent-session.ts:4318-4361`）

1. 无模型或 `contextWindow <= 0` ⇒ 整个结果 `Optional.empty()`（pi 返回 `undefined`）。
2. 分支含最新 `Compaction`，且压缩点之后没有任何可信用量（非 aborted/error 且 `calculateContextTokens(usage) > 0` 的助手消息）⇒ `tokens = null`、`percent = null`、`contextWindow` 照给。
3. 有可信用量 ⇒ `tokens` 为整数估算、`percent = tokens / contextWindow * 100`。
4. 分支为空 ⇒ `tokens = 0`、`percent = 0`。

## 4. 示例消费对照（W2/W3 验收面）

| 示例 | 本包成员 | 验收断言（W2/W3 落测） |
|---|---|---|
| `trigger-compact.ts` | `getContextUsage`、`compact` | 压缩前 usage 非空；触发后 `onComplete` 被调用且出现压缩条目 |
| `custom-compaction.ts` | `modelRegistry.find`、`complete` | 找到 `google/gemini-2.5-flash`；complete 收到 `maxTokens=8192`、`cacheRetention=none`、非空摘要 |
| `send-user-message.ts` | `isIdle` | idle 才派发；运行中返回 false |
| `system-prompt-header.ts` | `getSystemPrompt` | 返回当前有效系统提示原文 |
| `handoff.ts` | `getModel`（pi `ctx.model`）、`modelRegistry.complete`、`newSession`、`sessionManager.getBranch/getSessionFile` | 取消 ⇒ `cancelled=true`；成功 ⇒ 新会话 header parent 正确、`withSession` 收到新上下文 |
| `reload-runtime.ts` | `reload` | 命令路径重装扩展；工具路径不在本包测 |
| `shutdown-command.ts`（W7） | `shutdown` | `session_shutdown{quit}` 一次且资源关闭 |
| `subagent/index.ts`（W4） | `isProjectTrusted` | 信任态与 settings 一致 |
| `bookmark.ts` | `sessionManager.getEntries/getLabel` | 标签读取与条目定位 |
| `git-checkpoint.ts` | `sessionManager.getLeafEntry` | 无叶时 null、有叶时等于叶 id |
| `qna.ts`/`summarize.ts` | `getModel`（qna）、`modelRegistry.find/complete/hasConfiguredAuth`、`getBranch` | 模型存在/鉴权门/分支消息序 |

## 5. 测试计划

新增两个 coding-agent 测试类（夹具沿用 `AgentSessionExtensionUiTest` 的 faux/内存会话模式）：

1. `ExtensionContextGapTest`
   - `getModel`：物理模型、虚拟模型、未知模型三臂。
   - `modelRegistry.find`：目录/虚拟/未命中；`hasConfiguredAuth`：有凭证/无凭证/虚拟豁免。
   - `complete`：faux 流成功聚合；`signal` 先中止 ⇒ `stopReason=aborted`；未绑定宿主 ⇒ 抛。
   - `getContextUsage`：空分支、有 usage、压缩后、contextWindow=0。
   - `compact`：成功/异常回调；`reload`：调用计数；`shutdown`：`session_shutdown` 一次且幂等。
   - `isIdle`：空闲/运行中（启动窗）；`getSystemPrompt`；`isProjectTrusted`。
   - `newSession`：取消、成功（parent/previous 分离）、`withSession` 上下文晚绑定。
2. `ExtensionSessionViewGapTest`
   - `getBranch` root→leaf 序（含分支切换后的叶路径）。
   - `getEntry/getLabel/getLeafEntry/getLeafId/getSessionFile/getCwd/getSessionId/getTree` 的命中与空值臂。

回归：`LlmSummaryGenerator` 改委托收集器后，`pi-java-agent-core` 的 compaction 既有测试必须保持绿色。

验证命令（单 Maven，禁止并发）：

```powershell
$env:JAVA_HOME='D:\soft\jdk\jdk-25'; $env:MAVEN_OPTS='-Dfile.encoding=UTF-8'
& 'C:\Users\11959\.m2\wrapper\dists\apache-maven-3.9.12-bin\5nmfsn99br87k5d4ajlekdq10k\apache-maven-3.9.12\bin\mvn.cmd' test -pl pi-java-coding-agent -am -q
```

W2/W3 各自再跑 `mvn test -pl pi-java-examples -am`；L8 闭环仍按 `docs/27` 的 `mvn clean verify`。

## 6. 文件清单

新增：

- `pi-java-coding-agent/.../extension/ExtensionModelInfo.java`
- `.../extension/ExtensionModelRegistry.java`
- `.../extension/DefaultExtensionModelRegistry.java`
- `.../extension/ExtensionContextUsage.java`
- `.../extension/ExtensionCompactOptions.java`
- `.../extension/ExtensionCompleteOptions.java`
- `.../extension/ExtensionNewSessionOptions.java`
- `.../extension/ExtensionNewSessionResult.java`
- `.../extension/DefaultExtensionSessionView.java`
- `pi-java-agent-core/.../agent/compaction/AssistantResponseCollector.java`

修改：

- `.../extension/ExtensionContext.java`（10 个 default 方法）
- `.../extension/ExtensionSessionView.java`（9 个方法）
- `.../extension/DefaultExtensionContext.java`（覆写 + 视图换实现 + 注册表单例）
- `.../core/AgentSession.java`（`completeModel`、`currentSlashContext`、`shutdown`、`newSession` 委托）
- `.../core/SessionWiring.java`（注入 `ModelCompletion`）
- `.../core/SessionDiscoveryFork.java`（`newSession` 实现）
- `.../core/PromptPipeline.java`（`currentSlashContext()`）
- `.../agent/compaction/LlmSummaryGenerator.java`（收集器改委托）

不改 `docs/05` 总表；实施完成后按 `docs/04`/`06`/`07`/`map/04` 回填。

## 7. 实施顺序

1. B284a：值类型 + `ExtensionContext` 签名 + `DefaultExtensionContext` 简单只读成员（`getModel`/`isIdle`/`getSystemPrompt`/`isProjectTrusted`）。
2. B284b：`getContextUsage` + `compact` + `reload` + `shutdown`。
3. B284c：`modelRegistry` + `ModelCompletion` + `AssistantResponseCollector`（含 `LlmSummaryGenerator` 回归）。
4. B284d：`newSession`（`SessionDiscoveryFork` + `currentSlashContext`）与取消/晚绑定测试。
5. B285：视图 9 成员 + 测试。
6. 每步跑 coding-agent 测试；B284/B285 全绿后回填 docs/04 销号、docs/06 L8 进度、docs/07 能力清单、map/04 对应行，再开始 W2。

## 8. 用户裁决（2026-11-08）

1. B284：按**实际新增 10 项**记账（`thinkingLevel` 已有）。
2. B285：选 **A**（新增 9 项；`getSessionName` 归根上下文，`getSessionDir`/`getHeader`/`buildContextEntries`/`buildSessionProjection` 不做）。
3. D233（无 `findOfType/classify`）与 D234（无 `setup`）两条窄差均接受。

## 9. 实施记录（2026-11-09）

第 1–5 步已全部落地，无行为偏差：

- 值类型与门面：`ExtensionModelInfo`／`ExtensionContextUsage`／`ExtensionCompactOptions`／`ExtensionCompleteOptions`／`ExtensionNewSessionOptions`／`ExtensionNewSessionResult`／`ExtensionModelRegistry`／`DefaultExtensionModelRegistry`。
- `ExtensionContext` 10 个 default（未绑定错文 `extension context not bound: <member>`）；`DefaultExtensionContext` 覆写并按 FileLength 闸门把实现拆到 `ExtensionHostOperations`；`PerExtensionContext` 逐一透传。
- 宿主缝：`ModelCompletion`＋`AgentSession.completeModel`（`SessionWiring` 用 `recordingStreamFn` 注入）＋`AgentSession.newSession`（实现簇 `ExtensionHostCompletion`，序：`session_before_switch("new", null)` → `createReplacing` → `closeForReplacement` → `SlashContext.onSwitchSession` → `withSession`）。
- 流收集器：`AssistantResponseCollector` 收编 `LlmSummaryGenerator` 的事件循环与终局合成（摘要路 `aborted` 恒 false）。
- `ExtensionSessionView` 9 成员＋`DefaultExtensionSessionView`（无持久面沿转录兜底）。
- 测试：`ExtensionContextGapTest`（4）＋`ExtensionSessionViewGapTest`（2）通过；`pi-java-agent-core` 全量测试通过。
