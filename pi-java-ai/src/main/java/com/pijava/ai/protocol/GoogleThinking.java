package com.pijava.ai.protocol;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import com.google.genai.types.ThinkingConfig;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;

/**
 * Google 车道的思考配置换算 —— pi {@code api/google-shared.ts:36-111} 的六件套 ＋
 * {@code api/google-generative-ai.ts:430-471} 的 {@code getGoogleBudget} 的逐字移植
 * （包 B155，{@code docs/10}）。
 *
 * <p>两条独立的概念，别混：</p>
 * <ul>
 *   <li>{@link #usesThinkingLevel} 只选<b>线格形状</b>（{@code thinkingLevel} 还是
 *       {@code thinkingBudget}），与「模型支持哪些级别」无关（pi 的注释明写）。</li>
 *   <li>{@link #budget} 的**三张表是 Google 专属**，与
 *       {@link com.pijava.ai.thinking.ThinkingBudgets}（pi 的 {@code simple-options}
 *       通用表）**不是同一张表** —— 混用会发错值（{@code docs/10 §5 J1}）。</li>
 * </ul>
 *
 * <p>⚠️ {@link #resolve} 在映射值不合四级时**抛**（pi 的 {@code default:} 明文选择），
 * 抛点在**请求构建期**（{@code buildConfig}）⇒ 用户看到的是构建失败而非流中断，与 pi 同。</p>
 */
final class GoogleThinking {

    private GoogleThinking() {}

    /**
     * pi {@code google-shared.ts:37} 的 {@code ResolvedGoogleThinkingLevel}
     * （{@code Exclude<ThinkingLevel, "xhigh"|"max">}）—— Java 上用 4 值闭集表达，
     * 把「不可能出现 xhigh/max」变成**类型保证**。
     */
    enum Level {
        MINIMAL, LOW, MEDIUM, HIGH;

        /** pi {@code google-shared.ts:85-89} {@code toGoogleThinkingLevel}（字面量同名）。 */
        String apiName() {
            return name();
        }
    }

    /** pi {@code :78} —— {@code /gemini-3(?:\.\d+)?-(?:pro|flash)/}。 */
    private static final Pattern GEMINI3_PRO_FLASH =
        Pattern.compile("gemini-3(?:\\.\\d+)?-(?:pro|flash)");

    /** pi {@code :82} —— {@code /gemma-?4/}（两种拼法）。 */
    private static final Pattern GEMMA4 = Pattern.compile("gemma-?4");

    /**
     * pi {@code google-shared.ts:72-84} {@code usesGoogleThinkingLevel} ——
     * 「这个模型用 Gemini 的离散 {@code thinkingLevel} 控制，而不是 token 的
     * {@code thinkingBudget}」。
     */
    static boolean usesThinkingLevel(ModelInfo model) {
        var id = model.id().modelName().toLowerCase(Locale.ROOT);
        return GEMINI3_PRO_FLASH.matcher(id).find()
            || id.equals("gemini-flash-latest")
            || id.equals("gemini-flash-lite-latest")
            || GEMMA4.matcher(id).find();
    }

    /**
     * pi {@code google-shared.ts:48-67} {@code resolveGoogleThinkingLevel} ——
     * 目录映射优先（**小写化**后比对），否则用级别自身的字面量；都不合四级 ⇒ 抛。
     */
    static Level resolve(ModelInfo model, ThinkingLevel level) {
        var mapped = model.thinkingLevelMap().mapped(ModelThinkingLevel.of(level));
        var resolved = mapped.map(value -> value.toLowerCase(Locale.ROOT))
            .orElse(level.label());
        return switch (resolved) {
            case "minimal" -> Level.MINIMAL;
            case "low" -> Level.LOW;
            case "medium" -> Level.MEDIUM;
            case "high" -> Level.HIGH;
            default -> throw new IllegalStateException(
                "Unsupported Google thinking level mapping for " + model.id().provider()
                    + "/" + model.id().modelName() + ": " + level.label()
                    + " -> " + mapped.orElse("undefined"));
        };
    }

    /**
     * pi {@code google-shared.ts:101-111} {@code getDisabledGoogleThinkingConfig} ——
     * 关思考时的配置。⚠️ 返回的对象里**没有** {@code includeThoughts}（pi 只在
     * 开思考那一支设 true，{@code google-generative-ai.ts:403}）。
     *
     * <p>非关卡型 ⇒ {@code {thinkingBudget: 0}}；关卡型且 off 可用 ⇒ 同样；关卡型但
     * <b>off 被显式禁用</b> ⇒ 夹出最低可用级别，发 {@code {thinkingLevel: <最低>}}。</p>
     */
    static Optional<ThinkingConfig> disabledConfig(ModelInfo model) {
        if (!model.capabilities().contains(ModelCapability.THINKING)) {
            return Optional.empty();
        }
        if (!usesThinkingLevel(model)) {
            return Optional.of(ThinkingConfig.builder().thinkingBudget(0).build());
        }
        var fallback = com.pijava.ai.catalog.ModelThinkingLevels.clamp(
            model, ModelThinkingLevel.off());
        if (fallback instanceof ModelThinkingLevel.Off) {
            return Optional.of(ThinkingConfig.builder().thinkingBudget(0).build());
        }
        var level = ((ModelThinkingLevel.Enabled) fallback).level();
        return Optional.of(ThinkingConfig.builder()
            .thinkingLevel(resolve(model, level).apiName())
            .build());
    }

    /**
     * pi {@code google-generative-ai.ts:430-471} {@code getGoogleBudget} ——
     * **三张 Google 专属表** ＋ {@code -1} 兜底（Google 语义里 {@code -1} ＝动态思考）。
     *
     * <p>⚠️ pi 的 {@code customBudgets?.[level]} 那一支（{@code options.thinkingBudgets}）
     * 本仓**无选项通道**（{@code 原 docs/57 §6 R7} 登记）⇒ 不写死，见 {@code docs/10 §5 J1}。</p>
     */
    static int budget(ModelInfo model, Level level) {
        var id = model.id().modelName();
        if (id.contains("2.5-pro")) {
            return switch (level) {
                case MINIMAL -> 128;
                case LOW -> 2048;
                case MEDIUM -> 8192;
                case HIGH -> 32768;
            };
        }
        if (id.contains("2.5-flash-lite")) {
            return switch (level) {
                case MINIMAL -> 512;
                case LOW -> 2048;
                case MEDIUM -> 8192;
                case HIGH -> 24576;
            };
        }
        if (id.contains("2.5-flash")) {
            return switch (level) {
                case MINIMAL -> 128;
                case LOW -> 2048;
                case MEDIUM -> 8192;
                case HIGH -> 24576;
            };
        }
        return -1;
    }
}
