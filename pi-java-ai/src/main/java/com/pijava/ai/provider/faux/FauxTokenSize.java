package com.pijava.ai.provider.faux;

/**
 * docs/20 §3.2：pi {@code RegisterFauxProviderOptions.tokenSize}
 * （{@code providers/faux.ts:129-132}）的 Java 形状。
 *
 * <p>两端都可缺席（{@code null} ≙ pi 的 {@code undefined}，取默认 3／5）；
 * 归一规则见 {@link FauxChunking#of}。</p>
 *
 * @param min 随机下界（token 数），或 {@code null}
 * @param max 随机上界（token 数），或 {@code null}
 */
public record FauxTokenSize(Integer min, Integer max) {

    /** pi 默认 {@code {min: 3, max: 5}}。 */
    public static FauxTokenSize defaults() {
        return new FauxTokenSize(null, null);
    }

    /** 固定块大小的便捷构造（测试用 {@code (1, 1)}）。 */
    public static FauxTokenSize fixed(int size) {
        return new FauxTokenSize(size, size);
    }
}
