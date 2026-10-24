package com.pijava.coding.agent.export;

import java.util.Map;

import com.pijava.coding.agent.theme.Themes;

/**
 * HTML 导出的 {@code :root} CSS 变量生成（pi {@code export-html/index.ts}
 * {@code generateThemeVars}/{@code deriveExportColors} :42-127 逐字对位，
 * 批 21/docs/32 T12）。
 *
 * <p>java 模板变量名保持既有（{@code --page/--card/--user/…}），pi 令牌到变量
 * 的映射：page←exportPageBg、card←exportCardBg、user←userMessageBg、
 * accent←accent、text←text、muted←muted、error←error、success←success、
 * border←border、code←mdCode、heading←mdHeading；{@code export} 三节缺席时
 * page/card 从 userMessageBg 派生（pi 同——infoBg 在 java 模板无消费点，
 * 只派生不引用）。</p>
 */
final class ExportThemeVars {

    private ExportThemeVars() {}

    /** pi deriveExportColors 返回三元组。 */
    record Derived(String pageBg, String cardBg, String infoBg) {}

    /**
     * 生成 {@code :root { … }} 块。
     *
     * @param themeName 具名主题；{@code null} ⇒ 当前活跃主题
     */
    static String rootVars(String themeName) {
        Map<String, String> colors;
        try {
            colors = Themes.getResolvedThemeColors(themeName);
        } catch (RuntimeException e) {
            // pi generateThemeVars 不兜底——loadTheme 抛错即导出失败；java 导出是
            // 用户显式动作，主题坏了退回内建 dark 比炸掉导出好（窄差注记随 D125 族）。
            colors = Themes.getResolvedThemeColors("dark");
        }
        var export = Themes.getThemeExportColors(themeName);
        var userMessageBg = colors.getOrDefault("userMessageBg", "#343541");
        var derived = deriveExportColors(userMessageBg);

        return """
            :root {
              --page: %s; --card: %s; --user: %s;
              --accent: %s; --text: %s; --muted: %s;
              --error: %s; --success: %s; --border: %s;
              --code: %s; --heading: %s;
            }""".formatted(
            export.pageBg() instanceof String page ? page : derived.pageBg(),
            export.cardBg() instanceof String card ? card : derived.cardBg(),
            userMessageBg,
            colors.getOrDefault("accent", "#8abeb7"),
            colors.getOrDefault("text", "#d4d4d4"),
            colors.getOrDefault("muted", "#808080"),
            colors.getOrDefault("error", "#cc6666"),
            colors.getOrDefault("success", "#b5bd68"),
            colors.getOrDefault("border", "#505050"),
            colors.getOrDefault("mdCode", "#b5bd68"),
            colors.getOrDefault("mdHeading", "#f0c674"));
    }

    /** pi 的 RGB 三元组（parseColor 返回形）。 */
    private record Parsed(int r, int g, int b) {}

    /**
     * pi {@code parseColor}（:43-61 逐字）：{@code #RRGGBB} 或
     * {@code rgb(r,g,b)}；否则 null。
     */
    private static Parsed parseColor(String color) {
        if (color.matches("(?i)^#[0-9a-f]{6}$")) {
            return new Parsed(
                Integer.parseInt(color.substring(1, 3), 16),
                Integer.parseInt(color.substring(3, 5), 16),
                Integer.parseInt(color.substring(5, 7), 16));
        }
        var rgbMatch = java.util.regex.Pattern.compile(
            "^rgb\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)$").matcher(color);
        if (rgbMatch.find()) {
            return new Parsed(Integer.parseInt(rgbMatch.group(1)),
                Integer.parseInt(rgbMatch.group(2)), Integer.parseInt(rgbMatch.group(3)));
        }
        return null;
    }

    /**
     * pi {@code getLuminance}（:64-71 逐字——阈值常量是 {@code 0.03928}，与
     * WCAG 标准 0.04045 不同，照抄不改）。
     */
    private static double getLuminance(int r, int g, int b) {
        java.util.function.DoubleUnaryOperator toLinear = c -> {
            double s = c / 255;
            return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        };
        return 0.2126 * toLinear.applyAsDouble(r) + 0.7152 * toLinear.applyAsDouble(g)
            + 0.0722 * toLinear.applyAsDouble(b);
    }

    /** pi {@code adjustBrightness}（:73-78 逐字）：逐通道乘系数取整，产出 rgb() 串。 */
    private static String adjustBrightness(String color, double factor) {
        var parsed = parseColor(color);
        if (parsed == null) {
            return color;
        }
        java.util.function.IntUnaryOperator adjust =
            c -> (int) Math.min(255, Math.max(0, Math.round(c * factor)));
        return "rgb(" + adjust.applyAsInt(parsed.r()) + ", " + adjust.applyAsInt(parsed.g())
            + ", " + adjust.applyAsInt(parsed.b()) + ")";
    }

    /** pi {@code deriveExportColors}（:81-107 逐字）。 */
    static Derived deriveExportColors(String baseColor) {
        var parsed = parseColor(baseColor);
        if (parsed == null) {
            return new Derived("rgb(24, 24, 30)", "rgb(30, 30, 36)", "rgb(60, 55, 40)");
        }

        double luminance = getLuminance(parsed.r(), parsed.g(), parsed.b());
        boolean isLight = luminance > 0.5;

        if (isLight) {
            return new Derived(adjustBrightness(baseColor, 0.96), baseColor,
                "rgb(" + Math.min(255, parsed.r() + 10) + ", " + Math.min(255, parsed.g() + 5)
                    + ", " + Math.max(0, parsed.b() - 20) + ")");
        }
        return new Derived(adjustBrightness(baseColor, 0.7), adjustBrightness(baseColor, 0.85),
            "rgb(" + Math.min(255, parsed.r() + 20) + ", " + Math.min(255, parsed.g() + 15)
                + ", " + parsed.b() + ")");
    }
}
