package com.pijava.tui.theme;

import java.util.Locale;

import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.theme.ThemeToken;

import dev.tamboui.style.Style;

/**
 * L7 W6（C15，pi {@code getThinkingBorderColor} 对位）：思考等级 → 编辑器边框色。
 *
 * <p>{@link ModelThinkingLevel#label()} 的 pi 字面量（off/minimal/low/medium/
 * high/xhigh/max）映射到 {@code ThemeToken.THINKING_*}，经
 * {@link TuiThemeBridge#fg} 取当前主题色（每帧现读）。</p>
 */
public final class ThinkingBorderPalette {

    private ThinkingBorderPalette() {}

    /**
     * 等级边框样式；{@code null} ⇒ off（pi {@code thinkingLevel ?? "off"}）。
     *
     * @param level 当前思考等级
     * @return 主题边框样式
     */
    public static Style forLevel(ModelThinkingLevel level) {
        String label = level == null ? "off" : level.label().toLowerCase(Locale.ROOT);
        return TuiThemeBridge.fg(token(label));
    }

    static ThemeToken token(String label) {
        return switch (label) {
            case "minimal" -> ThemeToken.THINKING_MINIMAL;
            case "low" -> ThemeToken.THINKING_LOW;
            case "medium" -> ThemeToken.THINKING_MEDIUM;
            case "high" -> ThemeToken.THINKING_HIGH;
            case "xhigh" -> ThemeToken.THINKING_XHIGH;
            case "max" -> ThemeToken.THINKING_MAX;
            default -> ThemeToken.THINKING_OFF;
        };
    }
}
