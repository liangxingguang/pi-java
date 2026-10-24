package com.pijava.coding.agent.theme;

/**
 * 主题设置解析与终端外观判定（pi {@code theme.ts:576-641} 逐字，批 21/docs/32 T7）。
 */
public final class ThemeSettings {

    private ThemeSettings() {}

    /** 自动模式设置值：{@code "<light>/<dark>"}。 */
    public record AutoThemeSetting(String lightTheme, String darkTheme) {}

    /**
     * pi {@code parseAutoThemeSetting}（:578-593 逐字）：恰好一个 {@code /} 且
     两半非空（trim 后）才是自动模式对；否则 null。
     */
    public static AutoThemeSetting parseAutoThemeSetting(String themeSetting) {
        if (themeSetting == null || themeSetting.isEmpty()) {
            return null;
        }
        int slashIndex = themeSetting.indexOf('/');
        if (slashIndex == -1 || themeSetting.indexOf('/', slashIndex + 1) != -1) {
            return null;
        }
        var lightTheme = themeSetting.substring(0, slashIndex).trim();
        var darkTheme = themeSetting.substring(slashIndex + 1).trim();
        if (lightTheme.isEmpty() || darkTheme.isEmpty()) {
            return null;
        }
        return new AutoThemeSetting(lightTheme, darkTheme);
    }

    /**
     * pi {@code resolveThemeSetting}（:595-606 逐字）：自动对 ⇒ 按终端外观取半；
     含 {@code /} 的畸形值 ⇒ null；其余字符串原样。
     */
    public static String resolveThemeSetting(String themeSetting, ThemeAppearance terminalTheme) {
        var autoTheme = parseAutoThemeSetting(themeSetting);
        if (autoTheme != null) {
            return terminalTheme == ThemeAppearance.LIGHT
                ? autoTheme.lightTheme() : autoTheme.darkTheme();
        }
        if (themeSetting != null && themeSetting.contains("/")) {
            return null;
        }
        return themeSetting;
    }

    /**
     * pi {@code detectColorFgBgTheme}（:615-621 逐字注释语义）：从某些终端设置的
     * {@code COLORFGBG} 环境变量判明暗——值是 {@code fg;bg} 或 {@code fg;xpm;bg}
     * （rxvt），字段是 ANSI 色索引或 {@code default}。索引指终端自有调色板（此处
     * 颜色未知），像 Vim 一样按索引分类：0-6 与 8（亮黑，如 Solarized Dark 的
     * 背景）为 dark，7 与 9-15 为 light。
     *
     * @return 无可用的背景索引时 null
     */
    public static ThemeAppearance detectColorFgBgTheme() {
        return detectColorFgBgTheme(System.getenv("COLORFGBG"));
    }

    /** 测试缝：显式给 {@code COLORFGBG} 值。 */
    static ThemeAppearance detectColorFgBgTheme(String colorFgBg) {
        if (colorFgBg == null) {
            return null;
        }
        var parts = colorFgBg.split(";");
        var bg = parts[parts.length - 1].trim();
        if (!bg.matches("\\d{1,2}")) {
            return null;
        }
        int index = Integer.parseInt(bg);
        if (index > 15) {
            return null;
        }
        return index <= 6 || index == 8 ? ThemeAppearance.DARK : ThemeAppearance.LIGHT;
    }

    /**
     * pi {@code detectTerminalTheme}（:628-636 逐字）：终端明暗——以它渲染的背景
     为准（分类法与 system 主题相同）。无上报背景：终端明暗上报 ⇒ COLORFGBG ⇒
     dark。
     */
    public static ThemeAppearance detectTerminalTheme(TerminalColors colors,
                                                      ThemeAppearance reportedScheme) {
        if (colors != null && colors.background() != null) {
            return SystemTheme.terminalAppearance(colors.background(), colors.foreground());
        }
        return reportedScheme != null ? reportedScheme
            : java.util.Objects.requireNonNullElse(detectColorFgBgTheme(), ThemeAppearance.DARK);
    }
}
