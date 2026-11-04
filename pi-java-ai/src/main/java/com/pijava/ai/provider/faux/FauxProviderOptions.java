package com.pijava.ai.provider.faux;

import java.util.List;

/**
 * docs/20 §3.2：pi {@code RegisterFauxProviderOptions}（{@code providers/faux.ts:121-134}）
 * 的 Java 形状。
 *
 * @param api           协议判别字面量；{@code null} ≙ pi 的随机 {@code faux:<…>}
 * @param provider      provider 名；{@code null} ≙ {@code "faux"}
 * @param models        模型定义；空表 ≙ pi 的内置 {@code faux-1}
 * @param deferred      deferred 行为；{@code null} ≙ 默认（0 pending、无 pollAfterMs）
 * @param tokensPerSecond 流式节奏（每秒 token 数）；{@code null}／≤ 0 ≙ 不睡
 * @param tokenSize     工具参数与文本的随机块大小；{@code null} ≙ 默认 3／5
 */
public record FauxProviderOptions(
    String api,
    String provider,
    List<FauxModelDefinition> models,
    FauxDeferredOptions deferred,
    Double tokensPerSecond,
    FauxTokenSize tokenSize
) {
    /** Compact constructor that defensively copies the model list. */
    public FauxProviderOptions {
        models = models == null ? List.of() : List.copyOf(models);
    }

    /** 全部取 pi 默认。 */
    public static FauxProviderOptions defaults() {
        return new FauxProviderOptions(null, null, List.of(), null, null, null);
    }

    /** 只给模型的便捷构造（provider 名取 {@code faux}）。 */
    public static FauxProviderOptions withModels(FauxModelDefinition... models) {
        return new FauxProviderOptions(null, null, List.of(models), null, null, null);
    }
}
