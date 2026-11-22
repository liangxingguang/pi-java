package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.Usage;
import com.pijava.ai.api.CacheWarmingPolicy;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

/**
 * docs/16（L1 #6）：{@link CacheWarmer} 的**宿主适配器**——策略／经济学纯函数已迁至
 * ai 的公开面 {@link CacheWarmingPolicy}（pi
 * {@code packages/ai/src/cache-warming.ts:29-107}）；本类只剩 pi 在
 * {@code core/cache-warmer.ts} 自己承担的那一件：从分支算
 * {@code promptTokens}（:61-70），再交给 ai 的 {@code evaluate}。
 *
 * <p>拆出只为单文件 ≤500 行（评审 I2）；{@code CacheWarmer} 的
 * start/evaluate 是本类的调用点。</p>
 */
final class CacheWarmingEconomics {

    private CacheWarmingEconomics() {}

    /** pi :378-400 的宿主半边：查目录、算 prompt 量，再调 ai 的决策。 */
    static CacheWarmingPolicy.Decision evaluate(
            Function<ModelId<?>, Optional<ModelInfo>> modelInfo,
            ModelId<?> model, List<Entry> branch, CacheWarmingPolicy.Phase phase) {
        var info = modelInfo.apply(model).orElseGet(() -> ModelInfo.minimal(model));
        return CacheWarmingPolicy.evaluate(info, lastPromptTokens(branch), phase);
    }

    /** pi :61-70：分支上最近一次真实请求的 prompt 量（provider 上报口径）。 */
    private static long lastPromptTokens(List<Entry> entries) {
        for (var index = entries.size() - 1; index >= 0; index--) {
            if (entries.get(index) instanceof Entry.Message messageEntry
                    && messageEntry.message() instanceof Message.AssistantMessage assistant) {
                var usage = assistant.usage();
                // pi 假设 usage 恒在；缺失按 0 收（⇒ economics unavailable，安全方向）。
                return usage == null ? 0
                    : (long) (usage.input() + usage.cacheRead() + usage.cacheWrite());
            }
        }
        return 0;
    }
}
