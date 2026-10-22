package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.Usage;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.extension.event.ExtensionEvent.CacheWarmingDecisionEvent;

/**
 * 批 13（docs/24 T1）：pi {@code core/cache-warmer.ts}（:162-401）的逐字移植——
 * 靠「在缓存项到期前以 {@code maxTokens=1} 重放同一请求」保住一个 prompt 缓存项。
 * {@link #start} 替换旧 run；暖请求<b>不</b>顺延固定安全窗。
 *
 * <p>停止理由全集（pi 逐字，状态显示与测试断言依赖）：{@code "waiting for first
 * request"}／{@code "cache warming disabled"}／{@code "conversation context changed"}／
 * {@code "cache economics unavailable"}／{@code "request cannot be replayed safely"}／
 * {@code "request disabled prompt caching"}／{@code "cache lifetime unavailable"}／
 * {@code "agent run settled"}／{@code "30-minute idle safety limit reached"}／
 * {@code "one-hour safety limit reached"}／{@code "cache refresh deadline missed"}／
 * {@code "stopped by extension"}／{@code "expected savings below threshold"}／
 * {@code "inactive"}。</p>
 *
 * <p><b>并发纪律</b>（pi 单线程 ⇒ java 显式化）：{@code run} 槽与状态迁移走
 * {@code synchronized} 小块；阻塞段（decide 钩／暖 stream）在<b>锁外</b>，前后各做
 * 一次 {@link #validateRun}＋deadline 检查（pi :302/:318/:340 三段校验逐字）。
 * {@code isCurrent} 在锁外现读（与 pi 调用点现算同）——装配侧捕获的是
 * {@code List.copyOf} 快照（{@code lane.messages} 全走就地重写，活视图会让
 * 前缀比对恒真，评审 C1）；快照读到追加中间态的方向安全（误判 false ⇒
 * 提前停 warm），残余 {@link RuntimeException}（竞态下列表缩容）按 false 收。</p>
 */
public final class CacheWarmer {

    /** pi :16——streaming 阶段安全窗：自发起请求起不超过一小时。 */
    static final long MAX_WARMING_AGE_MS = 60 * 60_000L;
    /** pi :18——idle 阶段安全窗（估计可靠性随年龄下降）。 */
    static final long MAX_IDLE_WARMING_AGE_MS = 30 * 60_000L;
    /** pi :20——期望节省下限（美元）。 */
    static final double MINIMUM_EXPECTED_SAVINGS = 0.05;
    /** pi :26——idle 期间真实请求先到的概率（pi 实测常量，每会话估计并不更优）。 */
    static final double IDLE_CONTINUATION_PROBABILITY = 0.15;

    /** pi {@code ModelRuntime.streamSimple} 的 java 形（暖重放专用车道调用）。 */
    @FunctionalInterface
    public interface WarmStreamFn {
        /**
         * 阻塞消费到终局；返回终局 {@link Message.AssistantMessage}
         * （{@code stopReason} 见 {@link StopReason}；错误编码 ⇒ ERROR 终局，
         * warmer 不记账）。
         */
        Message.AssistantMessage streamToFinal(ModelId<?> model, Context context,
                                               StreamOptions options, WarmAbort abort);
    }

    /**
     * pi {@code AbortController} 的 java 形（裁决②/D65）：java 的 {@code StreamFn}
     * 契约无 signal，abort 语义＝「停止消费＋关闭 in-flight 迭代器断连」。
     */
    public interface WarmAbort {
        /** 中断本次暖重放（clearRun/replace 时调用）；幂等。 */
        void abort();

        /** 装配侧绑定 in-flight 资源（暖请求开始时）；绑定前已 abort ⇒ 立即关闭。 */
        void bind(AutoCloseable inFlight);

        /** 是否已 abort（测试与状态断言用）。 */
        boolean aborted();
    }

    /** pi {@code Pick<SessionManager, "appendUsage" | "getBranch">} 的 java 形。 */
    public interface SessionAccess {
        /**
         * pi {@code appendUsage}：追加模型归因用量条目（广播与落盘内建于装配侧收口）。
         * 会话已拆除 ⇒ {@code null}（warmer 不记账）。
         */
        Entry.Usage appendUsage(String kind, String provider, String model,
                                Usage usage, String note);

        /** pi {@code getBranch}：当前分支的条目序列（经济学扫描的输入）。 */
        List<Entry> getBranch();
    }

    /**
     * pi 的 {@code decide} 回调（cache-warmer.ts:169）——每次刷新前问扩展；
     * 抛错/异常 ⇒ 回落 pi 自己的决策（:315-317）。
     */
    @FunctionalInterface
    public interface DecideHook {
        /** 返回最终动作；覆写语义由装配侧（{@code CacheWarmingBridge}）实现。 */
        CacheWarming.Action decide(CacheWarmingDecisionEvent event);
    }

    private final WarmStreamFn warmStream;
    private final SessionAccess sessionAccess;
    private final Supplier<String> getMode;
    private final DecideHook decide;
    private final WarmScheduler scheduler;
    private final LongSupplier nowMs;
    private final Function<ModelId<?>, Optional<ModelInfo>> modelInfo;

    private ActiveRun run;
    private CacheWarming.Status inactive;

    /**
     * pi :173-184 构造对位＋调度/时钟/目录三注入（裁决④）。
     *
     * @param getMode   "off" | "streaming" | "idle"（现读，pi {@code getCacheWarmingMode}）
     * @param modelInfo 目录查询（经济学与 TTL 的操作数）；未命中 ⇒ {@link Optional#empty()}
     */
    public CacheWarmer(WarmStreamFn warmStream, SessionAccess sessionAccess,
                       Supplier<String> getMode, DecideHook decide,
                       WarmScheduler scheduler, LongSupplier nowMs,
                       Function<ModelId<?>, Optional<ModelInfo>> modelInfo) {
        this.warmStream = warmStream;
        this.sessionAccess = sessionAccess;
        this.getMode = getMode;
        this.decide = decide;
        this.scheduler = scheduler;
        this.nowMs = nowMs;
        this.modelInfo = modelInfo;
        this.inactive = new CacheWarming.Status("inactive", "waiting for first request",
            null, null, null);
    }

    // ── 三纯函数（pi :29-58）在 {@link CacheWarmingEconomics}（≤500 行规拆分）──

    // ── 状态读口（pi :186-202）───────────────────────────────────────

    /** pi {@code get status}：每次读现算经济学（pi 同）；快照后锁外求值。 */
    public CacheWarming.Status status() {
        ActiveRun snapshot;
        CacheWarming.Status inactiveSnap;
        synchronized (this) {
            if ("off".equals(getMode.get())) {
                return new CacheWarming.Status("inactive", "cache warming disabled",
                    null, null, null);
            }
            snapshot = this.run;
            inactiveSnap = this.inactive;
        }
        if (snapshot == null) {
            return inactiveSnap;
        }
        if (!safeIsCurrent(snapshot)) {
            return new CacheWarming.Status("inactive", "conversation context changed",
                null, null, null);
        }
        var decision = evaluate(snapshot);
        var refreshing = snapshot.timer == null;
        if (!decision.economicsAvailable() && !refreshing) {
            return new CacheWarming.Status("inactive", "cache economics unavailable",
                null, null, null);
        }
        return new CacheWarming.Status(refreshing ? "refreshing" : "scheduled", null,
            snapshot.nextWarmAt, decision, snapshot.extensionOverride);
    }

    // ── 生命周期（pi :205-298）───────────────────────────────────────

    /** pi :205-243：在 {@code isCurrent} 成立期间保住 {@code request} 写下的缓存项。 */
    public synchronized void start(CacheWarming.WarmRequest request, BooleanSupplier isCurrent) {
        clearRun();
        var options = request.options() == null ? StreamOptions.defaults() : request.options();
        if ("off".equals(getMode.get())) {
            stop("cache warming disabled");
            return;
        }
        var info = modelInfo.apply(request.model())
            .orElseGet(() -> ModelInfo.minimal(request.model()));
        if (!CacheWarmingEconomics.isReplayable(info, options)) {
            stop("request cannot be replayed safely");
            return;
        }
        var ttlMs = CacheWarmingEconomics.getPromptCacheTtlMs(info, options);
        if (ttlMs == null) {
            stop(options.cacheRetention().isPresent()
                    && options.cacheRetention().get() == CacheRetention.NONE
                ? "request disabled prompt caching"
                : "cache lifetime unavailable");
            return;
        }
        var delayMs = CacheWarmingEconomics.getCacheWarmingDelayMs(ttlMs);
        if (delayMs == null) {
            stop("cache lifetime unavailable");
            return;
        }
        var active = new ActiveRun(new CacheWarming.WarmRequest(
            request.model(), request.context(), options), isCurrent, ttlMs, delayMs,
            nowMs.getAsLong());
        this.run = active;
        schedule(active);
    }

    /** pi :245-257：streaming 模式 ⇒ 停；否则转 idle 相＋30 分钟窗检查。 */
    public synchronized void onAgentSettled() {
        var active = this.run;
        if (active == null) {
            return;
        }
        if ("streaming".equals(getMode.get())) {
            stop("agent run settled");
            return;
        }
        active.phase = "idle";
        var deadline = active.startedAt + MAX_IDLE_WARMING_AGE_MS;
        if (active.nextWarmAt > deadline || nowMs.getAsLong() >= deadline) {
            stop("30-minute idle safety limit reached");
        }
    }

    /** pi :260-265：持久化的 warming 模式改动后重调和（写设置后立即调）。 */
    public synchronized void onModeChanged() {
        var active = this.run;
        if (active == null) {
            return;
        }
        var reason = getModeStopReason(active);
        if (reason != null) {
            stop(reason);
        }
    }

    /** pi :267-269。 */
    public synchronized void cancel() {
        stop("inactive");
    }

    /** pi :271-277：清计时器＋中断在飞暖请求。 */
    private void clearRun() {
        var active = this.run;
        if (active == null) {
            return;
        }
        this.run = null;
        var timer = active.timer;
        if (timer != null) {
            timer.cancel();
        }
        active.abort.abort();
    }

    /** pi :279-282（无决策载荷臂）。 */
    private void stop(String reason) {
        clearRun();
        this.inactive = new CacheWarming.Status("inactive", reason, null, null, null);
    }

    /** pi :279-282（携决策臂——停止的那次决策留给 {@code /session} 展示）。 */
    private void stop(String reason, CacheWarming.Decision decision, boolean extensionOverride) {
        clearRun();
        this.inactive = new CacheWarming.Status("inactive", reason, null, decision,
            extensionOverride);
    }

    /** pi :284-298：排下一轮（预留一半到期余量给计时器延迟）＋双安全窗检查。 */
    private void schedule(ActiveRun active) {
        active.extensionOverride = false;
        var now = nowMs.getAsLong();
        active.nextWarmAt = now + active.delayMs;
        // 计时器可能在休眠/事件循环阻塞后迟到：预留一半的原到期余量给延迟与派发；
        // 迟到的刷新很可能是一笔全价 cache write，而不是 warm。
        active.refreshDeadlineAt = active.nextWarmAt + (active.ttlMs - active.delayMs) / 2;
        var deadline = active.startedAt + ("idle".equals(active.phase)
            ? MAX_IDLE_WARMING_AGE_MS : MAX_WARMING_AGE_MS);
        if (active.nextWarmAt > deadline || now >= deadline) {
            stop("idle".equals(active.phase)
                ? "30-minute idle safety limit reached" : "one-hour safety limit reached");
            return;
        }
        active.timer = scheduler.schedule(() -> refresh(active),
            Math.max(0, active.nextWarmAt - nowMs.getAsLong()));
    }

    // ── 刷新（pi :300-376）───────────────────────────────────────────

    /**
     * pi :300-355：三段校验（进场 → decide 后 → stream 后）＋decide 钩＋经济闸门。
     * 全程<b>不持锁</b>跑阻塞段；状态迁移回锁内。
     */
    private void refresh(ActiveRun active) {
        synchronized (this) {
            active.timer = null;
        }
        if (!validateRun(active)) {
            return;
        }
        if (refreshDeadlineMissed(active)) {
            return;
        }
        var decision = evaluate(active);
        var action = decision.action();
        try {
            var hooked = decide.decide(new CacheWarmingDecisionEvent(
                decision.warmCost(), decision.missCost(), decision.continuationProbability(),
                action));
            if (hooked != null) {
                action = hooked;
            }
        } catch (Throwable t) {
            // pi :315-317：扩展失败回落 pi 自己的决策。
        }
        if (!validateRun(active) || refreshDeadlineMissed(active)) {
            return;
        }
        var extensionOverride = action != decision.action();
        if (action == CacheWarming.Action.STOP) {
            var reason = extensionOverride
                ? "stopped by extension"
                : decision.economicsAvailable()
                    ? "expected savings below threshold"
                    : "cache economics unavailable";
            synchronized (this) {
                stop(reason, decision, extensionOverride);
            }
            return;
        }

        synchronized (this) {
            active.extensionOverride = extensionOverride;
        }
        try {
            var message = warmStream.streamToFinal(active.request.model(),
                active.request.context(), withWarmOverrides(active.request.options()),
                active.abort);
            if (!validateRun(active)) {
                return;
            }
            if (message != null && message.stopReason() != StopReason.ERROR
                    && message.stopReason() != StopReason.ABORTED
                    && message.usage() != null) {
                // pi :342-348：responseModel ?? model；note＝扩展覆写标记。
                // 广播/落盘内建于装配侧 appendUsage 收口（裁决⑧，pi onWarmed 不移植）。
                sessionAccess.appendUsage("cache_warm", message.provider(),
                    message.responseModel() != null ? message.responseModel() : message.model(),
                    message.usage(), extensionOverride ? "extension override" : null);
            }
        } catch (Throwable t) {
            // pi :351-353：cache warming is best-effort，不得影响在跑的 agent run。
        }
        synchronized (this) {
            if (this.run == active) {
                schedule(active);
            }
        }
    }

    /** pi :334-338 的展开覆盖：{@code maxTokens: 1}（maxRetries 在暖路径请求面，T7）。 */
    private static StreamOptions withWarmOverrides(StreamOptions options) {
        return new StreamOptions(OptionalInt.of(1), options.temperature(),
            options.reasoning(), options.cacheRetention(), options.sessionId(),
            options.thinkingBudgets());
    }

    /** pi :357-361。 */
    private synchronized boolean refreshDeadlineMissed(ActiveRun active) {
        if (nowMs.getAsLong() <= active.refreshDeadlineAt) {
            return false;
        }
        stop("cache refresh deadline missed");
        return true;
    }

    /** pi :363-369：run 同一性＋模式停止理由＋isCurrent。 */
    private boolean validateRun(ActiveRun active) {
        synchronized (this) {
            if (this.run != active) {
                return false;
            }
            var reason = getModeStopReason(active);
            if (reason == null && !safeIsCurrent(active)) {
                reason = "conversation context changed";
            }
            if (reason == null) {
                return true;
            }
            stop(reason);
            return false;
        }
    }

    /** pi :371-376。 */
    private String getModeStopReason(ActiveRun active) {
        var mode = getMode.get();
        if ("off".equals(mode)) {
            return "cache warming disabled";
        }
        if ("streaming".equals(mode) && "idle".equals(active.phase)) {
            return "agent run settled";
        }
        return null;
    }

    /**
     * isCurrent 竞态收口（类注释）：活视图在引擎追加中间态读到残表
     * ⇒ 按 false 收（安全方向：提前停 warm；pi 的 isCurrent 本就是 best-effort 闸门）。
     */
    private static boolean safeIsCurrent(ActiveRun active) {
        try {
            return active.isCurrent.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── 经济学 ──────────────────────────────────────────────────────

    /** pi :378-400（本体在 {@link CacheWarmingEconomics}——≤500 行规拆分，评审 I2）。 */
    private CacheWarming.Decision evaluate(ActiveRun active) {
        return CacheWarmingEconomics.evaluate(modelInfo, active.request.model(),
            sessionAccess.getBranch(), active.phase);
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /** pi :141-155 的 {@code ActiveRun}（可变字段 volatile——状态迁移锁外读者见 {@link #status}）。 */
    private static final class ActiveRun {
        final CacheWarming.WarmRequest request;
        final BooleanSupplier isCurrent;
        final long ttlMs;
        final long delayMs;
        final long startedAt;
        final AbortHandle abort = new AbortHandle();
        volatile long refreshDeadlineAt;
        volatile String phase = "streaming";
        volatile long nextWarmAt;
        volatile boolean extensionOverride;
        volatile WarmScheduler.Cancellable timer;

        ActiveRun(CacheWarming.WarmRequest request, BooleanSupplier isCurrent,
                  long ttlMs, long delayMs, long startedAt) {
            this.request = request;
            this.isCurrent = isCurrent;
            this.ttlMs = ttlMs;
            this.delayMs = delayMs;
            this.startedAt = startedAt;
        }
    }

    /** pi {@code AbortController} 的 run 作用域实现（裁决②/D65）。 */
    private static final class AbortHandle implements WarmAbort {
        private final java.util.concurrent.atomic.AtomicBoolean aborted =
            new java.util.concurrent.atomic.AtomicBoolean();
        private volatile AutoCloseable inFlight;

        @Override
        public void abort() {
            if (!aborted.compareAndSet(false, true)) {
                return;
            }
            var resource = inFlight;
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception e) {
                    // 断连 best-effort：残余帧由 HTTP 层兜底（D65）。
                }
            }
        }

        @Override
        public void bind(AutoCloseable resource) {
            inFlight = resource;
            if (aborted.get()) {
                // 绑定前已 abort（clearRun 先于 stream 起手）⇒ 立即断。
                try {
                    resource.close();
                } catch (Exception e) {
                    // 同上。
                }
            }
        }

        @Override
        public boolean aborted() {
            return aborted.get();
        }
    }
}
