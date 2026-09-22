package com.pijava.ai.catalog;

import java.util.List;

import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;

/**
 * 模型的思考级别能力 —— pi {@code models.ts:922-955} 的两个函数的逐字移植。
 *
 * <p>放在 {@code catalog} 包（而非 {@code thinking}）是为了对齐 pi 的<b>归属</b>：
 * 这两个函数住在 pi 的 {@code models.ts}（模型目录模块），且它们要读 {@link ModelInfo}
 * —— 放进 {@code thinking} 会与 {@code ModelInfo → ThinkingLevelMap} 形成包级环。</p>
 *
 * <p><b>方言映射</b>：pi 的 {@code model.reasoning} 在 pi-java 上是
 * {@link ModelCapability#THINKING}（{@code docs/41 §1.4} 登记的形状差异 ——
 * pi 是模型元数据布尔，java 是能力集成员）。</p>
 */
public final class ModelThinkingLevels {

    private ModelThinkingLevels() {}

    /**
     * pi {@code models.ts:924-933} {@code getSupportedThinkingLevels}。
     *
     * <pre>{@code
     * if (!model.reasoning) return ["off"];
     * return EXTENDED.filter(level => {
     *     const mapped = model.thinkingLevelMap?.[level];
     *     if (mapped === null) return false;              // 显式不支持
     *     if (level === "xhigh" || level === "max") return mapped !== undefined;  // opt-in
     *     return true;
     * });
     * }</pre>
     *
     * <p>⇒ 普通 reasoning 模型默认支持 {@code off..high}；{@code xhigh}/{@code max}
     * <b>必须由目录显式给出映射</b>才算支持。</p>
     */
    public static List<ModelThinkingLevel> supported(ModelInfo model) {
        if (!model.capabilities().contains(ModelCapability.THINKING)) {
            return List.of(ModelThinkingLevel.off());
        }
        var map = model.thinkingLevelMap();
        return ModelThinkingLevel.extended().stream()
            .filter(level -> {
                if (map.explicitlyUnsupported(level)) {
                    return false;
                }
                // `mapped !== undefined` ≙ 键在场（pi 的 opt-in 判据）
                return !isOptIn(level) || map.hasEntry(level);
            })
            .toList();
    }

    /**
     * pi {@code models.ts:935-955} {@code clampThinkingLevel}。
     *
     * <p>可用集里没有 ⇒ <b>先向上找、再向下找</b>（顺序是 pi 的明文选择）；都不行 ⇒
     * {@code availableLevels[0] ?? "off"}。</p>
     */
    public static ModelThinkingLevel clamp(ModelInfo model, ModelThinkingLevel level) {
        var available = supported(model);
        if (available.contains(level)) {
            return level;
        }
        var ladder = ModelThinkingLevel.extended();
        int requested = ladder.indexOf(level);
        if (requested == -1) {
            return firstOrOff(available);
        }
        for (int i = requested; i < ladder.size(); i++) {
            if (available.contains(ladder.get(i))) {
                return ladder.get(i);
            }
        }
        for (int i = requested - 1; i >= 0; i--) {
            if (available.contains(ladder.get(i))) {
                return ladder.get(i);
            }
        }
        return firstOrOff(available);
    }

    /** pi 的 {@code availableLevels[0] ?? "off"} —— 整条梯子被标空时 {@code available} 会是空表。 */
    private static ModelThinkingLevel firstOrOff(List<ModelThinkingLevel> available) {
        return available.isEmpty() ? ModelThinkingLevel.off() : available.get(0);
    }

    /** {@code xhigh}/{@code max} 是 pi 的 opt-in 级（其余默认支持）。 */
    private static boolean isOptIn(ModelThinkingLevel level) {
        return level instanceof ModelThinkingLevel.Enabled e
            && (e.level() instanceof ThinkingLevel.XHigh || e.level() instanceof ThinkingLevel.Max);
    }
}
