# 16 - L1 #6 收口设计：缓存预热策略迁入 ai

> 【背景摘要】L1（`pi-java-ai`）目前 **245.5/246 ≈ 99.8%**，唯一计分缺口是 #6：pi 把缓存预热的**策略／经济学**作为 ai 库公开面导出（`packages/ai/src/cache-warming.ts`），Java 的同一套数字落在 `pi-java-coding-agent/core`，ai 侧没有对应面。本稿把这块公开面补进 ai，并把 coding-agent 改成消费者；行为、数字与 wire 不变，只改「库作者可见的归属」。按用户 2026-11-22「继续 docs/16」的指示先出设计稿，审核通过后再写码。
>
> 【锚点与口径】pi 取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`；目标锚点 `a00a5048d`（2026-11-20）。分层判定仍只写 `docs/map/02-ai.md`，本稿只做设计与验收。

## 1. 目标与完成判据

| 项 | 内容 |
|---|---|
| 目标 | #6 从「部分」改判「对齐」⇒ L1 **246/246＝100%**（P12 正式重判口径为准） |
| 行为不变量 | 暖请求的决策数字、TTL、延迟、可重放判据、`maxTokens=1`／`disableContinuation=true`／`maxRetries=0` 全部不变 |
| 完成判据 | ①`mvn test -pl pi-java-ai -am` 全绿；②`mvn test -pl pi-java-coding-agent -am` 全链全绿；③`map/02` #6 行改判，`docs/05` §1.2 与 `docs/09` 同步 |
| 不做项 | 不搬 `Status`／`WarmRequest`／`CacheWarmer` 调度器（它们在 pi 就属 coding-agent）；不搬 `/session` 展示格式；不改暖请求 wire |

## 2. pi 证据

### 2.1 策略本体在 ai

`packages/ai/src/cache-warming.ts`（`index.ts:22` 有 `export * from "./cache-warming.ts"`）：

```ts
export const CACHE_WARMING_MAX_AGE_MS = 60 * 60_000;
export const CACHE_WARMING_MAX_IDLE_AGE_MS = 30 * 60_000;
export const CACHE_WARMING_MINIMUM_EXPECTED_SAVINGS = 0.05;
export const CACHE_WARMING_IDLE_CONTINUATION_PROBABILITY = 0.15;

export function getCacheWarmingDelayMs(ttlMs: number): number | undefined {
    if (ttlMs <= 10_000) return undefined;
    return Math.max(1, Math.floor(Math.min(ttlMs * 0.9, ttlMs - 10_000)));
}

export function getPromptCacheTtlMs(model: Model<Api>, options: SimpleStreamOptions | undefined): number | undefined {
    const retention = options?.cacheRetention
        ?? (getProviderEnvValue("PI_CACHE_RETENTION", options?.env) === "long" ? "long" : "short");
    if (retention === "none") return undefined;
    const seconds = model.promptCache?.[retention];
    return seconds === undefined ? undefined : seconds * 1000;
}

export function isCacheWarmingReplayable(model: Model<Api>, options: SimpleStreamOptions | undefined): boolean {
    if (!options?.reasoning || model.api !== "anthropic-messages") return true;
    return (model as Model<"anthropic-messages">).compat?.forceAdaptiveThinking === true;
}

export function getCacheWarmingRequestOptions<T extends SimpleStreamOptions>(options: T, signal: AbortSignal): T {
    return { ...options, maxTokens: 1, disableContinuation: true, maxRetries: 0, signal };
}

export function evaluateCacheWarming(model: Model<Api>, promptTokens: number, phase: CacheWarmingPhase): CacheWarmingDecision {
    // warmCost = cacheRead(promptTokens) + output(1)
    // missCost  = max(0, cacheMissCost - cacheHitCost)
    // cacheWrite > 0 时 miss 走 cacheWrite 桶，否则走 input 桶
    // continuationProbability = phase === "idle" ? 0.15 : 1
    // economicsAvailable = promptTokens > 0 && (cacheHitCost > 0 || cacheMissCost > 0)
    // action = expectedSavings >= 0.05 ? "warm" : "stop"
}
```

同时导出类型 `CacheWarmingAction`（`"warm" | "stop"`）、`CacheWarmingPhase`（`"streaming" | "idle"`）、`CacheWarmingDecision`（`phase/warmCost/missCost/continuationProbability/expectedSavings/economicsAvailable/action`）。

### 2.2 调度器与状态在 coding-agent

`packages/coding-agent/src/core/cache-warmer.ts:16-26,122-139`：`MAX_WARMING_AGE_*` 使用点、`CacheWarmingStatus`、`CacheWarmRequest`、计时器与扩展钩子。`promptTokens` 由宿主从分支里最近一次真实请求的 usage 算出后传给 `evaluateCacheWarming`。

⇒ 迁移只搬 **ai 的公开面**；宿主状态/调度/事件保持原位。

## 3. Java 现状

| pi 面 | Java 现状（迁移前） |
|---|---|
| `CACHE_WARMING_MAX_AGE_MS`／`MAX_IDLE_AGE_MS` | `CacheWarmer.java:47,49`（`MAX_WARMING_AGE_MS`／`MAX_IDLE_WARMING_AGE_MS`） |
| `MINIMUM_EXPECTED_SAVINGS`／`IDLE_CONTINUATION_PROBABILITY` | `CacheWarmer.java:51,53` |
| `getCacheWarmingDelayMs` | `CacheWarmingEconomics.java:27-32`（包私有） |
| `getPromptCacheTtlMs` | `CacheWarmingEconomics.java:41-56`（吃宿主 `StreamOptions`） |
| `isCacheWarmingReplayable` | `CacheWarmingEconomics.java:58-65` |
| `getCacheWarmingRequestOptions` | 散在宿主：`CacheWarmer.java:363-365`（`maxTokens=1`）、`DefaultProviders.java:322-324`（`maxRetries=0`）、`:320`（`disableContinuation=true`） |
| `evaluateCacheWarming` | `CacheWarmingEconomics.java:67-90` |
| `CacheWarmingAction`／`Phase`／`Decision` | `CacheWarming.java:16-47`（与宿主 `Status`／`WarmRequest` 同文件） |

依赖方向决定的硬约束：`pi-java-ai` **不能** import `com.pijava.agent.*`（`CacheWarmingEconomics` 当前吃 `Entry`／`StreamOptions`），所以不是文件搬家，需要一层宿主适配。

## 4. 目标结构

### 4.1 ai：新增 `com.pijava.ai.api.CacheWarmingPolicy`

一个类承载 pi 的 ai 公开面（常量＋类型＋纯函数）：

```java
public final class CacheWarmingPolicy {
    public static final long MAX_AGE_MS = 60 * 60_000L;
    public static final long MAX_IDLE_AGE_MS = 30 * 60_000L;
    public static final double MINIMUM_EXPECTED_SAVINGS = 0.05;
    public static final double IDLE_CONTINUATION_PROBABILITY = 0.15;

    public enum Phase { STREAMING, IDLE; public String wireName() { ... } }
    public enum Action { WARM, STOP; public String wireName() { ... } }
    public record Decision(Phase phase, double warmCost, double missCost,
                           double continuationProbability, double expectedSavings,
                           boolean economicsAvailable, Action action) {}
    public record WarmRequestOptions(int maxTokens, boolean disableContinuation, int maxRetries) {}

    public static final WarmRequestOptions WARM_REQUEST_OPTIONS =
        new WarmRequestOptions(1, true, 0);

    public static Long delayMs(long ttlMs);                     // null ≙ pi undefined
    public static Long promptCacheTtlMs(ModelInfo model,
            Optional<CacheRetention> requested, String envRetention);
    public static boolean isReplayable(ModelInfo model, boolean reasoningRequested);
    public static Decision evaluate(ModelInfo model, long promptTokens, Phase phase);
}
```

映射表：

| pi | Java | 说明 |
|---|---|---|
| `CACHE_WARMING_MAX_AGE_MS` | `MAX_AGE_MS` | 原值不变 |
| `CACHE_WARMING_MAX_IDLE_AGE_MS` | `MAX_IDLE_AGE_MS` | 原值不变 |
| `CACHE_WARMING_MINIMUM_EXPECTED_SAVINGS` | `MINIMUM_EXPECTED_SAVINGS` | 原值不变 |
| `CACHE_WARMING_IDLE_CONTINUATION_PROBABILITY` | `IDLE_CONTINUATION_PROBABILITY` | 原值不变 |
| `getCacheWarmingDelayMs(ttlMs)` | `delayMs(long)` | `Long` 的 `null` ≙ `undefined` |
| `getPromptCacheTtlMs(model, options)` | `promptCacheTtlMs(ModelInfo, Optional<CacheRetention>, String envRetention)` | env 由宿主传入（当前为进程 `PI_CACHE_RETENTION`；宿主 `StreamOptions` 无 per-request env，属既有窄差） |
| `isCacheWarmingReplayable(model, options)` | `isReplayable(ModelInfo, boolean reasoningRequested)` | `options?.reasoning` 的布尔投影 |
| `getCacheWarmingRequestOptions(options, signal)` | `WARM_REQUEST_OPTIONS`＋宿主应用 | 见裁决 R3 |
| `evaluateCacheWarming(model, promptTokens, phase)` | `evaluate(ModelInfo, long, Phase)` | `promptTokens` 仍由宿主算 |
| `CacheWarmingAction/Phase/Decision` | 同名嵌套类型 | Phase 改 enum，见裁决 R2 |

`evaluate` 内部沿用 `CostCalculator.calculateCost(PricingInfo, Usage)`（`pi-java-ai/.../model/CostCalculator.java:45`），计价桶与 pi 相同：`cacheWritePrice > 0 ⇒ miss 走 cacheWrite`，否则走 input。

### 4.2 coding-agent：只留宿主部分

- `CacheWarming.java` 保留 `Status`／`WarmRequest`（pi `cache-warmer.ts:122-139`），删除 `Action`／`Decision`。
- `CacheWarmingEconomics.java` 改为**宿主适配器**（建议保留类名，javadoc 改写）：
  - `lastPromptTokens(List<Entry>)` 留下（宿主分支遍历，pi 同样由宿主算）；
  - `evaluate(...)` 改成 `modelInfo → ModelInfo` ＋ `lastPromptTokens` ＋ 调 `CacheWarmingPolicy.evaluate`；
  - 其余纯函数删除。
- `CacheWarmer`：常量改引用 `CacheWarmingPolicy.*`；`ActiveRun.phase` 由 `String` 改 `Phase`（`"idle"` 两处赋值/三处比较同步）；`withWarmOverrides` 用 `WARM_REQUEST_OPTIONS.maxTokens()`。
- `DefaultProviders.warmStreamFnFor`：`maxRetries` 用 `WARM_REQUEST_OPTIONS.maxRetries()`，`disableContinuation` 用 `.disableContinuation()`。
- `CacheWarmingFormatting`／`CacheWarmingBridge`／`ExtensionEvent`／`ExtensionResults`：类型引用改为 `CacheWarmingPolicy.Decision`／`Action`。

## 5. 裁决

> 【场景】pi 的 ai 导出名是 `cache-warming`，Java 已有同名的 coding-agent 类 `CacheWarming`（含宿主状态）。
> 【决定】ai 新类命名 `CacheWarmingPolicy`；宿主类 `CacheWarming` 继续只装 `Status`／`WarmRequest`。
> 【风险/代价】无行为影响；[内部，模型不可见]。避免同名简单类在同一包遮蔽导入的歧义。

> 【场景】pi 的 phase 是字符串联合 `"streaming" | "idle"`；Java 现为 `String`。
> 【决定】迁入 ai 时改为 `enum Phase { STREAMING, IDLE }`（提供 `wireName()`）；`Decision` 的 `phase` 类型随之改 enum，宿主 `ActiveRun.phase` 同步。
> 【风险/代价】只在内部代码与展示格式化处改比较方式；wire 与事件载荷不含 phase，[模型不可见]。事件记录里的 phase 若未来上抛，用 `wireName()` 保字面量。

> 【场景】pi 的 `getCacheWarmingRequestOptions` 返回完整 options 对象（含 `signal`）；Java 没有统一的 options record，abort 由宿主的 `WarmAbort` 承载（D65）。
> 【决定】ai 只导出一组**请求覆盖常量** `WARM_REQUEST_OPTIONS`（`maxTokens=1`、`disableContinuation=true`、`maxRetries=0`），由宿主分别落到 `StreamOptions`／`ApiOptions.extra`／`ApiOptions.maxRetries`；`signal` 不进入 ai 记录。
> 【风险/代价】`signal` 的归属是既有 D65，不动；[内部，模型不可见]。三项覆盖值从此只有一个真源。

> 【场景】`CacheWarmingEconomics` 现名在迁移后只剩「分支 → promptTokens」这一件宿主工作。
> 【决定】保留类名与文件（减少评审噪声），javadoc 改写为「宿主适配器」；如后续再增长，再按 ≤500 行规则重命名。
> 【风险/代价】命名略宽泛；[内部，模型不可见]。

## 6. 夹具

| 层 | 夹具 |
|---|---|
| ai 纯函数 | 新增 `CacheWarmingPolicyTest`：延迟（300s→270s、60s→50s、≤10s→null、下限 1ms）；TTL（short 300s／long 3600s／none／未知／env 回落）；可重放（非 anthropic、无 reasoning、anthropic＋adaptive、anthropic＋非 adaptive）；评估（streaming 概率 1／idle 0.15、cacheWrite 桶选择、未知价目 `economicsAvailable=false`、0.05 阈值两侧）；四个常量值 |
| 宿主适配 | `CacheWarmerTest`：删除已迁走的纯函数断言（改由 ai 覆盖），保留 branch→promptTokens 与调度/状态机断言；`DefaultProvidersWarmTest`：现有 `disableContinuation`／`maxRetries` 断言改为验证「值来自 `WARM_REQUEST_OPTIONS`」 |
| 事件 | `CacheWarmingBridgeTest`：`Action` 引用改 ai 类型，行为不变 |

验收命令：

```bash
mvn test -pl pi-java-ai -am
mvn test -pl pi-java-coding-agent -am
```

## 7. 读数与台账

- 代码落地后 `map/02` #6 行：`已核（2026-11-22）：差异在模块归属` → **对齐**；L1 记 **246/246＝100%**。
- `docs/05` §1.2：L1 行 245.5 → 246.0；合计 1,287.5 → **1,288.0/1,397＝92.2%**（百分比不变）。
- `docs/09` §3「当前」列 L1 同步；`docs/04` §0 记账本项直接落地、未新开号。
- `session-resources` 的编号/权重仍归 P12；本包不替 P12 分配。

## 8. 提交切分

| 序 | 内容 | 模块 | 规模 |
|---|---|---|---|
| 1 | 本设计稿 | docs | S |
| 2 | `feat(ai): expose the cache warming policy surface`（新类＋ai 夹具） | ai | M |
| 3 | `refactor(coding-agent): consume the ai cache warming policy`（宿主适配＋类型引用） | coding-agent | M |
| 4 | `docs: close L1 #6`（读数回填） | docs | S |

提交 2 与 3 必须同批评审：只落 2 会留下两套并存的数字；只落 3 会编译失败。
