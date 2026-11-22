package com.pijava.ai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pijava.ai.api.CacheWarmingPolicy.Action;
import com.pijava.ai.api.CacheWarmingPolicy.Phase;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.PromptCacheLifetimes;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.builtin.ProviderCatalog;

/**
 * docs/16 §6（L1 #6 迁移）：pi {@code cache-warming.ts}（锚点 {@code a00a5048d}）
 * 四个常量、四个纯函数与 {@code evaluateCacheWarming} 的逐条夹具。宿主侧只剩
 * 「分支 → promptTokens」与调度/状态机，见 {@code CacheWarmerTest}。
 *
 * <p>模型夹具用真目录（{@link ProviderCatalog#allModels()}）：经济学断言
 * （missCost 0.575／warmCost 0.050025）依赖目录价目，与宿主夹具同源。</p>
 */
class CacheWarmingPolicyTest {

    private static final ModelInfo ADAPTIVE = catalogFind("anthropic", "claude-opus-4-6");
    private static final ModelInfo BUDGET = catalogFind("anthropic", "claude-sonnet-4-5");
    private static final ModelInfo OPENAI = catalogFind("openai", "gpt-5");

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

    // ── pi :31-43 四个常量 ─────────────────────────────────────────

    @Test
    void keepsThePiPolicyConstants() {
        assertThat(CacheWarmingPolicy.MAX_AGE_MS).isEqualTo(3_600_000L);
        assertThat(CacheWarmingPolicy.MAX_IDLE_AGE_MS).isEqualTo(1_800_000L);
        assertThat(CacheWarmingPolicy.MINIMUM_EXPECTED_SAVINGS).isEqualTo(0.05);
        assertThat(CacheWarmingPolicy.IDLE_CONTINUATION_PROBABILITY).isEqualTo(0.15);
        assertThat(CacheWarmingPolicy.WARM_REQUEST_OPTIONS.maxTokens()).isEqualTo(1);
        assertThat(CacheWarmingPolicy.WARM_REQUEST_OPTIONS.disableContinuation()).isTrue();
        assertThat(CacheWarmingPolicy.WARM_REQUEST_OPTIONS.maxRetries()).isZero();
    }

    // ── pi :46-49 延迟 ─────────────────────────────────────────────

    @Test
    void derivesTheWarmDelayFromTheTtl() {
        assertThat(CacheWarmingPolicy.delayMs(300_000)).isEqualTo(270_000L);
        assertThat(CacheWarmingPolicy.delayMs(60_000)).isEqualTo(50_000L);
        assertThat(CacheWarmingPolicy.delayMs(10_000)).isNull();
        // 余量下限 10 秒 ⇒ 10.001s 只剩 1ms；Math.max(1, …) 的不为零保证。
        assertThat(CacheWarmingPolicy.delayMs(10_001)).isEqualTo(1L);
    }

    // ── pi :55-66 TTL ──────────────────────────────────────────────

    @Test
    void resolvesThePromptCacheTtlFromRetentionAndTheModelTier() {
        assertThat(CacheWarmingPolicy.promptCacheTtlMs(ADAPTIVE, Optional.empty(), null))
            .isEqualTo(300_000L);
        assertThat(CacheWarmingPolicy.promptCacheTtlMs(ADAPTIVE,
            Optional.of(CacheRetention.LONG), null)).isEqualTo(3_600_000L);
        assertThat(CacheWarmingPolicy.promptCacheTtlMs(ADAPTIVE,
            Optional.of(CacheRetention.NONE), null)).isNull();
    }

    @Test
    void fallsBackToTheEnvRetentionAndReadsTheMatchingTier() {
        var longCached = withPromptCache(OPENAI, new PromptCacheLifetimes(300, 86_400));
        // 选项缺席 ⇒ 只有字面 "long" 触发长缓存（pi 的 === "long" 判据）。
        assertThat(CacheWarmingPolicy.promptCacheTtlMs(longCached, Optional.empty(), "long"))
            .isEqualTo(86_400_000L);
        assertThat(CacheWarmingPolicy.promptCacheTtlMs(longCached, Optional.empty(), "Long"))
            .isEqualTo(300_000L);
        assertThat(CacheWarmingPolicy.promptCacheTtlMs(longCached,
            Optional.of(CacheRetention.LONG), "short")).isEqualTo(86_400_000L);
    }

    @Test
    void returnsNullWhenTheModelHasNoTierForTheRetention() {
        // 目录没有缓存档（minimal ⇒ promptCache null）⇒ 不 warm。
        assertThat(CacheWarmingPolicy.promptCacheTtlMs(
            ModelInfo.minimal(ModelId.of("unknown", "model")), Optional.empty(), null)).isNull();
    }

    // ── pi :69-75 可重放 ───────────────────────────────────────────

    @Test
    void refusesToReplayBudgetThinkingOnAnthropicModels() {
        assertThat(CacheWarmingPolicy.isReplayable(BUDGET, true)).isFalse();
        assertThat(CacheWarmingPolicy.isReplayable(BUDGET, false)).isTrue();
        assertThat(CacheWarmingPolicy.isReplayable(ADAPTIVE, true)).isTrue();
        assertThat(CacheWarmingPolicy.isReplayable(OPENAI, true)).isTrue();
    }

    // ── pi :83-107 经济学 ──────────────────────────────────────────

    @Test
    void evaluatesStreamingRefreshesAtFullContinuationProbability() {
        var decision = CacheWarmingPolicy.evaluate(ADAPTIVE, 100_000, Phase.STREAMING);
        assertThat(decision.phase()).isEqualTo(Phase.STREAMING);
        assertThat(decision.continuationProbability()).isEqualTo(1.0);
        assertThat(decision.missCost()).isCloseTo(0.575, within(1e-9));
        assertThat(decision.warmCost()).isCloseTo(0.050025, within(1e-9));
        assertThat(decision.expectedSavings()).isCloseTo(0.524975, within(1e-9));
        assertThat(decision.economicsAvailable()).isTrue();
        assertThat(decision.action()).isEqualTo(Action.WARM);
    }

    @Test
    void idleSavingsFallBelowTheThresholdForTheSamePrompt() {
        var decision = CacheWarmingPolicy.evaluate(ADAPTIVE, 100_000, Phase.IDLE);
        assertThat(decision.continuationProbability()).isEqualTo(0.15);
        assertThat(decision.expectedSavings()).isCloseTo(0.036225, within(1e-9));
        assertThat(decision.action()).isEqualTo(Action.STOP);
    }

    @Test
    void pricesMissesInTheInputBucketWhenTheModelHasNoCacheWritePrice() {
        // cacheWrite 价缺席（<0）⇒ miss 落 input 桶：$3/M × 100k ＝ $0.30。
        var inputOnly = new ModelInfo(ADAPTIVE.id(), "input-only", Set.of(),
            0, 0, false, new PricingInfo(3, 15));
        var decision = CacheWarmingPolicy.evaluate(inputOnly, 100_000, Phase.STREAMING);
        assertThat(decision.missCost()).isCloseTo(0.3, within(1e-9));
        assertThat(decision.warmCost()).isCloseTo(15.0 / 1_000_000, within(1e-12));
        assertThat(decision.action()).isEqualTo(Action.WARM);
    }

    @Test
    void marksEconomicsUnavailableForUnknownPromptSizeOrPrices() {
        var noTokens = CacheWarmingPolicy.evaluate(ADAPTIVE, 0, Phase.STREAMING);
        assertThat(noTokens.economicsAvailable()).isFalse();
        assertThat(noTokens.action()).isEqualTo(Action.STOP);

        var unknownPrices = CacheWarmingPolicy.evaluate(
            ModelInfo.minimal(ADAPTIVE.id()), 100_000, Phase.STREAMING);
        assertThat(unknownPrices.economicsAvailable()).isFalse();
        assertThat(unknownPrices.action()).isEqualTo(Action.STOP);
    }

    @Test
    void phaseWireNamesMatchPiLiterals() {
        assertThat(Phase.STREAMING.wireName()).isEqualTo("streaming");
        assertThat(Phase.IDLE.wireName()).isEqualTo("idle");
        assertThat(Action.WARM.wireName()).isEqualTo("warm");
        assertThat(Action.STOP.wireName()).isEqualTo("stop");
    }
}
