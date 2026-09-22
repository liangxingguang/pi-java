package com.pijava.ai.protocol;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;

/**
 * Anthropic 车道的思考翻译 —— pi {@code anthropic-messages.ts} 的两段逻辑。
 *
 * <p>拆成独立类而不是塞进 {@link AnthropicMessagesApi}：后者已 380+ 行，
 * 而这两段各自有完整的判据与出处。</p>
 */
final class AnthropicThinking {

    private AnthropicThinking() {}

    /**
     * pi {@code anthropic-messages.ts:838-856} {@code mapThinkingLevelToEffort}。
     *
     * <pre>{@code
     * const mapped = level ? model.thinkingLevelMap?.[level] : undefined;
     * if (typeof mapped === "string") return mapped as AnthropicEffort;
     * switch (level) {
     *     case "minimal": case "low": return "low";
     *     case "medium": return "medium";
     *     case "high": return "high";
     *     default: return "high";      // xhigh / max 也落这里
     * }
     * }</pre>
     *
     * <p>pi 的 {@code AnthropicEffort} 是 {@code "low"|"medium"|"high"|"xhigh"|"max"}
     * （{@code :177}）—— 映射值<b>不校验</b>，目录写什么就发什么。</p>
     *
     * <p>⚠️ {@code level ? ...} 那个守卫在 Java 上不需要：调用点是 adaptive 分支，
     * 而 {@code streamSimple} 已在 {@code !options?.reasoning} 时提前返回
     * （{@code :872-876}）⇒ 这里的级别恒非空。</p>
     */
    static String mapLevelToEffort(ModelInfo model, ThinkingLevel level) {
        var mapped = model.thinkingLevelMap().mapped(ModelThinkingLevel.of(level));
        if (mapped.isPresent()) {
            return mapped.get();
        }
        return switch (level) {
            case ThinkingLevel.Minimal(), ThinkingLevel.Low() -> "low";
            case ThinkingLevel.Medium() -> "medium";
            // pi 的 `default:` —— xhigh / max 没有自己的回退值
            case ThinkingLevel.High(), ThinkingLevel.XHigh(), ThinkingLevel.Max() -> "high";
        };
    }
}
