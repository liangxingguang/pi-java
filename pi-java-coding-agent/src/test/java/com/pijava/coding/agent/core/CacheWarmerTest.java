package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.Usage;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.PromptCacheLifetimes;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.builtin.ProviderCatalog;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.coding.agent.extension.event.ExtensionEvent.CacheWarmingDecisionEvent;

/**
 * 批 13（docs/24 §7）：pi {@code test/cache-warmer.test.ts}（367 行）七用例的
 * 逐条翻译 —— {@code vi.useFakeTimers} ⇒ {@link ManualWarmScheduler}＋同源时钟；
 * 假 {@code ModelRuntime.streamSimple} ⇒ 假 {@link CacheWarmer.WarmStreamFn}；
 * {@code SessionManager.inMemory()} ⇒ 记录形 {@link CacheWarmer.SessionAccess}。
 *
 * <p>模型夹具用<b>真目录</b>（{@link ProviderCatalog#allModels()}）：pi 测试同样
 * 取 {@code getBuiltinModel}（opus-4-6＝adaptive、sonnet-4-5＝budget、gpt-5＝openai），
 * 经济学断言（missCost 0.575／warmCost 0.050025）依赖目录价目。</p>
 *
 * <p>pi :133 的 {@code env:{PI_CACHE_RETENTION:"long"}} 子例不翻：java 的
 * {@code StreamOptions} 无 env 键（设计 §3 T1），环境链读进程 env，与车道
 * {@code CompatResolver.resolveCacheRetention} 同源——该链的夹具在 ai 侧已有。</p>
 */
class CacheWarmerTest {

    private static final ModelInfo ADAPTIVE = catalogFind("anthropic", "claude-opus-4-6");
    private static final ModelInfo BUDGET = catalogFind("anthropic", "claude-sonnet-4-5");
    private static final ModelInfo OPENAI = catalogFind("openai", "gpt-5");
    // pi 的 unknownModel＝同 id 覆写 promptCache:undefined（模型对象自带数据）；
    // java 的 ModelInfo 经 id 查目录 ⇒ 无缓存档夹具须用独立 id（opus-4-5 不在他处用）。
    private static final ModelInfo UNKNOWN = withPromptCache(
        catalogFind("anthropic", "claude-opus-4-5"), null);

    /** pi :47-54 的 {@code warmUsage} 逐字。 */
    private static final Usage WARM_USAGE = new Usage(0, 1, 100, 0, null, null, 101,
        new Usage.Cost(0, 0, 0.01, 0, 0.01));

    private static ModelInfo catalogFind(String provider, String modelName) {
        return ProviderCatalog.allModels().find(ModelId.of(provider, modelName)).orElseThrow();
    }

    private static ModelInfo withPromptCache(ModelInfo base, PromptCacheLifetimes promptCache) {
        return new ModelInfo(base.id(), base.displayName(), base.capabilities(),
            base.maxInputTokens(), base.maxOutputTokens(), base.deprecated(), base.pricing(),
            base.thinkingLevelMap(), base.headers(), base.samplingParams(), base.compat(),
            base.api(), base.baseUrl(), promptCache, base.inputLimits(),
            base.samplingParamsByThinkingLevel());
    }

    // ── 夹具（pi :56-122 的 fakeRuntime/request/current）────────────────

    /** pi :56-67：终局助手消息（stopReason 默认 "length"）。 */
    private static Message.AssistantMessage response(ModelInfo model) {
        return response(model, StopReason.LENGTH);
    }

    private static Message.AssistantMessage response(ModelInfo model, StopReason stopReason) {
        return new Message.AssistantMessage(List.of(), stopReason, null,
            model.api(), model.id().provider(), model.id().modelName(),
            WARM_USAGE, Instant.ofEpochMilli(0), null, null);
    }

    /** pi :69-82：分支尾 assistant 的 prompt 量（input+cacheRead+cacheWrite）。 */
    private static List<Entry> branchWithPrompt(long promptTokens) {
        var usage = new Usage(0, 10, promptTokens, 0, null, null, promptTokens + 10,
            Usage.Cost.zero());
        var message = new Message.AssistantMessage(List.of(), StopReason.LENGTH, null,
            ADAPTIVE.api(), ADAPTIVE.id().provider(), ADAPTIVE.id().modelName(),
            usage, Instant.ofEpochMilli(0), null, null);
        return List.of(new Entry.Message("a", 0, null, Instant.ofEpochMilli(0), message, null));
    }

    private record StreamCall(ModelId<?> model, Context context, StreamOptions options,
                              CacheWarmer.WarmAbort abort) {}

    private record UsageCall(String kind, String provider, String model, Usage usage,
                             String note) {}

    /** pi :84-116 的 {@code fakeRuntime}：记录调用/事件/记账，模式与分支可变。 */
    private static final class FakeRuntime {
        final ManualWarmScheduler scheduler = new ManualWarmScheduler();
        final List<StreamCall> calls = new ArrayList<>();
        final List<CacheWarmingDecisionEvent> events = new ArrayList<>();
        final List<UsageCall> appended = new ArrayList<>();
        final CacheWarmer warmer;
        String mode = "idle";
        List<Entry> branch = branchWithPrompt(100_000);
        /** 非 null ⇒ streamToFinal 改走它（阻塞/失败臂）。 */
        java.util.function.Function<ModelInfo, Message.AssistantMessage> result;
        /** 非 null ⇒ decide 改走它（覆写/veto/时钟推进臂）。 */
        java.util.function.Function<CacheWarmingDecisionEvent, CacheWarming.Action> decideOverride;

        FakeRuntime() {
            warmer = new CacheWarmer(
                (model, context, options, abort) -> {
                    calls.add(new StreamCall(model, context, options, abort));
                    var fn = result;
                    return fn != null ? fn.apply(ADAPTIVE) : response(ADAPTIVE);
                },
                new CacheWarmer.SessionAccess() {
                    @Override
                    public Entry.Usage appendUsage(String kind, String provider, String model,
                                                   Usage usage, String note) {
                        appended.add(new UsageCall(kind, provider, model, usage, note));
                        return new Entry.Usage("u" + appended.size(), 0, null,
                            Instant.ofEpochMilli(0), kind, provider, model, usage, note,
                            null, null, null, null, null);
                    }

                    @Override
                    public List<Entry> getBranch() {
                        return branch;
                    }
                },
                () -> mode,
                event -> {
                    events.add(event);
                    var override = decideOverride;
                    return override != null ? override.apply(event) : event.action();
                },
                scheduler, scheduler.clock(),
                id -> {
                    for (var info : List.of(ADAPTIVE, BUDGET, UNKNOWN, OPENAI)) {
                        if (info.id().equals(id)) {
                            return Optional.of(info);
                        }
                    }
                    return Optional.empty();
                });
        }
    }

    /** pi :118-120：默认请求（adaptive 模型、空上下文、空 options）。 */
    private static CacheWarming.WarmRequest request(ModelInfo model, StreamOptions options) {
        return new CacheWarming.WarmRequest(model.id(), Context.of(new ArrayList<>()), options);
    }

    private static CacheWarming.WarmRequest request() {
        return request(ADAPTIVE, new StreamOptions(OptionalInt.empty(),
            java.util.OptionalDouble.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), null));
    }

    private static StreamOptions optionsWithReasoning() {
        return new StreamOptions(OptionalInt.empty(), java.util.OptionalDouble.empty(),
            Optional.of(new ThinkingLevel.High()), Optional.empty(), Optional.of("s"), null);
    }

    // ── pi :127-145 eligibility/timing 矩阵 ─────────────────────────────

    @Test
    void derivesEligibilityAndTimingFromRetentionAndProviderBehavior() {
        var noOptions = new StreamOptions(OptionalInt.empty(),
            java.util.OptionalDouble.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), null);
        var longRetention = new StreamOptions(OptionalInt.empty(),
            java.util.OptionalDouble.empty(), Optional.empty(),
            Optional.of(CacheRetention.LONG), Optional.empty(), null);
        var noRetention = new StreamOptions(OptionalInt.empty(),
            java.util.OptionalDouble.empty(), Optional.empty(),
            Optional.of(CacheRetention.NONE), Optional.empty(), null);
        var openaiLongCache = withPromptCache(OPENAI, new PromptCacheLifetimes(300, 86_400));

        assertThat(CacheWarmingEconomics.getPromptCacheTtlMs(ADAPTIVE, noOptions)).isEqualTo(300_000L);
        assertThat(CacheWarmingEconomics.getPromptCacheTtlMs(ADAPTIVE, longRetention)).isEqualTo(3_600_000L);
        assertThat(CacheWarmingEconomics.getPromptCacheTtlMs(ADAPTIVE, noRetention)).isNull();
        assertThat(CacheWarmingEconomics.getPromptCacheTtlMs(openaiLongCache, longRetention))
            .isEqualTo(86_400_000L);
        assertThat(CacheWarmingEconomics.getPromptCacheTtlMs(UNKNOWN, noOptions)).isNull();

        assertThat(CacheWarmingEconomics.getCacheWarmingDelayMs(300_000)).isEqualTo(270_000L);
        assertThat(CacheWarmingEconomics.getCacheWarmingDelayMs(60_000)).isEqualTo(50_000L);
        assertThat(CacheWarmingEconomics.getCacheWarmingDelayMs(10_000)).isNull();

        assertThat(CacheWarmingEconomics.isReplayable(BUDGET, optionsWithReasoning())).isFalse();
        assertThat(CacheWarmingEconomics.isReplayable(BUDGET, noOptions)).isTrue();
        assertThat(CacheWarmingEconomics.isReplayable(ADAPTIVE, optionsWithReasoning())).isTrue();
        assertThat(CacheWarmingEconomics.isReplayable(OPENAI, optionsWithReasoning())).isTrue();
    }

    // ── pi :147-180 replay 保 options＋二轮 schedule ────────────────────

    @Test
    void replaysProfitableRequestsAndPreservesOptionsAcrossRepeatedRefreshes() {
        var fake = new FakeRuntime();
        fake.warmer.start(request(ADAPTIVE, optionsWithReasoning()), () -> true);
        fake.scheduler.advance(270_000);

        assertThat(fake.calls).hasSize(1);
        var call = fake.calls.get(0);
        assertThat(call.model()).isEqualTo(ADAPTIVE.id());
        // maxTokens=1 由 warmer 侧覆盖（pi :335）；reasoning/sessionId 透传。
        assertThat(call.options().maxTokens()).hasValue(1);
        assertThat(call.options().reasoning()).containsInstanceOf(ThinkingLevel.High.class);
        assertThat(call.options().sessionId()).contains("s");
        // pi :160：signal 被 run 自己的 controller 替换——java 形＝run 作用域 abort 句柄。
        assertThat(call.abort()).isNotNull();
        assertThat(call.abort().aborted()).isFalse();

        assertThat(fake.events).hasSize(1);
        var event = fake.events.get(0);
        assertThat(event.continuationProbability()).isEqualTo(1.0);
        assertThat(event.action()).isEqualTo(CacheWarming.Action.WARM);
        assertThat(event.missCost()).isCloseTo(0.575, within(1e-9));
        assertThat(event.warmCost()).isCloseTo(0.050025, within(1e-9));

        // pi :168-174：appendUsage 五参（note 缺席 ≙ undefined ⇒ null）。
        assertThat(fake.appended).containsExactly(
            new UsageCall("cache_warm", "anthropic", "claude-opus-4-6", WARM_USAGE, null));

        fake.scheduler.advance(270_000);
        assertThat(fake.calls).hasSize(2);
        fake.warmer.cancel();
    }

    // ── pi :182-218 deadline 两则 ────────────────────────────────────────

    @Test
    void doesNotIssueRefreshesAfterTheirSafeDeadline() {
        var fake = new FakeRuntime();
        fake.warmer.start(request(), () -> true);
        // 五分钟缓存排在 4m30s，保留 15s 到期余量；模拟计时器因休眠迟到 15s 以上。
        fake.scheduler.advance(285_001);

        assertThat(fake.calls).isEmpty();
        assertThat(fake.warmer.status().state()).isEqualTo("inactive");
        assertThat(fake.warmer.status().reason()).isEqualTo("cache refresh deadline missed");
    }

    @Test
    void rechecksTheDeadlineAfterAnExtensionDecision() {
        var fake = new FakeRuntime();
        fake.decideOverride = event -> {
            // decide 期间时钟越过 deadline（pi :204-208 的 setSystemTime 对位）。
            fake.scheduler.advance(15_001);
            return CacheWarming.Action.WARM;
        };
        fake.warmer.start(request(), () -> true);
        fake.scheduler.advance(270_000);

        assertThat(fake.calls).isEmpty();
        assertThat(fake.warmer.status().state()).isEqualTo("inactive");
        assertThat(fake.warmer.status().reason()).isEqualTo("cache refresh deadline missed");
    }

    // ── pi :220-253 经济决策四臂 ─────────────────────────────────────────

    @Test
    void appliesEconomicDecisionsAndExtensionOverrides() {
        var unprofitable = new FakeRuntime();
        unprofitable.branch = branchWithPrompt(5_000);
        unprofitable.warmer.start(request(), () -> true);
        unprofitable.scheduler.advance(270_000);
        assertThat(unprofitable.calls).isEmpty();
        var stopped = unprofitable.warmer.status();
        assertThat(stopped.state()).isEqualTo("inactive");
        assertThat(stopped.reason()).isEqualTo("expected savings below threshold");
        assertThat(stopped.decision()).isNotNull();
        assertThat(stopped.decision().action()).isEqualTo(CacheWarming.Action.STOP);
        assertThat(stopped.decision().economicsAvailable()).isTrue();
        assertThat(stopped.extensionOverride()).isEqualTo(Boolean.FALSE);

        var forced = new FakeRuntime();
        forced.branch = branchWithPrompt(5_000);
        forced.decideOverride = event -> CacheWarming.Action.WARM;
        forced.warmer.start(request(), () -> true);
        forced.scheduler.advance(270_000);
        assertThat(forced.calls).hasSize(1);
        assertThat(forced.appended).hasSize(1);
        assertThat(forced.appended.get(0).note()).isEqualTo("extension override");
        forced.warmer.cancel();

        var vetoed = new FakeRuntime();
        vetoed.decideOverride = event -> CacheWarming.Action.STOP;
        vetoed.warmer.start(request(), () -> true);
        vetoed.scheduler.advance(270_000);
        assertThat(vetoed.calls).isEmpty();
        assertThat(vetoed.warmer.status().state()).isEqualTo("inactive");
        assertThat(vetoed.warmer.status().reason()).isEqualTo("stopped by extension");
        assertThat(vetoed.warmer.status().extensionOverride()).isEqualTo(Boolean.TRUE);

        var unavailable = new FakeRuntime();
        unavailable.branch = branchWithPrompt(0);
        unavailable.warmer.start(request(), () -> true);
        assertThat(unavailable.warmer.status().state()).isEqualTo("inactive");
        assertThat(unavailable.warmer.status().reason()).isEqualTo("cache economics unavailable");
        unavailable.scheduler.advance(270_000);
        assertThat(unavailable.calls).isEmpty();
    }

    // ── pi :255-283 stop 三因链 ──────────────────────────────────────────

    @Test
    void stopsForUnsupportedRequestsContextChangesAndModeChanges() {
        var fake = new FakeRuntime();
        fake.mode = "off";
        fake.warmer.start(request(), () -> true);
        assertThat(fake.warmer.status().reason()).isEqualTo("cache warming disabled");

        fake.mode = "idle";
        fake.warmer.start(request(UNKNOWN, new StreamOptions(OptionalInt.empty(),
            java.util.OptionalDouble.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), null)), () -> true);
        assertThat(fake.warmer.status().reason()).isEqualTo("cache lifetime unavailable");

        fake.warmer.start(request(BUDGET, optionsWithReasoning()), () -> true);
        assertThat(fake.warmer.status().reason()).isEqualTo("request cannot be replayed safely");

        var stillCurrent = new java.util.concurrent.atomic.AtomicBoolean(true);
        fake.warmer.start(request(), stillCurrent::get);
        stillCurrent.set(false);
        assertThat(fake.warmer.status().reason()).isEqualTo("conversation context changed");
        fake.scheduler.advance(270_000);
        assertThat(fake.calls).isEmpty();

        fake.warmer.start(request(), () -> true);
        fake.mode = "off";
        fake.scheduler.advance(270_000);
        assertThat(fake.calls).isEmpty();

        var streaming = new FakeRuntime();
        streaming.mode = "streaming";
        streaming.branch = branchWithPrompt(400_000);
        streaming.warmer.start(request(), () -> true);
        streaming.warmer.onAgentSettled();
        assertThat(streaming.warmer.status().reason()).isEqualTo("agent run settled");
    }

    // ── pi :285-308 替换 abort＋失败不记账 ───────────────────────────────

    @Test
    void abortsReplacedRequestsAndDoesNotRecordFailedRefreshes() throws Exception {
        var release = new CountDownLatch(1);
        var pending = new FakeRuntime();
        pending.result = model -> {
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test latch timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return response(model);
        };
        pending.warmer.start(request(), () -> true);
        // refresh 会阻塞在 streamToFinal —— 把 advance 放到另一个线程跑。
        var advancer = Thread.ofVirtual().start(() -> pending.scheduler.advance(270_000));
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pending.calls.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(pending.calls).hasSize(1);

        pending.warmer.start(request(), () -> true);
        assertThat(pending.calls.get(0).abort().aborted()).isTrue();
        release.countDown();
        advancer.join(10_000);
        pending.warmer.cancel();
        pending.scheduler.advance(600_000);
        assertThat(pending.calls).hasSize(1);

        var failed = new FakeRuntime();
        failed.result = model -> response(model, StopReason.ERROR);
        failed.warmer.start(request(), () -> true);
        failed.scheduler.advance(270_000);
        assertThat(failed.appended).isEmpty();
        failed.warmer.cancel();
    }

    // ── pi :310-335 格式化两则 ───────────────────────────────────────────

    @Test
    void formatsStatusAndUsageEntries() {
        var decision = new CacheWarming.Decision("idle", 0.013, 0.621, 0.6, 0.36, true,
            CacheWarming.Action.WARM);
        var status = new CacheWarming.Status("scheduled", null, 222_000L, decision, null);
        assertThat(CacheWarmingFormatting.formatStatus(status, 0)).isEqualTo(
            "Decision in 3m 42s (60% continuation probability,"
                + " expected savings $0.360 >= $0.050 -> warm)");

        var usage = new Usage(0, 1, 100, 0, null, null, 101,
            new Usage.Cost(0.00004, 0.00005, 0.02940725, 0, 0.02949725));
        var entry = new Entry.Usage("u1", 0, null, Instant.ofEpochMilli(0), "cache_warm",
            "anthropic", "claude-opus-4-6", usage, "extension override",
            null, null, null, null, null);
        assertThat(CacheWarmingFormatting.formatUsage(entry))
            .isEqualTo("Cache warmed (extension override): $0.029497");
    }
}
