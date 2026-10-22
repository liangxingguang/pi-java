package com.pijava.coding.agent.core;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.Message;

/**
 * 批 13（docs/24 T2）：pi {@code core/cache-stats.ts}（175 行）全量逐字——
 * prompt 缓存 miss 统计：「上一轮 prompt 里有过、这一轮却没走 cache-read」的
 * token 即被重计费。
 *
 * <p>java 形差异（设计 §3 T2）：pi 的 {@code ModelPriceSource.getModel} ⇒
 * {@code Function<ModelKey, Optional<ModelInfo>>}（ModelKey＝{@code provider+"/"+model}
 * 字符串，装配点给 {@code catalog.find} 的适配）；{@code collectCacheMisses} 不用
 * Map 键身份，返回保序的 {@code List<Map.Entry<Entry.Message, CacheMiss>>}。</p>
 *
 * <p>{@link #detectMiss}／{@link #asPreviousRequest}／{@link PreviousRequest} 公开，
 * 供 TUI 的增量 tracker（裁决⑤，tui 模块跨包）复用同一判定状态机——判定逻辑
 * 不复制。</p>
 */
public final class CacheStats {

    /** pi :8——prompt 缓存 TTL：idle 间隙超过它就值得点名（Anthropic 默认 5 分钟）。 */
    public static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    /** pi :11——每 turn 不超过此量的 miss 属缓存断点粒度噪声。 */
    static final long NOISE_FLOOR_TOKENS = 1024;

    private CacheStats() {}

    /** pi :14-23：单条 assistant 消息上计到的一次缓存 miss。 */
    public record CacheMiss(long missedTokens, double missedCost, long idleMs,
                            boolean modelChanged) {}

    /** pi :25-30。 */
    public record CacheWasteTotals(long missedTokens, double missedCost, int missCount) {}

    /**
     * pi :38-48 的 {@code PreviousRequest}——扫描见到的上一个请求；它 prompt 里的
     * 内容都应已在缓存中。{@code timestampMs}＝epoch ms（pi 的数值 timestamp）。
     */
    public record PreviousRequest(long promptTokens, String modelKey, long timestampMs,
                                  boolean reportedCache) {}

    /**
     * pi :56-90 逐字：相对上一请求判定一条 assistant 消息的 miss。不计的情形：
     * 首 turn／复位后／从未报过缓存活动的 provider 上的零缓存 turn／噪声地板以下。
     */
    public static CacheMiss detectMiss(PreviousRequest prev, Message.AssistantMessage message,
                                       Function<String, Optional<ModelInfo>> models) {
        var usage = message.usage();
        if (usage == null) {
            return null;
        }
        var promptTokens = usage.input() + usage.cacheRead() + usage.cacheWrite();
        // 零缓存 turn 只在之前报过缓存活动时计数：cache-read-only provider 上那是
        // 全 miss；从不报缓存的 provider 上那什么都不说明。
        if (prev == null || promptTokens <= 0
                || (usage.cacheRead() + usage.cacheWrite() == 0 && !prev.reportedCache())) {
            return null;
        }

        var missedTokens = (long) (Math.min(prev.promptTokens(), promptTokens)
            - usage.cacheRead());
        if (missedTokens <= NOISE_FLOOR_TOKENS) {
            return null;
        }

        // 多付的钱＝missed token 按实付价（input/cacheWrite，含 write 溢价）而非
        // cache-read 价计。missed token 只会落在 input 或 cacheWrite 桶 ⇒ 实付价
        // 从本消息自己的 cost 拆账推。
        var cost = usage.cost();
        var paidTokens = usage.input() + usage.cacheWrite();
        var paidPerToken = paidTokens > 0 && cost != null
            ? (cost.input() + cost.cacheWrite()) / paidTokens : 0;
        double readPerToken;
        if (usage.cacheRead() > 0 && cost != null) {
            readPerToken = cost.cacheRead() / usage.cacheRead();
        } else {
            // pi :82：models.getModel(...)?.cost.cacheRead ?? 0（UNKNOWN 的 -1 价目按 0 收）。
            var rate = models.apply(message.provider() + "/" + message.model())
                .map(info -> info.pricing().cacheReadPrice()).orElse(-1.0);
            readPerToken = Math.max(0, rate) / 1_000_000;
        }

        return new CacheMiss(missedTokens,
            missedTokens * Math.max(0, paidPerToken - readPerToken),
            Math.max(0, message.timestamp().toEpochMilli() - prev.timestampMs()),
            !(message.provider() + "/" + message.model()).equals(prev.modelKey()));
    }

    /**
     * pi :92-102：assistant 消息 ⇒ 下一轮的 prev（{@code reportedCache} 粘住——
     * 区分「缓存只读 provider 的全 miss」与「provider 从不报缓存」）。
     * promptTokens ≤ 0 ⇒ {@code null}（pi 返回 undefined，调用方保留旧 prev）。
     */
    public static PreviousRequest asPreviousRequest(Message.AssistantMessage message,
                                                    boolean reportedCache) {
        var usage = message.usage();
        if (usage == null) {
            return null;
        }
        var promptTokens = usage.input() + usage.cacheRead() + usage.cacheWrite();
        if (promptTokens <= 0) {
            return null;
        }
        return new PreviousRequest((long) promptTokens,
            message.provider() + "/" + message.model(),
            message.timestamp().toEpochMilli(),
            reportedCache || usage.cacheRead() + usage.cacheWrite() > 0);
    }

    /**
     * pi :120-129：{@code cache_warm} 用量条目充 prev（{@code reportedCache} 恒
     * true——暖请求就是一次缓存活动）；promptTokens ≤ 0 ⇒ {@code null}（不动 prev）。
     * 公开供增量 tracker（裁决⑤）与 {@link #scan} 共用同一构造。
     */
    public static PreviousRequest cacheWarmAsPrevious(Entry.Usage entry) {
        var usage = entry.usage();
        var promptTokens = usage == null ? 0
            : usage.input() + usage.cacheRead() + usage.cacheWrite();
        if (promptTokens <= 0) {
            return null;
        }
        return new PreviousRequest((long) promptTokens,
            entry.provider() + "/" + entry.model(),
            entry.timestamp().toEpochMilli(), true);
    }

    /** pi :104-142 的单趟扫描（三公开函数共用）。 */
    private static Scan scan(List<Entry> entries, Function<String, Optional<ModelInfo>> models) {
        PreviousRequest prev = null;
        long missedTokens = 0;
        double missedCost = 0;
        var missCount = 0;
        var misses = new ArrayList<Map.Entry<Entry.Message, CacheMiss>>();

        for (var entry : entries) {
            if (entry instanceof Entry.Compaction || entry instanceof Entry.BranchSummary) {
                // 上下文合法变了：下一 turn 的 prompt 是新内容，不是重计费内容。
                // 模型切换不豁免：它把整份 prompt 重计费，应当计数。
                prev = null;
                continue;
            }
            if (entry instanceof Entry.Usage usageEntry
                    && "cache_warm".equals(usageEntry.kind())) {
                var warmPrev = cacheWarmAsPrevious(usageEntry);
                prev = warmPrev != null ? warmPrev : prev;
            } else if (entry instanceof Entry.Message messageEntry
                    && messageEntry.message() instanceof Message.AssistantMessage assistant) {
                var miss = detectMiss(prev, assistant, models);
                if (miss != null) {
                    missedTokens += miss.missedTokens();
                    missedCost += miss.missedCost();
                    missCount += 1;
                    misses.add(new AbstractMap.SimpleImmutableEntry<>(messageEntry, miss));
                }
                var advanced = asPreviousRequest(assistant,
                    prev != null && prev.reportedCache());
                prev = advanced != null ? advanced : prev;
            }
        }
        return new Scan(prev, new CacheWasteTotals(missedTokens, missedCost, missCount), misses);
    }

    private record Scan(PreviousRequest prev, CacheWasteTotals totals,
                        List<Map.Entry<Entry.Message, CacheMiss>> misses) {}

    /**
     * pi :148-150：会话级累计缓存浪费——本应走 cache-read（在上一轮 prompt 里）
     * 却被重计费的 prompt token。
     */
    public static CacheWasteTotals computeCacheWaste(List<Entry> entries,
            Function<String, Optional<ModelInfo>> models) {
        return scan(entries, models).totals();
    }

    /**
     * pi :157-162：全会话所有计数的 miss，以付费的 assistant 消息条目为键
     * （重建聊天时重推导通知——resume／压缩后重建）。java 形：保序条目对列表
     * （设计 §3 T2，不用 Map 键身份）。
     */
    public static List<Map.Entry<Entry.Message, CacheMiss>> collectCacheMisses(
            List<Entry> entries, Function<String, Optional<ModelInfo>> models) {
        return scan(entries, models).misses();
    }

    /**
     * pi :168-174：判定一条<b>刚完成</b>的 assistant 消息——{@code entries} 必须
     * 尚未包含它（message_end 先于落盘发射）。
     */
    public static CacheMiss detectCacheMiss(List<Entry> entries,
            Message.AssistantMessage message, Function<String, Optional<ModelInfo>> models) {
        return detectMiss(scan(entries, models).prev(), message, models);
    }
}
