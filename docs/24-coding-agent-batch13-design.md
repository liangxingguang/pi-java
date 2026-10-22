# coding-agent 批 13 设计稿：cache-warmer 子系统批（E2-14/E2-49＋D 域 #16＋map/05 R24）

【背景摘要】本稿是 coding-agent 对齐批 13 的设计，收 docs/20 §3 序 12a
「cache-warmer 子系统批」（批 12 裁决⑨插行）。pi 的缓存预热是一个**子系统**
而非键接线：`core/cache-warmer.ts` 449 行状态机（定时重放请求保 prompt 缓存
不失效，经济闸门 $0.05）＋`core/cache-stats.ts` 175 行 miss 统计＋sdk.ts
装配＋agent-session 四面接线＋扩展 decide 钩＋TUI 五处通知门。本批全量移植
并接通两个 settings 键（`cacheWarming`／`showCacheMissNotices`，声明面批 11
已落）与 map/05 R24（缓存预热用量行）。

术语（首现释义）：

- **cache warming（缓存预热）**：provider 的 prompt 缓存有 TTL（Anthropic 默认
  5 分钟）；TTL 到期前用一个 `maxTokens=1` 的重放请求刷新缓存项，使下一次真实
  请求仍命中缓存（cache-read 价 ≈ 1/10 输入价）。
- **经济闸门**：仅当「期望节省 ≥ $0.05」才刷新——期望节省＝续聊概率 × miss
  代价 − 刷新成本（pi cache-warmer.ts:378-400）。
- **decide 钩**：扩展事件 `cache_warming_decision`，每次刷新前询问扩展，
  可覆盖 warm/stop 决策（pi runner.ts:1121-1142）。
- **五门**：pi `interactive-mode.ts` 里五处以 `getShowCacheMissNotices()` 为门
  的通知点（:3999/:4110/:4121/:4151/:4185）。
- **核销/窄差/存疑⇒对齐**：同前批（docs/23 §0）。

---

## §0 基线与预期翻动

| 口径 | 批 12 后基线 | 本批预期 | 依据 |
|---|---|---|---|
| 模块（Σ 485，去重） | 311.0（64.1%） | **315.0（64.9%）** | §8 对账表，+4.0 |
| D 域（Σ 83） | 73.0（88.0%） | **75.0（90.4%）** | #16 缺失→对齐（w2，+2.0） |
| E2 域（Σ 84） | 75.0（89.3%） | **76.5（91.1%）** | E2-49（w2，+1.0）＋E2-14（w1，+0.5） |
| 全仓（Σ 1379） | 985.0（71.4%） | **989.0（71.7%）** | 同上 +4.0（含 map/05 R24 w1，+0.5） |

翻动构成（+4.0）：

- map/04 D 域 #16 `cache_warming_decision`：缺失→**对齐**（T3 扩展钩＋T1 本体
  decide 消费，+2.0）。
- map/04 E2-49 `cacheWarming`：存疑→**对齐**（T1/T6/T7/T8 子系统全链＋T9
  设置页行，+1.0）。
- map/04 E2-14 `showCacheMissNotices`：存疑→**对齐**（T10/T11 有载体三门
  落地＋T9 设置页行；两门无载体登窄差 D67，裁决⑥，+0.5）。
- map/05 R24 缓存预热用量行：存疑→**对齐**（T10 文案逐字＋门＋T12 /session
  状态段＋T5 footer 计入，绑定 TUI 行计入模块，+0.5）。

行为变化注记（changelog 级，【模型可见】）：

1. `cacheWarming` 默认 **"streaming" 生效**——此后 Anthropic 系模型在 agent
   运行期间会自动发送 `maxTokens=1` 的缓存刷新请求（每次刷新产生一笔
   cache-read 计费并落 `cache_warm` 用量条目）；此前 java 从不刷新，缓存
   TTL 到期后下一请求全价重写。
2. 会话 JSONL 出现 `{"type":"usage","kind":"cache_warm",...}` 条目（pi 同形，
   可跨读）；footer token 计数包含暖条目。
3. 设置页新增两行：Cache warming（off/streaming/idle）与 Cache miss notices
   （true/false，默认 false）。
4. `showCacheMissNotices=true` 时：聊天区出现缓存 miss 警告行与暖用量行
   （默认 false ⇒ 默认不可见）；`/session` 新增 Cache Warming 状态段
   （pi 同——该段无条件显示，不吃 showCacheMissNotices 门）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`，只走 `git show`）

> 路径均相对 pi 仓根；下省前缀 `packages/coding-agent/src/`。

### 1.1 本体：`core/cache-warmer.ts`（449 行）

常量与纯函数（逐字）：

```ts
// cache-warmer.ts:16-26
const MAX_WARMING_AGE_MS = 60 * 60_000;        // streaming 阶段安全窗
const MAX_IDLE_WARMING_AGE_MS = 30 * 60_000;   // idle 阶段安全窗（估计可靠性更低）
const CACHE_WARMING_MINIMUM_EXPECTED_SAVINGS = 0.05;   // 期望节省下限（美元）
const IDLE_CONTINUATION_PROBABILITY = 0.15;    // idle 期间真实请求先到的概率（实测常量）

// :29-32 刷新时机：90% TTL 且至少留 10 秒余量；TTL ≤ 10s 不 warm
export function getCacheWarmingDelayMs(ttlMs: number): number | undefined {
    if (ttlMs <= 10_000) return undefined;
    return Math.max(1, Math.floor(Math.min(ttlMs * 0.9, ttlMs - 10_000)));
}

// :39-46 缓存寿命：options.cacheRetention ?? PI_CACHE_RETENTION env ?? "short"；
// "none" ⇒ 不 warm；读 model.promptCache 对应档秒数
export function getPromptCacheTtlMs(model: Model<Api>, options: SimpleStreamOptions | undefined): number | undefined {
    const retention =
        options?.cacheRetention ??
        (getProviderEnvValue("PI_CACHE_RETENTION", options?.env) === "long" ? "long" : "short");
    if (retention === "none") return undefined;
    const seconds = model.promptCache?.[retention];
    return seconds === undefined ? undefined : seconds * 1000;
}

// :55-58 可重放性：anthropic-messages 的预算思考（非 adaptive）从 max_tokens 推
// budget_tokens，maxTokens=1 重放会改变缓存键且可能真思考 ⇒ 不可重放
export function isReplayable(model: Model<Api>, options: SimpleStreamOptions | undefined): boolean {
    if (!options?.reasoning || model.api !== "anthropic-messages") return true;
    return (model as Model<"anthropic-messages">).compat?.forceAdaptiveThinking === true;
}
```

经济学（逐字，:378-400；`lastPromptTokens` :61-70 扫分支尾 assistant 的
`input+cacheRead+cacheWrite`；`price` :72-86 走 `calculateCost`）：

```ts
private evaluate(run: ActiveRun): CacheWarmingDecision {
    const model = run.model;
    const promptTokens = lastPromptTokens(this.sessionManager.getBranch());
    const cacheHitCost = price(model, { cacheRead: promptTokens });
    const cacheMissCost = price(
        model,
        model.cost.cacheWrite > 0 ? { cacheWrite: promptTokens } : { input: promptTokens },
    );
    const warmCost = price(model, { cacheRead: promptTokens, output: 1 });
    const missCost = Math.max(0, cacheMissCost - cacheHitCost);
    const continuationProbability = run.phase === "idle" ? IDLE_CONTINUATION_PROBABILITY : 1;
    const economicsAvailable = promptTokens > 0 && (cacheHitCost > 0 || cacheMissCost > 0);
    const expectedSavings = continuationProbability * missCost - warmCost;
    return {
        phase: run.phase, warmCost, missCost, continuationProbability, expectedSavings,
        economicsAvailable,
        action: expectedSavings >= CACHE_WARMING_MINIMUM_EXPECTED_SAVINGS ? "warm" : "stop",
    };
}
```

状态机（:162-401）：单 `run` 槽；`start` 替换旧 run（clearRun：clearTimeout＋
`controller.abort()`）；`schedule` 算 `nextWarmAt`/`refreshDeadlineAt`（预留
一半到期余量给计时器延迟）＋双安全窗检查＋`setTimeout(refresh)`（`.unref()`）；
`refresh`（:300-355）三段校验（进场 validateRun → decide 后再 validateRun＋
deadline → stream 后 validateRun），decide 钩异常回落 pi 决策，action=stop 时
按来源写 reason（`"stopped by extension"`／`"expected savings below threshold"`／
`"cache economics unavailable"`），warm 则 `streamSimple(model, context,
{...options, maxTokens: 1, maxRetries: 0, signal})` 成功后 `appendUsage(
"cache_warm", provider, responseModel ?? model, usage, extensionOverride ?
"extension override" : undefined)`，`onWarmed?.(entry)`，最后 `if (this.run ===
run) this.schedule(run)` 排下一轮；`onAgentSettled`（:245-257）：mode=streaming
⇒ stop("agent run settled")；否则转 idle 相＋30 分钟窗检查；`onModeChanged`
（:260-265）按当前 mode 重算停止理由；`cancel`＝stop("inactive")。

停止理由全集（逐字，状态显示与测试断言依赖）：`"waiting for first request"`／
`"cache warming disabled"`／`"conversation context changed"`／
`"cache economics unavailable"`／`"request cannot be replayed safely"`／
`"request disabled prompt caching"`／`"cache lifetime unavailable"`／
`"agent run settled"`／`"30-minute idle safety limit reached"`／
`"one-hour safety limit reached"`／`"cache refresh deadline missed"`／
`"stopped by extension"`／`"expected savings below threshold"`／`"inactive"`。

格式化（:403-453，/session 行与转录行，逐字见 §3 T1/T10）：

```ts
// :449-453 转录用量行
export function formatCacheWarmingUsage(entry: UsageEntry): string {
    const note = entry.note ? ` (${entry.note})` : "";
    const cost = entry.usage.cost.total.toFixed(6).replace(/(\.\d{3}\d*?)0+$/, "$1");
    return `Cache warmed${note}: $${cost}`;
}
```

### 1.2 miss 统计：`core/cache-stats.ts`（175 行）

- 常量：`CACHE_TTL_MS = 5*60*1000`（:8）；`NOISE_FLOOR_TOKENS = 1024`（:11）。
- `CacheMiss`（:14-23）：missedTokens／missedCost／idleMs／modelChanged。
- `detectMiss`（:56-90）：prev 缺席／promptTokens≤0／零缓存且 prev 未报过缓存
  ⇒ 不计；`missedTokens = min(prev.promptTokens, promptTokens) - cacheRead`，
  ≤1024 不计；missedCost＝missedTokens × max(0, 实付单价 − cache-read 单价)
  （实付单价从本消息 cost 拆账推；read 单价缺省时查 `models.getModel`）。
- `scan`（:104-142）：compaction/branch_summary 复位 prev（模型切换**不**豁免）；
  `usage` 且 `kind==="cache_warm"` 条目充当 prev（reportedCache 恒 true）；
  assistant 消息逐条 detectMiss 并推进 prev（`reportedCache` sticky）。
- 三公开函数：`computeCacheWaste`（:148，/session 用）、`collectCacheMisses`
  （:157，重建臂）、`detectCacheMiss`（:168，即时臂——entries 未含当前消息）。

### 1.3 装配：`core/sdk.ts`

```ts
// sdk.ts:334-339 构造（decide 经 extensionRunnerRef 晚绑定，空 ⇒ 事件自带 action）
const cacheWarmer = new CacheWarmer(
    modelRuntime, sessionManager,
    () => settingsManager.getCacheWarmingMode(),
    async (event) => extensionRunnerRef.current?.emitCacheWarmingDecision(event) ?? event.action,
);

// sdk.ts:369-381 isCurrent：provider/id 相同＋捕获消息数组是当前数组的前缀（逐元素恒等；
// 注释：agent 可能浅拷贝 messages 数组，顶层对象身份不是有效缓存键）
const cacheContextIsCurrent = (requestModel: Model<any>) => {
    const messages = agent.state.messages;
    return () => {
        const currentModel = agent.state.model;
        const currentMessages = agent.state.messages;
        return (
            currentModel.provider === requestModel.provider &&
            currentModel.id === requestModel.id &&
            messages.length <= currentMessages.length &&
            messages.every((message, index) => currentMessages[index] === message)
        );
    };
};

// sdk.ts:427-429 streamFn 内：仅会话请求触发（压缩/摘要用自己的 routing id）
if (options?.sessionId === sessionManager.getSessionId()) {
    cacheWarmer.start({ model, context, options: requestOptions }, cacheContextIsCurrent(model));
}

// sdk.ts:470 随 AgentSession 构造注入
```

注意：暖重放的 options 是**捕获时冻结**的 `requestOptions`（含
transformHeaders 闭包 ⇒ 暖请求**过** `before_provider_headers` 钩与 attribution
头合并）；而 `onPayload/onResponse/onProviderStreamEvent` 是 `new Agent({...})`
的构造参数（Agent 级回调），`models.streamSimple` 直调 ⇒ 暖请求**不过**这三钩。

### 1.4 会话四面：`core/agent-session.ts`

```ts
// :266/:469/:494 配置注入；:495-496 onWarmed ⇒ entry_appended 广播
if (this._cacheWarmer) {
    this._cacheWarmer.onWarmed = (entry) => this._emit({ type: "entry_appended", entry });
}
// :1079 _emitAgentSettled 第一步
this._cacheWarmer?.onAgentSettled();
// :1409-1412 dispose
if (this._cacheWarmer) { this._cacheWarmer.onWarmed = undefined; this._cacheWarmer.cancel(); }
// :1431-1439 状态读口＋写入口（写设置后立即重调和）
get cacheWarmingStatus(): CacheWarmingStatus | undefined { return this._cacheWarmer?.status; }
setCacheWarmingMode(mode: CacheWarmingMode): void {
    this.settingsManager.setCacheWarmingMode(mode);
    this._cacheWarmer?.onModeChanged();
}
```

### 1.5 扩展钩：`core/extensions/`

```ts
// types.ts:1599-1602
on(event: "cache_warming_decision",
   handler: ExtensionHandler<CacheWarmingDecisionEvent, CacheWarmingDecisionEventResult>): () => void;

// runner.ts:1120-1142（last override wins；处理器异常 ⇒ emitError 后继续）
async emitCacheWarmingDecision(event: CacheWarmingDecisionEvent): Promise<CacheWarmingAction> {
    const ctx = this.createContext();
    let action = event.action;
    for (const { ext, handlers } of snapshotEventHandlers(this.extensions, event.type)) {
        for (const handler of handlers) {
            try {
                const result = (await handler(event, ctx)) as CacheWarmingDecisionEventResult | undefined;
                if (result?.action !== undefined) action = result.action;
            } catch (err) { this.emitError({...}); }
        }
    }
    return action;
}
```

事件载荷（cache-warmer.ts:112-120）：`{type, warmCost, missCost,
continuationProbability, action}`；结果 `{action?: "warm"|"stop"}`。

### 1.6 TUI 五门＋设置页＋/session：`modes/interactive/`

```ts
// interactive-mode.ts:3999-4001 重建臂（renderSessionItems）：重推导 miss 并注入
const cacheMisses = this.settingsManager.getShowCacheMissNotices()
    ? collectCacheMisses(this.sessionManager.getEntries(), this.session.modelRuntime)
    : new Map<AssistantMessage, CacheMiss>();
// :4062-4065 注入点（assistant 消息渲染后，aborted/error 不注入）
if (message.stopReason !== "aborted" && message.stopReason !== "error") {
    const miss = cacheMisses.get(message);
    if (miss) this.addCacheMissNotice(miss);
}

// :4110-4115 暖用量行（门＋渲染）
private addCacheWarmingUsage(entry: UsageEntry): void {
    if (!this.settingsManager.getShowCacheMissNotices()) return;
    this.chatContainer.addChild(new Spacer(1));
    const usage = formatCacheWarmingUsage(entry);
    this.chatContainer.addChild(new ThemedText(() => theme.fg("dim", usage), 1, 0));
}

// :4121-4122 压缩成本通知（同门；java 无载体——B38 未建，D67）
// :4150-4151 thinking-drop 通知（同门；java 无 diagnostics 面，D67）

// :4185-4191 即时臂（message_end 处理内 :3593 调用；entries 未含当前消息）
private maybeShowCacheMissNotice(message: AssistantMessage): void {
    if (!this.settingsManager.getShowCacheMissNotices()) return;
    const miss = detectCacheMiss(this.sessionManager.getEntries(), message, this.session.modelRuntime);
    if (miss) this.addCacheMissNotice(miss);
}

// :4193-4206 miss 通知文案（门：missedTokens<20_000 且 missedCost<0.1 ⇒ 不显示）
private addCacheMissNotice(miss: CacheMiss): void {
    if (miss.missedTokens < 20_000 && miss.missedCost < 0.1) return;
    const cost = miss.missedCost >= 0.01 ? ` (~$${miss.missedCost.toFixed(2)})` : "";
    const reBilled = `${formatTokens(miss.missedTokens)} tokens re-billed${cost}`;
    let label = "Cache miss";
    if (miss.modelChanged) { label = "Cache miss after model switch"; }
    else if (miss.idleMs >= CACHE_TTL_MS) { label = `Cache miss after ${Math.round(miss.idleMs / 60_000)}m idle`; }
    this.chatContainer.addChild(new Spacer(1));
    this.chatContainer.addChild(new ThemedText(() => theme.fg("warning", `${label}: ${reBilled}`), 1, 0));
}

// :3444-3451 entry_appended 即时上屏（暖条目）
case "entry_appended":
    ...
    } else if (event.entry.type === "usage" && event.entry.kind === "cache_warm") {
        this.addCacheWarmingUsage(event.entry);
        this.ui.requestRender();
    }
```

`formatTokens`（components/footer.ts:25-31，逐字）：`<1000` 原样；`<10000`
`(n/1000).toFixed(1)+"k"`；`<1M` 整 k；`<10M` 一位小数 M；否则整 M。

设置页（settings-selector.ts:521-527 "Cache warming" 三值循环、:543-548
"Cache miss notices" 布尔）与回调（interactive-mode.ts:5005-5008
`this.session.setCacheWarmingMode(mode)`＋showStatus；:5045-5048
`setShowCacheMissNotices`＋**rebuildChatFromMessages**）。

/session 段（interactive-mode.ts:6739-6746＋6757-6764，逐字见 §3 T12）。

### 1.7 settings 面（批 11 已落声明面，本批零改动）

`settings-manager.ts:914-923` get/setCacheWarmingMode（只读全局域、非法回落
"streaming"）；:943/:965 get/setShowCacheMissNotices（有效域读、全局域写）；
defaults：`cacheWarming: "streaming"`（settings-defaults.ts:60）、
`showCacheMissNotices: false`（:27）。

### 1.8 pi 测试蓝本：`test/cache-warmer.test.ts`（367 行）

7 用例＋1 runner 用例，全部可翻译（§7）：eligibility/timing 矩阵、replay 保
options（reasoning/sessionId/transformHeaders 透传＋maxTokens=1/maxRetries=0
覆盖＋signal 替换）、deadline 两则（计时器延迟＋decide 期间过期）、经济决策
四臂（unprofitable stop／扩展强制 warm＋note／扩展 veto／economics
unavailable）、stop 三因（off/unknown TTL/不可重放/context 变化/mode 变化/
settled）、替换 abort＋失败不记账、格式化两则、runner last-override-wins。

### 1.9 RPC 面取证（零消费）

`cacheWarmingStatus` 全仓仅 agent-session.ts 与 interactive-mode.ts 两处 ⇒
**RPC/web 无 cache-warming 面**，本批不接 RPC。

---

## §2 java 侧现状（取证 2026-10-22）

**已就绪的地基**（逐条核过，文件:行号）：

- settings：`Settings.java:109` 字段＋`SettingsNetworkAccessors.java:47-56`
  get/setCacheWarmingMode（pi :914-923 对位）；`Settings.java:97`＋
  `SettingsUiPrefsAccessors.java:18-26` showCacheMissNotices。
- 条目：`Entry.Usage`（`Entry.java:255-270`）九键逐字对齐 pi UsageEntry，
  javadoc 已注明「唯一 pi 生产者是 cache-warmer」；跨读测试
  `PiV3UsageEntryTest` 在。
- 模型数据：`ModelInfo.pricing()/promptCache()/compat()/api()`（批 3a 数据面），
  `ModelCatalog.find(ModelId)` ⇒ `Optional<ModelInfo>`；`CostCalculator.
  calculateCost`（pi calculateCost 逐行移植）；`PromptCacheLifetimes.
  shortSeconds/longSeconds`；`ModelCompat.forceAdaptiveThinking`。
- 事件面：`AgentSessionEvent.EntryAppended/MessageEnd/AgentSettled` 全在；
  扩展 spec 令牌机制（`ExtensionEventSpec` 30 常量）＋结果收集型 dispatch
  样板（`BoundaryBridge.dispatchTurnEnd:155-190`）；`ProviderEventBridge`
  晚绑定 install 模式。
- 装配缝：`AgentSession` wireSession 内 streamFn 装配（`AgentSession.java:
  426-440`）＋`harnessRef`/`sessionRef` 晚绑定（:452/:560）；harness 与摘要
  生成器是**两次独立注入**（:562 / :573）⇒ 可分包（裁决①）。
- 挂点：`SessionRunner.java:175/:201` AgentSettled 双发射点；`AgentSession.
  closeWith:2011`；`appendBoundaryEntry:1828-1834` 收口样板（追加⇒广播⇒落盘）。
- `StreamIterator.close()`（断连释放）；`AbortSignal` 在 ai 层但**chat 车道
  不消费**（仅 OpenRouterImagesApi 读 extra["signal"]）。
- `ContextEntries.pathToLeaf`（分支序列）；`LaneState.appendDeferredEntry`
  （public、带锁；`HarnessUtils.recordDeferredWrite:118-121` **自带 idle 门**
  ⇒ run 中记 deferred write、idle 纯追加——pi `_appendEntry` 的双态对位）。

**缺口**（本批新建）：

1. CacheWarmer 本体／CacheStats／DTO／格式化——全无。
2. `AgentHarness` 无 messages 访问器（isCurrent 操作数）；无 deferred 追加的
   公开入口（appendLaneEntry 是 idle 限定，`RunLifecycle.java:326-329` 运行中
   抛 ISE）。
3. `DefaultProviders.streamFnFor` 无 maxRetries 强制通道（`apiOptions:352-353`
   从 settings 现读）；三钩与头变换一体调用（`streamBlocking:290-291`，但
   `withRequestHooks`/`applyHeaderTransform` 是**两个独立方法** ⇒ 可分包）。
4. TUI：`ChatMessage.cacheWarmNotice:114-120` 是自有文案（"Cache warming: N
   tokens"）且**无门**；`ChatScreen.onSessionEvent:273` 不消费 EntryAppended/
   MessageEnd；无 cache miss 检测；SettingsScreen 无两行；`/session`
   （`SessionCommands.sessionInfo:108-117`）无 Cache Warming 段。
5. footer：`PiLaneSink:394` 只喂车道内 input+output；暖条目无喂点。

---

## §3 任务分解

> 模块归属：T1–T8＋T13＝pi-java-coding-agent（T4/T5 涉 agent-core 三方法：
> getMessages、appendDeferredLaneEntry、addTokens）；T9–T12＝pi-java-tui
> （T12 的 /session 在 coding-agent slash 包）。

### T1：CacheWarmer 本体（新文件 ×3）

**`core/CacheWarming.java`**（DTO 族，~110 行，【内部实现】）：

```java
package com.pijava.coding.agent.core;

/** pi cache-warmer.ts:88-155 的类型族（密封族单文件，AgentSessionEvent 同格）。 */
public final class CacheWarming {
    private CacheWarming() {}

    /** pi :88 —— 纯常量闭集 ⇒ enum（CLAUDE.md 规范）。 */
    public enum Action {
        WARM, STOP;
        public String wireName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    /** pi :91-106 一次决策的输入与结局（/session 展示面）。 */
    public record Decision(String phase,   // "streaming" | "idle"
        double warmCost, double missCost, double continuationProbability,
        double expectedSavings, boolean economicsAvailable, Action action) {}

    /** pi :122-132 warmer 状态（state/reason/nextWarmAt/decision/extensionOverride）。 */
    public record Status(String state,     // "inactive" | "scheduled" | "refreshing"
        String reason, Long nextWarmAt, Decision decision, Boolean extensionOverride) {}

    /** pi :135-139 待保温的请求（原样重放）。 */
    public record WarmRequest(com.pijava.ai.model.ModelId<?> model,
        com.pijava.agent.harness.Context context,
        com.pijava.agent.harness.StreamOptions options) {}
}
```

**`core/CacheWarmer.java`**（状态机，~400 行）。逐字段落对位 pi :16-26（四
常量）、:29-58（三纯函数）、:245-298（start/onAgentSettled/onModeChanged/
cancel/clearRun/stop/schedule）、:300-376（refresh/deadline/validate/
modeStopReason）、:378-400（evaluate）。关键 java 化裁决：

```java
public final class CacheWarmer {
    static final long MAX_WARMING_AGE_MS = 60 * 60_000L;            // pi :16
    static final long MAX_IDLE_WARMING_AGE_MS = 30 * 60_000L;       // pi :18
    static final double MINIMUM_EXPECTED_SAVINGS = 0.05;            // pi :20
    static final double IDLE_CONTINUATION_PROBABILITY = 0.15;       // pi :26

    /** pi :29-32 逐字。 */
    public static java.lang.Long getCacheWarmingDelayMs(long ttlMs) {
        if (ttlMs <= 10_000) return null;
        return Math.max(1, (long) Math.floor(Math.min(ttlMs * 0.9, ttlMs - 10_000)));
    }

    /** pi :39-46：retention 链＝options.cacheRetention ?? PI_CACHE_RETENTION env
     *  ?? "short"（java StreamOptions 无 env 键——车道 retentionOf 同读系统 env，
     *  同源）；promptCache 档秒数经 ModelInfo。 */
    public static java.lang.Long getPromptCacheTtlMs(
            com.pijava.ai.catalog.ModelInfo model, StreamOptions options) { ... }

    /** pi :55-58：reasoning 缺席 ⇒ true；api "anthropic-messages" 且
     *  compat.forceAdaptiveThinking ⇒ true；否则 false。 */
    public static boolean isReplayable(
            com.pijava.ai.catalog.ModelInfo model, StreamOptions options) { ... }
}
```

依赖注入（pi :162-184 构造四参＋onWarmed 的 java 形）：

```java
public interface WarmStreamFn {   // pi ModelRuntime.streamSimple 的 java 形
    /** 阻塞消费到终局；返回终局 AssistantMessage（stopReason 见 StopReason）。 */
    com.pijava.ai.message.Message.AssistantMessage streamToFinal(
        com.pijava.ai.model.ModelId<?> model,
        com.pijava.agent.harness.Context context,
        com.pijava.agent.harness.StreamOptions options,
        WarmAbort abort);
}
/** pi AbortController 的 java 形（裁决②）：持有 in-flight StreamIterator，close 断连。 */
public interface WarmAbort { void abort(); }

public interface SessionAccess {  // pi Pick<SessionManager,"appendUsage"|"getBranch">
    com.pijava.agent.entry.Entry.Usage appendUsage(String kind, String provider,
        String model, com.pijava.ai.Usage usage, String note);
    java.util.List<com.pijava.agent.entry.Entry> getBranch();
}

CacheWarmer(WarmStreamFn warmStream, SessionAccess sessionAccess,
    java.util.function.Supplier<String> getMode,          // "off"|"streaming"|"idle"
    DecideHook decide,                                     // 见 T3；异常回落内建
    WarmScheduler scheduler, java.util.function.LongSupplier nowMs,
    java.util.function.Function<com.pijava.ai.model.ModelId<?>,
        java.util.Optional<com.pijava.ai.catalog.ModelInfo>> modelInfo)
```

- **调度 seam（裁决④）**：`WarmScheduler.schedule(Runnable, long delayMs) ⇒
  Cancellable`。生产实现＝单线程 `ScheduledExecutorService`（守护线程），触发后
  refresh 本体在**虚拟线程**跑（阻塞拉取不占地调度线程）；测试实现＝手动队列
  ＋手动时钟（翻译 pi `vi.useFakeTimers`）。
- **并发纪律**：pi 单线程；java 的 start/onAgentSettled/onModeChanged/cancel 与
  refresh 并发 ⇒ `run` 槽与状态迁移走 `synchronized` 小块，阻塞段（decide/
  stream）在锁外、前后各做一次 `validateRun`（pi :302/:318/:340 三段校验逐字）。
- **isCurrent 竞态注记**：pi 的比较在调用点现算；java 同（BooleanSupplier
  在锁外现读 harness 活视图，见 T4/T6）。
- **onWarmed 不移植（裁决⑧）**：pi 的 onWarmed 唯一消费者是 entry_appended
  广播；java 的 appendUsage 收口（T5）内建广播。

**`core/CacheWarmingFormatting.java`**（~140 行）：`formatStatus`（pi :433-446
逐字——含 `formatEconomics` :407-416／`formatDecisionTime` :418-430／
`formatDollars` :403-405 三私有助手）、`formatUsage`（pi :449-453 逐字，操作数
`Entry.Usage`）、`formatTokens`（pi footer.ts:25-31 逐字——miss 通知与
/session 共用）。金额格式：pi `toFixed(6)` 去尾零（保留 ≥3 位小数）⇒ java
`String.format(Locale.ROOT, "%.6f", v)` 后正则去尾零（逐字 `(\.\d{3}\d*?)0+$
⇒ $1`）。

### T2：CacheStats（新文件 `core/CacheStats.java`，~190 行）

pi cache-stats.ts 全量逐字（§1.2）。java 形：

```java
public final class CacheStats {
    public static final long CACHE_TTL_MS = 5 * 60 * 1000L;      // pi :8
    static final long NOISE_FLOOR_TOKENS = 1024;                 // pi :11

    /** pi :14-23。 */ public record CacheMiss(long missedTokens,
        double missedCost, long idleMs, boolean modelChanged) {}
    /** pi :25-30。 */ public record CacheWasteTotals(long missedTokens,
        double missedCost, int missCount) {}

    /** pi computeCacheWaste :148-150。 */
    public static CacheWasteTotals computeCacheWaste(List<Entry> entries,
        Function<ModelKey, Optional<ModelInfo>> models) { ... }
    /** pi collectCacheMisses :157-162 —— java 不用 Map 键身份（见下），
     *  返回 List<Map.Entry<Entry.Message, CacheMiss>> 保序。 */
    public static List<Map.Entry<com.pijava.agent.entry.Entry.Message, CacheMiss>>
        collectCacheMisses(...) { ... }
    /** pi detectCacheMiss :168-174。 */
    public static CacheMiss detectCacheMiss(List<Entry> entries,
        Message.AssistantMessage message, ...) { ... }
    /** pi scan :104-142 的单条增量形（T11 tracker 复用同一状态机）。 */
    static ... 
}
```

- `ModelPriceSource`（pi :33-35）⇒ `Function<ModelKey, Optional<ModelInfo>>`
  （ModelKey＝provider+"/"+modelName 字符串；装配点给 `catalog.find`）。
- prev 的 `timestamp`：pi 用 message.timestamp（epoch ms 数值）；java
  `Message.AssistantMessage.timestamp()` 同型 ✓；usage 条目臂 `Date.parse(
  entry.timestamp)` ⇒ `Entry.Usage.timestamp()`（Instant）⇒ epoch ms。
- `reportedCache` sticky 语义逐字（pi :100、:138）。

### T3：扩展钩 `cache_warming_decision`（D 域 #16，w2）

三处 ADT 增量（机制零改签，spec 令牌设计即为此）：

```java
// ExtensionEvent.java（pi cache-warmer.ts:112-115 四键；type 由 spec 承载）
public record CacheWarmingDecisionEvent(double warmCost, double missCost,
    double continuationProbability, CacheWarming.Action action) implements ExtensionEvent {}

// ExtensionResults.java（pi :117-120）
public record CacheWarmingDecisionEventResult(
    CacheWarming.Action action) implements ExtensionResults {}

// ExtensionEventSpec.java（pi types.ts:1599-1602）
public static final ExtensionEventSpec<CacheWarmingDecisionEvent,
    CacheWarmingDecisionEventResult> CACHE_WARMING_DECISION =
    new ExtensionEventSpec<>("cache_warming_decision");
```

**`extension/bridge/CacheWarmingBridge.java`**（~90 行，裁决⑦）：晚绑定
`install(bus, ctx)`（ProviderEventBridge :48-51 同款）；`emitCacheWarmingDecision`
逐字 pi runner.ts:1121-1142——`bus.snapshot(CACHE_WARMING_DECISION)` 逐处理器，
`result.action() != null` ⇒ 覆盖（last wins），`catch (Throwable)` ⇒
`bus.emitError(h.error(e))` 后继续；未安装/无订阅 ⇒ 返回事件自带 action（pi
`sdk.ts:338` 的 `?? event.action` 臂）。CacheWarmer 的 `DecideHook` 由装配点
给：`event -> bridge.emitCacheWarmingDecision(...)`，桥内异常回落 pi 决策
（pi cache-warmer.ts:315-317 的 try/catch 在 warmer 里也有一层——两层同保留，
逐字）。

### T4：AgentHarness 两方法（agent-core）

```java
/**
 * pi agent.state.messages 的读口（批 13，sdk.ts:370-377 对位）：lane 工作副本
 * 的<b>活视图</b>——元素身份随 run 推进稳定（前缀恒等比较的操作数），
 * 修改抛 UOE。
 */
public List<com.pijava.ai.message.Message> getMessages() {
    return java.util.Collections.unmodifiableList(lane.messages);
}
// 竞态注记：lane.messages 由引擎线程支改、warmer 调度线程读 ⇒ 可能读到
// 追加中间态。后果方向安全：isCurrent 误判 false ⇒ 提前停 warm（pi 的
// isCurrent 本就是 best-effort 闸门）；不会误判 true（同位元素一旦恒等，
// 引擎只追加不替换前缀——replaceTranscript/compact 走整表替换，视图随之
// 整体失效，下一校验即停）。

/**
 * pi SessionManager._appendEntry 的双态对位（批 13）：委托
 * {@link LaneState#appendDeferredEntry}——run 中记 deferred write（与
 * PiLaneSink 的 assistant usage 条目同生命周期），idle 纯追加
 * （recordDeferredWrite 自带 idle 门，HarnessUtils:118-121）。带锁。
 * 不重建 working messages（usage/custom 类条目不参与投影）。
 */
public <E extends Entry> E appendDeferredLaneEntry(
        String laneName, LaneState.EntryFactory<E> factory) {
    return ctx.requireLane(laneName).appendDeferredEntry(factory);
}
```

（`lane`/`ctx` 字段名以实现为准；`LaneState.EntryFactory` 已 public。）

### T5：会话收口 `appendUsage`＋footer 计入（R24 尾巴）

```java
// AgentSession.java（pi session-manager.ts:1244-1258 对位；收口样板照 :1828-1834）
/**
 * pi appendUsage：模型归因用量条目（不进 LLM 上下文）——追加 ⇒ EntryAppended
 * 广播 ⇒ 落盘。生产者：CacheWarmer（kind="cache_warm"）。footer 计入
 * （pi getSessionStats :4192-4193 的 usage 条目臂对位）。
 */
public Entry.Usage appendUsage(String kind, String provider, String model,
        com.pijava.ai.Usage usage, String note) {
    var entry = harness.appendDeferredLaneEntry(laneName(),
        (seq, parentId) -> new Entry.Usage(
            java.util.UUID.randomUUID().toString(), seq, parentId,
            java.time.Instant.now(), kind, provider, model, usage, note,
            null, null, null, null, null));     // 审计五键空（非车道产出）
    emitSessionEvent(new AgentSessionEvent.EntryAppended(entry));
    if (session != null) {
        SessionPersistence.persistPending(this, session, laneName());
    }
    harness.addTokens((long) usage.totalTokens());   // footer（AgentHarness +addTokens）
    return entry;
}
```

`AgentHarness` 顺手加 `public void addTokens(long tokens)`（委托
ExecutionContext.TokenCounter，与 PiLaneSink:394 同一计数器）。

### T6：装配（AgentSession wireSession 内）

位置：`AgentSession.java:428-440` 段后、`AgentHarness.create` 前（warmer 先于
harness 构造；isCurrent/sessionAccess 经 harnessRef/sessionRef 晚绑定——首
请求必晚于 create 返回）。裁决①（替代 pi sdk.ts:427 的 sessionId 比较——
java 的 `StreamOptions.sessionId` 恒空、亲和 id 由宿主层 `requestExtra`
统一注入，门无法逐字抄）：

```java
// pi sdk.ts:334-339 对位
var cacheWarmingBridge = new CacheWarmingBridge();     // loadExtensions 处 install
                                                     // （providerBridge.install 同点）
var cacheWarmer = new CacheWarmer(
    DefaultProviders.warmStreamFnFor(args, effective.defaultProvider, providers,
        effective, args.sessionId(), providerBridge),              // T7
    new CacheWarmer.SessionAccess() {
        public Entry.Usage appendUsage(...) {                      // T5 收口
            var s = sessionRef.get();
            return s == null ? null : s.appendUsage(kind, provider, model, usage, note);
        }
        public List<Entry> getBranch() {                           // pi getBranch
            var s = sessionRef.get();
            return s == null ? List.of()
                : ContextEntries.pathToLeaf(s.accumulatedEntries(), s.currentLeafId());
        }
    },
    () -> settings.network().getCacheWarmingMode(),     // SettingsNetworkAccessors:47
    cacheWarmingBridge::emitOrDefault,
    WarmScheduler.virtualThreadDaemon(), System::currentTimeMillis, catalog::find);

// 裁决①：只包 harness 注入点；LlmSummaryGenerator（:573）保持 recordingStreamFn
// ⇒ 压缩/摘要不触发 start（pi「仅会话请求」门的 java 形）
var warmingStreamFn = (StreamFn) (model, context, options) -> {
    cacheWarmer.start(new CacheWarming.WarmRequest(model, context, options),
        cacheContextIsCurrent(model));
    return recordingStreamFn.stream(model, context, options);
};
// HarnessConfig.builder().streamFn(warmingStreamFn)…（:562 处换入）

// pi sdk.ts:369-381 逐字对位（harnessRef 晚绑定）
private java.util.function.BooleanSupplier cacheContextIsCurrent(ModelId<?> requestModel) {
    var h = harnessRef.get();
    var messages = h == null ? List.<com.pijava.ai.message.Message>of() : h.getMessages();
    return () -> {
        var harness = harnessRef.get();
        if (harness == null) return false;
        var currentModel = harness.getModel();
        var currentMessages = harness.getMessages();
        if (currentModel == null) return false;
        if (!currentModel.provider().equals(requestModel.provider)
                || !currentModel.modelName().equals(requestModel.modelName())) return false;
        if (messages.size() > currentMessages.size()) return false;
        for (int i = 0; i < messages.size(); i++) {
            if (currentMessages.get(i) != messages.get(i)) return false;   // 逐元素恒等
        }
        return true;
    };
}
```

- 捕获 `messages`＝**活视图**（T4）⇒ 与 pi 捕获数组引用同形（pi 注释：agent
  浅拷贝数组时顶层身份无效，逐元素恒等才有效——java lane.messages 就地
  支改，活视图等价）。
- `currentLeafId()`：AgentSession 加包私有读口（`harness.snapshot(laneName())
  .leafId()`，已有机制）。
- fork 臂：本段在 wireSession 公共路径 ⇒ fork 自动重装（pi 每会话新建
  CacheWarmer 同），旧 warmer 随 closeWith cancel（T8）。

### T7：暖路径请求面（DefaultProviders）

```java
/**
 * 批 13（pi cache-warmer.ts:332-338 对位）：暖重放专用 StreamFn——
 * ① maxRetries 强制 0（pi 展开覆盖 `{...options, maxRetries: 0}`；
 *   java 的 maxRetries 在 apiOptions 从 settings 现读，需强制通道）；
 * ② 跳过 ProviderHooks 三钩（onPayload/onResponse/onStreamEvent 在 pi 是
 *   Agent 级回调，models.streamSimple 直调不过）；
 * ③ 保留 transformHeaders（pi 暖请求 options 含 buildRequestOptions 烘焙的
 *   头变换闭包 ⇒ before_provider_headers 钩与头合并照走）。
 */
public static StreamFn warmStreamFnFor(Args args, String defaultProvider,
        ProviderRegistry providers, Settings settings, String sessionId,
        ProviderEventBridge bridge) {
    var fallbackName = resolveProviderName(args, defaultProvider);
    return (model, context, options) -> {
        var provider = providers.get(model.provider()).orElseGet(() -> { ...同上... });
        var apiOptions = apiOptions(args, model.provider(), settings,
            Credentials::resolveCredential, requestExtra(options, sessionId));
        apiOptions = new ApiOptions(apiOptions.baseUrl(), apiOptions.apiKey(),
            apiOptions.timeout(), 0, apiOptions.extra(), apiOptions.authKind());  // ①
        return streamBlockingWarm(provider, model, context, options, apiOptions, bridge);
    };
}
```

`streamBlockingWarm`＝`streamBlocking` 的暖变体（或加 boolean 形参）：
`effectiveOptions = bridge == null ? effectiveOptions
: bridge.applyHeaderTransform(effectiveOptions);`——**不调**
`withRequestHooks`（②③）。`StreamOptions` 覆盖 `maxTokens=1` 在 warmer 侧
（`options.withMaxTokens(1)`——StreamOptions 加 wither 或 warmer 构造新
record，六参构造逐字段替换）。

`WarmStreamFn.streamToFinal` 实现（AgentSession 内私有）：调 warm StreamFn ⇒
try-with-resources 包 StreamIterator（abort 句柄持有它，close 即断连，
裁决②）⇒ 逐事件消费，取终局 `StreamEvent.MessageEnd` 的 AssistantMessage
（错误编码 `StreamError` ⇒ 返回 stopReason=ERROR 的空终局消息——pi 的
catch 全吞对位， warmer 侧不记账）。

### T8：会话四面接线

```java
// ① settled（pi agent-session.ts:1079 对位）——SessionRunner.java:175 与 :201
//    两处 emitSessionEvent(AgentSettled) 之前：
owner.notifyCacheWarmerSettled();   // 包私有：cacheWarmer == null ? noop
                                    //          : cacheWarmer.onAgentSettled()

// ② dispose（pi :1409-1412 对位）——closeWith（:2011）abort() 之后：
if (cacheWarmer != null) { cacheWarmer.cancel(); }   // onWarmed 无（裁决⑧）

// ③ 写入口（pi :1436-1439 对位）——T9 设置页走这里，不走裸 accessor：
public void setCacheWarmingMode(String mode) {
    services.settings().network().setCacheWarmingMode(mode);   // 批 11 已有写口
    if (cacheWarmer != null) { cacheWarmer.onModeChanged(); }
}

// ④ 状态读口（pi :1431-1433 对位）——T12 /session 消费：
public CacheWarming.Status cacheWarmingStatus() {
    return cacheWarmer == null ? null : cacheWarmer.status();
}
```

### T9：SettingsScreen 两行（E2-49/E2-14 的设置页消费点）

```java
// pi settings-selector.ts:521-527 对位（setter 走会话入口，pi :5005-5008）；
// 注意 getCacheWarmingMode 在 network() 面（批 11 裁决⑧三访问器拆分），
// buildFields 需加 var network = session.services().settings().network();
fields.add(new SettingField("Cache warming", List.of("off", "streaming", "idle"),
    network::getCacheWarmingMode,
    v -> session.setCacheWarmingMode(v)));              // T8-③（含 onModeChanged）

// pi :543-548 对位（改动后 rebuild，pi :5045-5048——走批 12 T14 检出模式：
// cacheMissNoticesToggled 置位，PiTuiApp 检出后
// chatScreen.rebuildTranscript(session.accumulatedEntries(), leafId)）
fields.add(new SettingField("Cache miss notices", List.of("false", "true"),
    () -> String.valueOf(uiPrefs.getShowCacheMissNotices()),
    v -> { uiPrefs.setShowCacheMissNotices(Boolean.parseBoolean(v));
           cacheMissNoticesToggled = true; }));
```

行位置：pi 的顺序是 cache-warming 在 http-idle-timeout 之后、cache-miss 在
mermaid 之后；java 无此两行邻位 ⇒ 追加在 "Warn Anthropic extra usage" 之后、
子菜单行之前（顺序差属设置页布局，不登窄差——pi 设置页行序本就与 java
不同源）。

### T10：暖用量行——文案逐字＋门＋即时臂（R24 主体）

`ChatMessage.cacheWarmNotice`（:114-120）改 pi 逐字：

```java
// pi cache-warmer.ts:449-453（经 CacheWarmingFormatting.formatUsage）＋
// pi interactive-mode.ts:3401/:4030 的 kind 判据（既有，不动）
private static ChatMessage cacheWarmNotice(Entry.Usage usage) {
    if (!"cache_warm".equals(usage.kind()) || usage.usage() == null) {
        return null;
    }
    return new System(CacheWarmingFormatting.formatUsage(usage), MetaKind.GENERIC);
}
// "Cache warmed: $0.029497" ／ "Cache warmed (extension override): $0.029497"
```

**门**（pi :4111）放 ChatScreen 渲染侧（from() 是纯投影，批 12 T6 的 pad
参数同构——不扩 from 签名，门控在调用点）：

```java
// ChatScreen：三处调用点收口一个私有助手
private void appendUsageNotice(Entry.Usage usage) {
    if (!showCacheMissNotices.getAsBoolean()) return;      // pi :4111（晚绑定现读）
    var bubble = ChatMessage.from(usage, outputPad.getAsInt());
    if (bubble != null) chatPanel.append(bubble);
}
```

- `onEntry`：`entry instanceof Entry.Usage` ⇒ 走 appendUsageNotice（替代现有
  直渲路径），并查 `liveUsageNoticeIds` 去重（见下）。
- `rebuildTranscript`（:382-399）：usage 条目同走 appendUsageNotice。
- **即时臂**（pi :3449-3451 对位）——`onSessionEvent` 新增：

```java
case AgentSessionEvent.EntryAppended appended -> {
    if (appended.entry() instanceof Entry.Usage u && "cache_warm".equals(u.kind())) {
        appendUsageNotice(u);
        liveUsageNoticeIds.add(u.id());   // run 结束 onEntry 投递时跳过（pi 无此
    }                                     // 问题——pi 重建式渲染；java 是增量追加）
}
```

`showCacheMissNotices` 供应：ChatScreen 现无 settings 通路 ⇒ 构造后
`setShowCacheMissNoticesSupplier(BooleanSupplier)`（PiTuiLauncher 装配处给
`session.services().settings().uiPrefs()::getShowCacheMissNotices`——晚绑定
现读，pi 每次渲染现查同）。

### T11：cache miss 通知（E2-14 三门之二：重建臂＋即时臂）

**`tui/util/CacheMissTracker.java`**（~120 行，裁决⑤）：CacheStats.scan 的
增量形——`feed(Entry)` 逐条推进 prev 状态（compaction/branch_summary 复位；
cache_warm 条目充 prev；assistant 消息判 miss 并推进），产出
`Optional<CacheMiss>`；`detectOnMessageEnd(AssistantMessage)`＝对未落盘消息
用当前 prev 判一次（pi detectCacheMiss :168-174 的「entries 未含 message」
语义天然成立——tracker 只见过已投递条目）。检测本体委托 T2 的 CacheStats
（公开单条判定 `detectMiss(prev, message, models)` 包内静态 ⇒ tracker 复用，
**不另写一份判定逻辑**）。

接线（ChatScreen）：

```java
// 即时臂（pi :4185-4191＋:3593 挂点对位）
case AgentSessionEvent.MessageEnd end -> {
    if (end.message() instanceof Message.AssistantMessage am
            && am.stopReason() != StopReason.ABORTED && am.stopReason() != StopReason.ERROR) {
        var miss = cacheMissTracker.detectOnMessageEnd(am);
        if (miss.isPresent() && showCacheMissNotices.getAsBoolean()) {
            appendCacheMissNotice(miss.get());
            lastMissNotifiedMessage = am;             // onEntry 同实例去重
        }
    }
}

// onEntry/rebuildTranscript 条目流：先 feed tracker 再渲染（assistant 气泡
// 被 streaming 去重跳过也要 feed——条目客观存在）；feed 产出 miss 且
// message != lastMissNotifiedMessage 且非 aborted/error ⇒ appendCacheMissNotice
// rebuildTranscript 开头 tracker.reset()
```

通知文案（pi :4193-4206 逐字；`appendCacheMissNotice` 私有助手）：

```java
if (miss.missedTokens() < 20_000 && miss.missedCost() < 0.1) return;
var cost = miss.missedCost() >= 0.01
    ? " (~$" + String.format(Locale.ROOT, "%.2f", miss.missedCost()) + ")" : "";
var reBilled = CacheWarmingFormatting.formatTokens(miss.missedTokens())
    + " tokens re-billed" + cost;
String label = "Cache miss";
if (miss.modelChanged()) label = "Cache miss after model switch";
else if (miss.idleMs() >= CacheStats.CACHE_TTL_MS)
    label = "Cache miss after " + Math.round(miss.idleMs() / 60_000.0) + "m idle";
chatPanel.append(new ChatMessage.System(label + ": " + reBilled, MetaKind.GENERIC));
```

（pi 用 warning 主题色＋前置 Spacer；java System 气泡无主题/Spacer 面——与
既有 ChatMessage.System 通知同格，风格差随 R 域既有口径，不新登。）

### T12：/session Cache Warming 段（R24 尾巴）

`SessionCommands.sessionInfo`（:108-117）追加（pi :6739-6746＋6757-6764；
java 无主题色面，纯文本同既有 4 行风格）：

```java
// pi :6739-6746 逐字文案
builder.append("\nCache Warming\n");
builder.append("Mode: ").append(
    session.services().settings().network().getCacheWarmingMode()).append('\n');
var status = session.cacheWarmingStatus();
builder.append("Status: ").append(status != null
    ? CacheWarmingFormatting.formatStatus(status)
    : "Inactive (cache warming unavailable)").append('\n');
if (status != null && status.decision() != null
        && status.decision().economicsAvailable()) {
    builder.append("Cache miss penalty: $").append(fmt3(status.decision().missCost()));
    builder.append("Refresh cost: $").append(fmt3(status.decision().warmCost()));
}
// pi :6757-6764 Cache Re-billed 行（computeCacheWaste 消费点；java 无 Cost 块，
// 挂 Cache Warming 段尾——结构差见 D68）
var waste = CacheStats.computeCacheWaste(session.accumulatedEntries(), catalogKey -> ...);
if (waste.missedTokens() > 0) { ... "Cache Re-billed: ..." }
```

（`formatStatus` 的 `now` 参数：pi :433 默认 `Date.now()` ⇒ java 同；
/models 键函数与 T2 同来源。）

### T13：测试基建——WarmScheduler＋手动时钟

```
core/WarmScheduler.java（接口＋生产实现，~60 行）
test 侧 ManualWarmScheduler（手动 advance(ms) 触发到期任务，同步跑）
```

---

## §4 不做项与重排（docs/20 §3 联动）

- **RPC/web 面无**（§1.9 取证）——不接。
- **E2-14 五门之二无载体**：压缩成本通知（pi :4121，B38 压缩聊天区重建未建）
  与 thinking-drop 通知（pi :4151，java 无 diagnostics 数据面）——登 D67，
  随各自载体批次收，不阻断 E2-14 翻对齐（裁决⑥）。
- **批 7 既有差不动**：java 的三钩对所有过 recordingStreamFn 的请求（含压缩
  摘要）生效（pi 仅 Agent 级会话请求）——批 7（docs/17）已裁，本批暖路径按
  pi 跳过三钩，不回溯既有面。
- **markdown 激活（R1）**、**`-r` 选择器**（序 13）等 docs/20 既定行不动。

## §5 窄差登记（接续 D64；台账格式【场景/决定/风险】随闭环入 docs/04）

- **D65（warm 中止通道）**【场景】pi 用 AbortController 中断暖重放的 fetch
  （cache-warmer.ts:149/:276）；java 的 StreamFn 契约无 signal（chat 车道不
  消费 extra["signal"]）。【决定】abort＝关闭 in-flight StreamIterator
  （close() 断连释放，StreamIterator 契约保证）。【风险】close 后 socket 的
  残余帧由 HTTP 层兜底； observable 行为（暖请求被打断、不记账）与 pi 一致。
- **D66（暖重放选项现读）**【场景】pi 暖重放冻结捕获时刻的 requestOptions
  （timeout/maxRetryDelayMs/headers 合并值）；java 暖路径 streamFn 每请求现读
  settings 重建 apiOptions。【决定】维持 java 现读（与主路径同源，避免第二条
  设置快照链）。【风险】用户在 warm 排期内改 timeout/retry 设置时，pi 用旧值
  java 用新值——方向更安全（新值生效更快），无功能缺。
- **D67（E2-14 两门无载体）**【场景】pi 五门之二：压缩成本通知（:4121）与
  thinking-drop 通知（:4151）依赖 java 未建的载体（B38 压缩重建／diagnostics
  面）。【决定】有载体三门（:3999/:4110/:4185）本批落地即翻 E2-14 对齐
  （裁决⑥）；两门随载体批次收。【风险】无——键的门语义已通，缺的只是两条
  通知种类。
- **D68（/session 结构差）**【场景】pi 的 Cache Re-billed 行挂在 Cost 块内
  （:6748-6764）；java /session 无 Cost/token 统计块（仅 4 行基本信息）。
  【决定】Cache Re-billed 行挂 Cache Warming 段尾。【风险】纯展示结构差；
  /session 全量对齐（stats 块）是独立题，不扩本批。

## §6 裁决

1. **裁决①（「仅会话请求」门＝装配点分包）**：pi sdk.ts:427 用
   `options.sessionId === sessionManager.getSessionId()` 判会话请求；java 的
   `StreamOptions.sessionId` 在车道请求恒空（亲和 id 由宿主层 requestExtra
   统一注入）。【决定】门改成装配形状：warming wrapper 只包 harness 注入点
   （`AgentSession.java:562`），摘要生成器（:573）保持原 recordingStreamFn
   ⇒ 压缩/摘要天然不触发 start。行为与 pi 逐字等价（pi 注释：「compaction
   and summaries use their own routing ids; only session requests replace the
   cache entry」）。
2. **裁决②（abort＝iterator.close）**：java StreamFn 契约无 signal（批 A8
   既定），abort 语义由「停止消费＋关闭迭代器断连」承担。
3. **裁决③（暖路径请求面）**：maxRetries 强制 0 经暖路径专用 streamFn
   （ApiOptions 重建），不动 settings 现读链与 StreamOptions record；三钩跳过、
   头变换保留（pi 暖请求钩面逐字对位）。
4. **裁决④（调度/时钟注入）**：WarmScheduler＋LongSupplier 构造注入；生产＝
   守护 ScheduledExecutorService＋虚拟线程执行 refresh；测试＝手动（翻译 pi
   vi.useFakeTimers 七用例的前提）。
5. **裁决⑤（miss 检测＝增量 tracker）**：pi 三函数都是整表单趟 scan；java
   TUI 是增量条目流，用同一状态机的增量形（feed/detectOnMessageEnd），判定
   逻辑不复制（CacheStats 包内静态共享）。
6. **裁决⑥（E2-14 翻对齐判据）**：延续批 12 裁决①（TUI 消费点计入）——
   有载体的三门落地即翻对齐；无载体两门登 D67 不阻断。
7. **裁决⑦（decide 钩＝独立桥）**：CacheWarmingBridge 晚绑定 install
   （ProviderEventBridge 模式），last-override-wins＋异常 emitError 逐字。
8. **裁决⑧（onWarmed 不移植）**：pi onWarmed 唯一消费者是 entry_appended
   广播；java 的 appendUsage 收口内建广播（appendBoundaryEntry 样板）。

## §7 测试计划

**CacheWarmerTest**（翻译 pi cache-warmer.test.ts 七用例，ManualWarmScheduler
＋手动时钟＋假 WarmStreamFn/SessionAccess）：

1. eligibility/timing 矩阵（pi :127-145：TTL 六组合＋delay 三值＋replayable
   四组合）；
2. replay 保 options（pi :147-180：reasoning/sessionId 透传、maxTokens=1/
   maxRetries=0 覆盖、abort 句柄替换、decide 事件四键、appendUsage 五参、
   二轮 schedule）；
3. deadline 两则（pi :182-218：计时器延迟＋decide 期间过期 ⇒ 不发、reason
   "cache refresh deadline missed"）；
4. 经济决策四臂（pi :220-253：unprofitable stop＋status.decision 快照、扩展
   强制 warm＋note "extension override"、扩展 veto＋extensionOverride=true、
   economics unavailable）；
5. stop 三因链（pi :255-283：off／unknown TTL／不可重放／context 变化／mode
   运行中改 off／streaming 模式 settled）；
6. 替换 abort＋失败不记账（pi :285-308）；
7. 格式化两则（pi :310-335：status 行逐字 "Decision in 3m 42s (60%
   continuation probability, expected savings $0.360 >= $0.050 -> warm)"、
   usage 行 "Cache warmed (extension override): $0.029497"）。

**CacheWarmingBridgeTest**：last-override-wins（pi :338-366 翻译：两扩展
warm→stop ⇒ "stop"）；处理器抛错 ⇒ emitError＋决策不丢。

**CacheStatsTest**（pi 无测试，自写各臂）：首 turn 不计／零缓存未报过不计／
噪声地板 1024／compaction 复位／模型切换不豁免且 modelChanged=true／idleMs
取差／cache_warm 条目充 prev（reportedCache 恒 true）／sticky reportedCache。

**DefaultProvidersWarmTest**：maxRetries=0 强制；三钩缺席（ProviderHooks 键
不入 extra）；applyHeaderTransform 保留（钩订阅时头改写生效）。

**AgentSessionCacheWarmingTest**（装配/接线）：

- appendUsage 收口：条目九键形状＋EntryAppended 广播＋持久化落盘（JSONL 含
  `"kind":"cache_warm"`，跨读向 pi 已有 PiV3UsageEntryTest 补反向）＋
  footer addTokens；
- warming wrapper 只包 harness：prompt 触发 start；直接调摘要路径不触发；
- settled 双发射点 ⇒ onAgentSettled；closeWith ⇒ cancel；
  setCacheWarmingMode ⇒ settings 写入＋onModeChanged；
- run 中 append（streaming 臂）⇒ deferred write 路径落盘（PiLaneSink 同机制）。

**TUI 测试**：ChatMessage 文案两形（带/不带 note）＋非 cache_warm kind 不渲；
ChatScreen 即时臂（EntryAppended→门开渲染/门关不渲＋onEntry 去重）；
CacheMissTracker 双臂＋同实例去重；SettingsScreen 两行循环＋setter 走
session.setCacheWarmingMode；rebuildTranscript 经 tracker 注入 miss 通知。

**/session 测试**：段文案（status 三态：unavailable/inactive+reason/decision
两行）。

## §8 对账表（判定列：前态→后态）

| 行 | 单元 | w | 前→后 | 任务 |
|---|---|---|---|---|
| map/04 D 域 #16 | `cache_warming_decision` | 2 | 缺失→**对齐**（+2.0） | T1/T3 |
| map/04 E2-49 | `cacheWarming` | 2 | 存疑→**对齐**（+1.0） | T1/T6/T7/T8/T9 |
| map/04 E2-14 | `showCacheMissNotices` | 1 | 存疑→**对齐**（+0.5） | T9/T10/T11（D67 窄差） |
| map/05 R24 | 缓存预热用量行 | 1 | 存疑→**对齐**（+0.5） | T5/T10/T12（D68 窄差） |

模块：311.0＋4.0＝**315.0/485 ≈ 64.9%**；D 域 73.0＋2.0＝**75.0/83 ≈
90.4%**；E2 域 75.0＋1.5＝**76.5/84 ≈ 91.1%**；全仓 985.0＋4.0＝
**989.0/1379 ≈ 71.7%**。

## §9 闭环回填

闭环时同步（docs/20 §4 三处＋本稿）：

1. docs/04 台账：D65–D68 登记；E2-14/E2-49/D#16 触发条件条目核销（批 12
   回填的「cache-warmer 两键随子系统批」条）。
2. docs/05＋docs/map/04（D 域 #16、E2-14、E2-49 三行）＋docs/map/05（R24
   行）翻判定＋实算汇总。
3. docs/20：§2 表追加批 13 行（含账面 Δ 实算）；§3 移除序 12a 行、序 13
   提为下一批；§1 四项读数重填。
4. 记忆：批 13 闭鼓楼更新（含本批新坑）。
