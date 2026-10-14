package com.pijava.ai.protocol;

import java.util.Optional;
import java.util.OptionalInt;

import com.fasterxml.jackson.databind.node.ObjectNode;

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

    /** B99（pi {@code anthropic-messages.ts:190}）：managed effort 两 beta。 */
    static final String MID_CONVERSATION_OUTPUT_CONFIG_BETA =
        "mid-conversation-output-config-2026-07-01";
    static final String THINKING_BINDING_CONTROLS_BETA =
        "thinking-binding-controls-2026-08-01";

    /**
     * 车道级解析结果。
     *
     * @param thinking     要写进请求的<b>typed</b> {@code thinking} 参数；<b>空 = 不调
     *                     {@code builder.thinking()}</b>（managed 支走 {@link #rawThinking}）
     * @param outputConfig adaptive/managed 分支的 {@code output_config}（带 effort）
     * @param maxTokens    调整后的 {@code max_tokens}；空 = 沿用调用方给的值
     *                     （pi 的 adaptive ／ 无 reasoning 两条分支都是 {@code {...base}}）
     * @param rawThinking  B99 managed 支的原始 JSON {@code thinking} 树（含
     *                     {@code block_binding}，stable SDK 的 typed adaptive 无此字段）；
     *                     经 {@code putAdditionalBodyProperty("thinking", …)} 上线，
     *                     与 {@link #thinking} 互斥
     */
    record Resolved(Optional<ThinkingConfigParam> thinking,
                    Optional<OutputConfig> outputConfig,
                    OptionalInt maxTokens,
                    Optional<ObjectNode> rawThinking) {}

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
     * <p>B99（批 4）：{@code supportsMidConvoEffort} 顶支已接通 —— managed 模型无条件
     * adaptive＋{@code block_binding:{prefix_mismatch_behavior:"drop_block"}}（原始 JSON
     * 树经 {@code putAdditionalBodyProperty}，stable SDK 2.66.0 的 typed adaptive 仍无
     * blockBinding）＋顶层 {@code output_config:{effort:"high"}}，与是否开思考无关。</p>
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
                            OptionalInt baseMaxTokens, TranscriptContext transcript,
                            ThinkingBudgets budgets) {
        if (model == null || !model.capabilities().contains(ModelCapability.THINKING)) {
            return new Resolved(Optional.empty(), Optional.empty(), OptionalInt.empty(),
                Optional.empty());
        }
        // B99（pi :1235-1241）：managed effort 顶支在 reasoning 空判定**之前**，
        // 与 thinkingEnabled 无关 —— 无 reasoning 也恒发 adaptive＋block_binding＋
        // 顶层 output_config:{effort:"high"}，不抬 maxTokens、不吃 thinkingBudgets。
        if (compat != null && Boolean.TRUE.equals(compat.supportsMidConvoEffort())) {
            return new Resolved(Optional.empty(),
                Optional.of(outputConfigHigh()), OptionalInt.empty(),
                Optional.of(managedThinkingTree()));
        }
        if (reasoning.isEmpty()) {
            // pi :1179 —— `thinkingEnabled === false && model.thinkingLevelMap?.off !== null`
            return new Resolved(
                model.thinkingLevelMap().supportsExplicitOff()
                    ? Optional.of(disabledParam()) : Optional.empty(),
                Optional.empty(), OptionalInt.empty(), Optional.empty());
        }
        var level = reasoning.get();
        // ⚠️ `compat` 必须是**解析后**的（CompatResolver）—— 目录标注只给「覆盖」那一源，
        // 而 pi 的判据读的是「目录已烘进去」的值（原 docs/53 §4.1）。
        if (compat != null && compat.forceAdaptiveThinking()) {
            return new Resolved(Optional.of(adaptiveParam()),
                Optional.of(outputConfig(model, level)), OptionalInt.empty(),
                Optional.empty());
        }
        // B128：budget 表由请求通道带入（settings.thinkingBudgets）；null ≙ pi 的
        // undefined ⇒ 全默认表（simple-options.ts:72 的浅合并）。
        var adjusted = ThinkingBudgets.adjust(
            baseMaxTokens, model.maxOutputTokens(), level,
            ThinkingBudgets.orDefault(budgets));
        // pi :896 —— adjust 之后**再**夹一次（adjust 会把上限抬到 base + budget）。
        var maxTokens = SimpleOptions.clampMaxTokensToContext(model, transcript, adjusted.maxTokens());
        long room = Math.max(0L, (long) maxTokens - ThinkingBudgets.MIN_ANSWER_TOKENS);
        return new Resolved(
            Optional.of(enabledParam(Math.min(adjusted.thinkingBudget(), room))),
            Optional.empty(), OptionalInt.of(maxTokens), Optional.empty());
    }

    /**
     * B99/B110：managed 模型的 active marker effort 与盖章值的<b>同一个</b>函数
     * （pi 的 {@code options?.effort ?? "high"}，{@code anthropic-messages.ts:1154-1161}
     * 与 {@code :581} 共用）。
     *
     * <p>请求侧无 reasoning 时 options.effort 缺席 ⇒ {@code "high"}（managed 分支
     * 无 reasoning 也恒发顶层 high）；有值 ⇒ {@link #mapLevelToEffort}（含目录映射）。</p>
     */
    static String activeEffort(ModelInfo model, Optional<ThinkingLevel> reasoning) {
        return reasoning.<String>map(level -> mapLevelToEffort(model, level)).orElse("high");
    }

    /** 无 NON_NULL inclusion 的 mapper（与 ThinkingFormatWriter.PLAIN_JSON 同物）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper PLAIN_JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * B99（pi {@code :1236-1240}）：managed thinking 原始 JSON 树，键序逐字
     * {@code type, display, block_binding.prefix_mismatch_behavior}。stable SDK 的
     * {@code ThinkingConfigAdaptive} 无 blockBinding（javap 实测，只有 beta client
     * 类型有）⇒ 经 {@code putAdditionalBodyProperty("thinking", …)} 上线（裁决②）。
     */
    private static ObjectNode managedThinkingTree() {
        var tree = PLAIN_JSON.createObjectNode();
        tree.put("type", "adaptive");
        tree.put("display", DEFAULT_DISPLAY);
        var binding = tree.putObject("block_binding");
        binding.put("prefix_mismatch_behavior", "drop_block");
        return tree;
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

    /** B99：managed 支恒发 {@code output_config:{effort:"high"}}（pi {@code :1241}）。 */
    private static OutputConfig outputConfigHigh() {
        return OutputConfig.builder().effort(OutputConfig.Effort.HIGH).build();
    }
}
