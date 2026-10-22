package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.Usage;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.builtin.ProviderCatalog;

/**
 * 批 13（docs/24 §7）：pi {@code core/cache-stats.ts} 的逐臂自写夹具（pi 无测试）——
 * 首 turn 不计／零缓存未报过不计／噪声地板 1024／compaction·branch_summary 复位／
 * 模型切换不豁免（modelChanged=true）／idleMs 取差／cache_warm 条目充 prev
 * （reportedCache 恒 true）／sticky reportedCache／missedCost 拆账数学。
 */
class CacheStatsTest {

    private static final ModelInfo OPUS = ProviderCatalog.allModels()
        .find(ModelId.of("anthropic", "claude-opus-4-6")).orElseThrow();
    /** pi {@code ModelPriceSource} 的 java 形：provider+"/"+model ⇒ 目录查询。 */
    private static final Function<String, Optional<ModelInfo>> MODELS =
        key -> OPUS.id().provider().equals(key.substring(0, key.indexOf('/')))
            ? Optional.of(OPUS) : Optional.empty();

    private static Usage usage(double input, double cacheRead, double cacheWrite) {
        return new Usage(input, 1, cacheRead, cacheWrite, null, null,
            input + cacheRead + cacheWrite + 1, Usage.Cost.zero());
    }

    private static Usage usageWithCost(double input, double cacheRead, double cacheWrite,
                                       double costInput, double costCacheRead,
                                       double costCacheWrite) {
        return new Usage(input, 1, cacheRead, cacheWrite, null, null,
            input + cacheRead + cacheWrite + 1,
            new Usage.Cost(costInput, 0, costCacheRead, costCacheWrite,
                costInput + costCacheRead + costCacheWrite));
    }

    private static Entry.Message assistant(String id, String provider, String model,
                                           long epochMs, Usage usage) {
        var message = new Message.AssistantMessage(List.of(), StopReason.LENGTH, null,
            "anthropic-messages", provider, model, usage, Instant.ofEpochMilli(epochMs),
            null, null);
        return new Entry.Message(id, 0, null, Instant.ofEpochMilli(epochMs), message, null);
    }

    private static Entry compaction(String id, long epochMs) {
        return new Entry.Compaction(id, 0, null, Instant.ofEpochMilli(epochMs),
            "summary", null, List.of(), 0, null, null, null);
    }

    private static Entry branchSummary(String id, long epochMs) {
        return new Entry.BranchSummary(id, 0, null, Instant.ofEpochMilli(epochMs),
            "from", "summary", null, null, null);
    }

    private static Entry cacheWarm(String id, long epochMs, double cacheRead) {
        return new Entry.Usage(id, 0, null, Instant.ofEpochMilli(epochMs), "cache_warm",
            "anthropic", "claude-opus-4-6", usage(0, cacheRead, 0), null,
            null, null, null, null, null);
    }

    @Test
    void firstTurnIsNotCounted() {
        var entries = List.<Entry>of(
            assistant("m1", "anthropic", "claude-opus-4-6", 0, usage(150_000, 0, 50_000)));
        assertThat(CacheStats.collectCacheMisses(entries, MODELS)).isEmpty();
        var totals = CacheStats.computeCacheWaste(entries, MODELS);
        assertThat(totals.missCount()).isZero();
        assertThat(totals.missedTokens()).isZero();
    }

    @Test
    void zeroCacheTurnOnNeverReportingProviderIsNotCounted() {
        // prev 从未报过缓存（reportedCache=false）⇒ 全零缓存的下一turn 什么都不说明。
        var entries = List.<Entry>of(
            assistant("m1", "anthropic", "claude-opus-4-6", 0, usage(200_000, 0, 0)),
            assistant("m2", "anthropic", "claude-opus-4-6", 60_000, usage(200_000, 0, 0)));
        assertThat(CacheStats.collectCacheMisses(entries, MODELS)).isEmpty();
    }

    @Test
    void missesAtOrBelowNoiseFloorAreNotCounted() {
        var atFloor = List.<Entry>of(
            assistant("m1", "anthropic", "claude-opus-4-6", 0, usage(0, 0, 2_024)),
            assistant("m2", "anthropic", "claude-opus-4-6", 1_000, usage(1_024, 1_000, 0)));
        assertThat(CacheStats.collectCacheMisses(atFloor, MODELS)).isEmpty();

        var aboveFloor = List.<Entry>of(
            assistant("m1", "anthropic", "claude-opus-4-6", 0, usage(0, 0, 2_025)),
            assistant("m2", "anthropic", "claude-opus-4-6", 1_000, usage(1_025, 1_000, 0)));
        assertThat(CacheStats.collectCacheMisses(aboveFloor, MODELS)).hasSize(1);
    }

    @Test
    void compactionAndBranchSummaryResetThePreviousRequest() {
        var entries = List.<Entry>of(
            assistant("m1", "anthropic", "claude-opus-4-6", 0, usage(0, 0, 100_000)),
            compaction("c1", 1_000),
            assistant("m2", "anthropic", "claude-opus-4-6", 2_000, usage(100_000, 0, 0)),
            branchSummary("b1", 3_000),
            assistant("m3", "anthropic", "claude-opus-4-6", 4_000, usage(100_000, 0, 0)));
        // m2/m3 都在复位之后：prev 缺席 ⇒ 不计（上下文是合法新内容，不是重计费）。
        assertThat(CacheStats.collectCacheMisses(entries, MODELS)).isEmpty();
    }

    @Test
    void modelSwitchIsNotExemptAndMarksModelChangedWithIdleDelta() {
        var entries = List.<Entry>of(
            assistant("m1", "anthropic", "claude-opus-4-6", 10_000, usage(0, 0, 100_000)),
            assistant("m2", "anthropic", "claude-sonnet-4-5", 400_000, usage(100_000, 0, 0)));
        var misses = CacheStats.collectCacheMisses(entries, MODELS);
        assertThat(misses).hasSize(1);
        var miss = misses.get(0).getValue();
        assertThat(miss.modelChanged()).isTrue();
        assertThat(miss.missedTokens()).isEqualTo(100_000);
        assertThat(miss.idleMs()).isEqualTo(390_000);
    }

    @Test
    void cacheWarmUsageEntryActsAsPreviousRequestAndAlwaysReportedCache() {
        var entries = List.<Entry>of(
            assistant("m1", "anthropic", "claude-opus-4-6", 0, usage(0, 0, 100_000)),
            cacheWarm("w1", 250_000, 100_000),
            assistant("m2", "anthropic", "claude-opus-4-6", 260_000, usage(100_000, 0, 0)));
        var misses = CacheStats.collectCacheMisses(entries, MODELS);
        assertThat(misses).hasSize(1);
        var miss = misses.get(0).getValue();
        assertThat(miss.modelChanged()).isFalse();
        // idleMs 从暖条目时刻起算（暖刷新了缓存项）。
        assertThat(miss.idleMs()).isEqualTo(10_000);
    }

    @Test
    void reportedCacheIsStickyAcrossZeroCacheTurns() {
        var entries = List.<Entry>of(
            assistant("m1", "anthropic", "claude-opus-4-6", 0, usage(0, 0, 100_000)),
            assistant("m2", "anthropic", "claude-opus-4-6", 1_000, usage(100_000, 0, 0)),
            assistant("m3", "anthropic", "claude-opus-4-6", 2_000, usage(100_000, 0, 0)));
        // m2 计数后 prev 推进且 reportedCache 粘住 ⇒ m3 仍计（缓存只读 provider 的连续全 miss）。
        assertThat(CacheStats.collectCacheMisses(entries, MODELS)).hasSize(2);
    }

    @Test
    void missedCostUsesPaidRateMinusCacheReadRate() {
        // paidTokens = input+cacheWrite = 100k，实付 0.15+0.3125 ⇒ 4.625e-6/token；
        // cache-read 价目 0.5/M（消息自身无 cacheRead ⇒ 查目录）⇒ 差 4.125e-6。
        var entries = List.<Entry>of(
            assistant("m1", "anthropic", "claude-opus-4-6", 0, usage(0, 0, 100_000)),
            assistant("m2", "anthropic", "claude-opus-4-6", 1_000,
                usageWithCost(50_000, 0, 50_000, 0.15, 0, 0.3125)));
        var miss = CacheStats.collectCacheMisses(entries, MODELS).get(0).getValue();
        assertThat(miss.missedTokens()).isEqualTo(100_000);
        assertThat(miss.missedCost()).isCloseTo(0.4125, within(1e-9));
    }

    @Test
    void computeCacheWasteAccumulatesTotalsAcrossMisses() {
        var entries = List.<Entry>of(
            assistant("m1", "anthropic", "claude-opus-4-6", 0, usage(0, 0, 100_000)),
            assistant("m2", "anthropic", "claude-opus-4-6", 1_000, usage(100_000, 0, 0)),
            assistant("m3", "anthropic", "claude-opus-4-6", 2_000, usage(80_000, 0, 0)));
        var totals = CacheStats.computeCacheWaste(entries, MODELS);
        assertThat(totals.missCount()).isEqualTo(2);
        assertThat(totals.missedTokens()).isEqualTo(180_000);
    }

    @Test
    void detectCacheMissJudgesAMessageNotYetInEntries() {
        var entries = List.<Entry>of(
            assistant("m1", "anthropic", "claude-opus-4-6", 0, usage(0, 0, 100_000)));
        var pending = assistant("m2", "anthropic", "claude-opus-4-6", 1_000,
            usage(100_000, 0, 0));
        var miss = CacheStats.detectCacheMiss(entries, pending.message() instanceof
            Message.AssistantMessage am ? am : null, MODELS);
        assertThat(miss).isNotNull();
        assertThat(miss.missedTokens()).isEqualTo(100_000);

        // 首 turn（entries 空）⇒ 不计。
        assertThat(CacheStats.detectCacheMiss(List.of(), pending.message() instanceof
            Message.AssistantMessage am ? am : null, MODELS)).isNull();
    }
}
