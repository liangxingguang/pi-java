package com.pijava.ai.catalog;

import java.util.Locale;
import java.util.Optional;

/**
 * pi {@code types.ts:109} 的 {@code CacheRetention}：{@code "none" | "short" | "long"}。
 *
 * <p>它是 {@code StreamOptions} 上的**跨车道**请求选项（{@code types.ts:211}），不是某条车道
 * 私有的 —— pi 的 anthropic / bedrock / openai-completions / openai-responses /
 * openai-codex-responses / mistral 六条车道都读它。pi-java 目前只有 anthropic 车道在消费
 * （包 A-01），其余五条各自的读点见 {@code docs/54 §1.2} 的登记。</p>
 *
 * <p>⚠️ <b>为什么在 {@code catalog} 而不是 {@code api}</b>（{@code docs/54 §9 R2} 原写
 * {@code api}）：解析它的是 {@link CompatResolver}，而 {@code api} <b>已</b>依赖
 * {@code catalog}（{@code StreamRequest} 用 {@link ModelInfo}）⇒ 把本类型放 {@code api}
 * 会新增一条 {@code catalog → api} 的<b>包环</b>。改放这里与同类的词汇表类型
 * （{@link ModelCompat}、{@link MaxTokensField}）同处，且 {@code api} 并不需要它
 * （选项经 {@code ApiOptions.extra} 的字符串键过桥）。见 {@code docs/54 §12} 的实现偏离记录。</p>
 *
 * <p><b>缺省语义</b>（pi {@code anthropic-messages.ts:60-67} 的 {@code resolveCacheRetention}）：
 * 选项缺席时看 {@code PI_CACHE_RETENTION} 环境变量，**只有字面 {@code "long"} 触发长缓存**，
 * 其余一切（含未设、含任意其它串）都落到 {@link #SHORT}。这是 pi 的 {@code === "long"}
 * 判据，不是「非空即 long」。</p>
 */
public enum CacheRetention {
    /** 不发任何缓存断点。pi 的 {@code "none"}。 */
    NONE,
    /** 默认：发断点但不指定 TTL。pi 的 {@code "short"}。 */
    SHORT,
    /** 长缓存（Anthropic 上是 {@code ttl:"1h"}）。pi 的 {@code "long"}。 */
    LONG;

    /** pi 的线格字面量（环境变量也用这组值）。 */
    public String wireName() {
        return switch (this) {
            case NONE -> "none";
            case SHORT -> "short";
            case LONG -> "long";
        };
    }

    /**
     * 解析**选项侧**的线格值。
     *
     * <p>⚠️ 与 {@code ResponsesOptions.CacheRetention.parse} **刻意不同**：那里未知值回落到
     * {@code SHORT}，这里返回 {@link Optional#empty()}。区别在于**谁来决定回落** —— pi 的
     * TS 类型让「选项在场但取值非法」不可能发生，故 java 侧唯一忠实的选择是把非法值当作
     * <b>缺席</b>看待，交给 {@code PI_CACHE_RETENTION} 与最终缺省去定（{@code docs/54 §2 P2}）。
     * 若在这里就塌成 {@code SHORT}，一个错拼的 {@code "Long"} 会把环境变量静默屏蔽掉。</p>
     *
     * <p>本方法**不**用于环境变量：那条判据是 pi 的 {@code === "long"}（大小写敏感、不
     * trim），由 {@link CompatResolver#anthropicCacheControl} 逐字照抄。</p>
     */
    public static Optional<CacheRetention> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "none" -> Optional.of(NONE);
            case "short" -> Optional.of(SHORT);
            case "long" -> Optional.of(LONG);
            default -> Optional.empty();
        };
    }
}
