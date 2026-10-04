package com.pijava.ai.protocol;

import java.util.Optional;
import java.util.OptionalInt;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.ThinkingConfigParam;

import com.pijava.ai.api.SimpleOptions;
import com.pijava.ai.api.TranscriptContext;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingBudgets;
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

    /** pi 的 {@code thinkingDisplay ?? "summarized"}（{@code :1167}）。pi-java 无用户面（§9 登记）。 */
    private static final String DEFAULT_DISPLAY = "summarized";

    /** pi {@code :1173} 的 {@code thinkingBudgetTokens || 1024} —— **0 会落到 1024**。 */
    private static final long FALLBACK_BUDGET = 1024L;

    /**
     * 车道级解析结果。
     *
     * @param thinking     要写进请求的 {@code thinking} 参数；<b>空 = 整个参数不发</b>
     * @param outputConfig adaptive 分支的 {@code output_config}（带 effort）；其余分支为空
     * @param maxTokens    调整后的 {@code max_tokens}；空 = 沿用调用方给的值
     *                     （pi 的 adaptive ／ 无 reasoning 两条分支都是 {@code {...base}}）
     */
    record Resolved(Optional<ThinkingConfigParam> thinking,
                    Optional<OutputConfig> outputConfig,
                    OptionalInt maxTokens) {}

    /**
     * pi {@code anthropic-messages.ts:858-904}（streamSimple 的分流）＋
     * {@code :1152-1180}（落线）的合并移植。
     *
     * <pre>{@code
     * // 分流（streamSimple）
     * if (!options?.reasoning)              → thinkingEnabled: false
     * if (compat.forceAdaptiveThinking)     → thinkingEnabled: true, effort: mapLevelToEffort(...)
     * else                                  → adjustMaxTokensForThinking(...) → thinkingEnabled: true,
     *                                          thinkingBudgetTokens: min(budget, max(0, maxTokens-1024))
     * // 落线（request build），全部在 `else if (model.reasoning)` 之内
     * thinkingEnabled === true  && adaptive → {type:"adaptive", display} (+ output_config.effort)
     * thinkingEnabled === true  && !adaptive→ {type:"enabled", budget_tokens: budget || 1024, display}
     * thinkingEnabled === false && map.off !== null → {type:"disabled"}
     * }</pre>
     *
     * <p>⚠️ <b>{@code supportsMidConvoEffort} 那条分支不在内</b>（{@code 原 docs/46 §3-D3}）：
     * 它要写 {@code thinking.block_binding}，而 {@code anthropic-java-core:2.52.0} 里
     * <b>没有任何 {@code BlockBinding} 类型</b>（实测 javap ＋ unzip 零命中）。</p>
     *
     * <p>包 A-10 前后这里变了两处：<b>① </b>{@code clampMaxTokensToContext} 现在**在**内
     * （pi {@code anthropic-messages.ts:896} 在 {@code adjustMaxTokensForThinking} **之后**
     * 再夹一次 —— 因为 adjust 可能**抬高**上限：{@code base + budget}）。故 {@code transcript}
     * 成为入参。<b>② </b>调用方给的 {@code baseMaxTokens} 在生产路径上**已经是漏斗解析过的
     * 夹取值**（{@code AbstractChatApi} → {@code SimpleOptions}），与 pi 的
     * {@code base.maxTokens} 同位；直接调 {@code buildParams} 的路径则拿回落值
     * （{@code SimpleOptions.maxTokensOrDefault}），同样与 pi 的
     * {@code options?.maxTokens ?? model.maxTokens} 同形。</p>
     *
     * @param baseMaxTokens 调用方给的输出上限；空 ≙ pi 的 {@code options.maxTokens ?? model.maxTokens}
     *                      （两条路在 {@code adjust} 里同值，见 {@link ThinkingBudgets#adjust}）
     * @param transcript    夹取要读的会话（pi 的 {@code context}，{@code :896}）
     */
    static Resolved resolve(ModelInfo model, ModelCompat compat, Optional<ThinkingLevel> reasoning,
                            OptionalInt baseMaxTokens, TranscriptContext transcript) {
        if (model == null || !model.capabilities().contains(ModelCapability.THINKING)) {
            return new Resolved(Optional.empty(), Optional.empty(), OptionalInt.empty());
        }
        if (reasoning.isEmpty()) {
            // pi :1179 —— `thinkingEnabled === false && model.thinkingLevelMap?.off !== null`
            return new Resolved(
                model.thinkingLevelMap().supportsExplicitOff()
                    ? Optional.of(disabledParam()) : Optional.empty(),
                Optional.empty(), OptionalInt.empty());
        }
        var level = reasoning.get();
        // ⚠️ `compat` 必须是**解析后**的（CompatResolver）—— 目录标注只给「覆盖」那一源，
        // 而 pi 的判据读的是「目录已烘进去」的值（原 docs/53 §4.1）。
        if (compat != null && compat.forceAdaptiveThinking()) {
            return new Resolved(Optional.of(adaptiveParam()),
                Optional.of(outputConfig(model, level)), OptionalInt.empty());
        }
        var adjusted = ThinkingBudgets.adjust(
            baseMaxTokens, model.maxOutputTokens(), level, ThinkingBudgets.DEFAULT);
        // pi :896 —— adjust 之后**再**夹一次（adjust 会把上限抬到 base + budget）。
        var maxTokens = SimpleOptions.clampMaxTokensToContext(model, transcript, adjusted.maxTokens());
        long room = Math.max(0L, (long) maxTokens - ThinkingBudgets.MIN_ANSWER_TOKENS);
        return new Resolved(
            Optional.of(enabledParam(Math.min(adjusted.thinkingBudget(), room))),
            Optional.empty(), OptionalInt.of(maxTokens));
    }

    /** pi {@code :1173} —— {@code {type:"enabled", budget_tokens, display}}。 */
    private static ThinkingConfigParam enabledParam(long budgetTokens) {
        return ThinkingConfigParam.ofEnabled(ThinkingConfigEnabled.builder()
            .type(JsonValue.from("enabled"))
            .budgetTokens(budgetTokens == 0 ? FALLBACK_BUDGET : budgetTokens)
            .display(ThinkingConfigEnabled.Display.SUMMARIZED)
            .build());
    }

    /** pi {@code :1165-1167} —— {@code {type:"adaptive", display}}。 */
    private static ThinkingConfigParam adaptiveParam() {
        return ThinkingConfigParam.ofAdaptive(ThinkingConfigAdaptive.builder()
            .type(JsonValue.from("adaptive"))
            .display(ThinkingConfigAdaptive.Display.SUMMARIZED)
            .build());
    }

    /** pi {@code :1179-1180} —— {@code {type:"disabled"}}。 */
    private static ThinkingConfigParam disabledParam() {
        return ThinkingConfigParam.ofDisabled(ThinkingConfigDisabled.builder()
            .type(JsonValue.from("disabled"))
            .build());
    }

    /**
     * adaptive 分支的 {@code output_config.effort}（pi {@code :1168-1170}）。
     *
     * <p>pi 只在 {@code options.effort} 有值时发；java 的 effort 恒由
     * {@link #mapLevelToEffort} 算出（pi 的 {@code streamSimple} 在 adaptive 分支一定给
     * effort，{@code :878-884}）⇒ 这里恒有值。</p>
     */
    private static OutputConfig outputConfig(ModelInfo model, ThinkingLevel level) {
        return OutputConfig.builder()
            .effort(OutputConfig.Effort.of(mapLevelToEffort(model, level)))
            .build();
    }
}
