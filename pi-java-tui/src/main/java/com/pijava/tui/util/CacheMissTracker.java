package com.pijava.tui.util;

import java.util.Optional;
import java.util.function.Function;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.CacheStats;

/**
 * 批 13（docs/24 T11，裁决⑤）：{@link CacheStats} 扫描状态机的<b>增量形</b>——
 * pi 的三公开函数都是整表单趟 scan（resume/重建一次跑完），java TUI 是增量
 * 条目流（onEntry 逐条），用同一状态机的逐条推进形；判定本体
 * （{@link CacheStats#detectMiss}/{@link CacheStats#asPreviousRequest}/
 * {@link CacheStats#cacheWarmAsPrevious}）单源不复制。
 *
 * <p>{@link #detectOnMessageEnd} 对位 pi {@code detectCacheMiss}（:168-174）的
 * 「entries 未含 message」语义：当前消息尚未落盘 ⇒ 用当前 prev 判一次。
 * <b>判定后推进 prev 到该消息</b>（评审 I1：pi 的整表 scan 看得到本 run 已落的
 * turn N-1——onEntry 在 java 是 run 末批量投递，不推进的话多 turn run 里
 * idleMs/missedTokens 都相对「上一 run 尾」冻结）；run 末 feed 投递同一实例
 * 时按身份跳过，防重复判定/重复推进。</p>
 *
 * <p>非线程安全：TUI 的条目流/事件都在渲染线程上消费（SessionEventChannel）。</p>
 */
public final class CacheMissTracker {

    private final Function<String, Optional<ModelInfo>> models;
    private CacheStats.PreviousRequest prev;
    /**
     * 已被 {@link #detectOnMessageEnd} 推进过的消息（<b>身份</b>集——一个 run 可有多
     * turn，feed 在 run 末按序投递时必须全数跳过，不是只跳最新一条）。
     */
    private final java.util.Set<Message.AssistantMessage> advancedMessages =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** @param models pi {@code ModelPriceSource} 的 java 形（provider+"/"+model ⇒ 目录）。 */
    public CacheMissTracker(Function<String, Optional<ModelInfo>> models) {
        this.models = models;
    }

    /** 目录查询缺席的测试形（missedCost 的 read 单价按 0 收）。 */
    public static CacheMissTracker noModels() {
        return new CacheMissTracker(key -> Optional.empty());
    }

    /** 整表重建前归零（pi 的每次 scan 新 prev 对位）。 */
    public void reset() {
        prev = null;
        advancedMessages.clear();
    }

    /**
     * 逐条推进 prev（pi scan :112-140 循环体的单条对位）：compaction/branch_summary
     * 复位；cache_warm 条目充 prev；assistant 消息判 miss 并推进（reportedCache 粘住）。
     *
     * @return assistant 消息上计到的 miss（其余条目恒空）
     */
    public Optional<CacheStats.CacheMiss> feed(Entry entry) {
        if (entry instanceof Entry.Compaction || entry instanceof Entry.BranchSummary) {
            prev = null;
            return Optional.empty();
        }
        if (entry instanceof Entry.Usage usageEntry
                && "cache_warm".equals(usageEntry.kind())) {
            var warmPrev = CacheStats.cacheWarmAsPrevious(usageEntry);
            prev = warmPrev != null ? warmPrev : prev;
            return Optional.empty();
        }
        if (entry instanceof Entry.Message messageEntry
                && messageEntry.message() instanceof Message.AssistantMessage assistant) {
            if (advancedMessages.contains(assistant)) {
                // 即时臂已判定并推进过（run 末投递同一实例）——不重复判定/推进。
                return Optional.empty();
            }
            var miss = CacheStats.detectMiss(prev, assistant, models);
            var advanced = CacheStats.asPreviousRequest(assistant,
                prev != null && prev.reportedCache());
            prev = advanced != null ? advanced : prev;
            return Optional.ofNullable(miss);
        }
        return Optional.empty();
    }

    /**
     * pi {@code detectCacheMiss}：对尚未落盘的刚完成消息用当前 prev 判一次，
     * <b>判定后推进 prev 到该消息</b>（pi scan 看得到本 run 已落的 turn 对位）；
     * {@code promptTokens ≤ 0} 的消息不成为 prev（{@code asPreviousRequest} 的
     * null 臂，scan 的 {@code ?? prev} 同）也就不记跳过标记——feed 它时
     * {@code detectMiss} 同样不计（零门），无重复面。
     */
    public Optional<CacheStats.CacheMiss> detectOnMessageEnd(
            Message.AssistantMessage message) {
        var miss = CacheStats.detectMiss(prev, message, models);
        var advanced = CacheStats.asPreviousRequest(message,
            prev != null && prev.reportedCache());
        if (advanced != null) {
            prev = advanced;
            advancedMessages.add(message);
        }
        return Optional.ofNullable(miss);
    }
}
