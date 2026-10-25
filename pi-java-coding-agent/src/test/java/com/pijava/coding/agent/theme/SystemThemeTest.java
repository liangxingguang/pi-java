package com.pijava.coding.agent.theme;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link SystemTheme} 测试（pi system-theme.test.ts 性质断言移植＋tier-3 golden，
 * 批 21/docs/32 §7）。
 */
class SystemThemeTest {

    private static ThemeColor.Rgb rgb(String hex) {
        return ThemeColorMath.colorToRgb(ThemeColorMath.parseColor(hex));
    }

    private static final SystemTheme.SystemThemeInput DRACULA = new SystemTheme.SystemThemeInput(
        rgb("#f8f8f2"), rgb("#282a36"),
        List.of("#21222c", "#ff5555", "#50fa7b", "#f1fa8c", "#bd93f9", "#ff79c6",
                "#8be9fd", "#f8f8f2", "#6272a4", "#ff6e6e", "#69ff94", "#ffffa5",
                "#d6acff", "#ff92df", "#a4ffff", "#ffffff")
            .stream().map(SystemThemeTest::rgb).toList(),
        null, null);

    private static final Map<String, SystemTheme.SystemThemeInput> TERMINALS = Map.of(
        "dracula", DRACULA,
        "solarizedLight", new SystemTheme.SystemThemeInput(
            rgb("#657b83"), rgb("#fdf6e3"), null, null, null),
        "backgroundOnly", new SystemTheme.SystemThemeInput(
            null, rgb("#1e1e1e"), null, null, null),
        "midGray", new SystemTheme.SystemThemeInput(
            rgb("#ffffff"), rgb("#808080"), null, null, null));

    private static final List<ThemeToken> PANELS = List.of(ThemeToken.USER_MESSAGE_BG,
        ThemeToken.TOOL_PENDING_BG, ThemeToken.TOOL_SUCCESS_BG, ThemeToken.TOOL_ERROR_BG,
        ThemeToken.SELECTED_BG);

    /** pi resolved()："" ⇒ 面板取背景、其余取前景。 */
    private ThemeColor.Rgb resolved(SystemTheme.SystemThemeInput input, ThemeToken token) {
        var value = SystemTheme.generateSystemThemeColors(input).colors().get(token);
        if ("".equals(value)) {
            return PANELS.contains(token) ? input.background() : input.foreground();
        }
        return value instanceof Number number
            ? ThemeColorMath.indexedToRgb(number.intValue()) : rgb((String) value);
    }

    @Test
    void keepsBodyTextReadableOnBackgroundAndPanels() {
        for (var entry : TERMINALS.entrySet()) {
            var input = entry.getValue();
            var text = resolved(input, ThemeToken.TEXT);
            for (var surface : List.of(input.background(),
                    resolved(input, ThemeToken.SELECTED_BG))) {
                assertThat(SystemTheme.wcagContrast(text, surface)).as(entry.getKey())
                    .isGreaterThanOrEqualTo(4.5);
            }
            assertThat(SystemTheme.wcagContrast(resolved(input, ThemeToken.TOOL_TITLE),
                resolved(input, ThemeToken.TOOL_ERROR_BG))).as(entry.getKey())
                .isGreaterThanOrEqualTo(4.5);
        }
    }

    @Test
    void ordersForegroundRolesByContrastAndKeepsPanelsClose() {
        for (var entry : TERMINALS.entrySet()) {
            var input = entry.getValue();
            double background = lightness(input.background());
            if (!entry.getKey().equals("midGray")) {
                assertThat(Math.abs(lightness(resolved(input, ThemeToken.TEXT)) - background))
                    .as(entry.getKey())
                    .isGreaterThan(Math.abs(lightness(resolved(input, ThemeToken.MUTED))
                        - background));
                assertThat(Math.abs(lightness(resolved(input, ThemeToken.MUTED)) - background))
                    .as(entry.getKey())
                    .isGreaterThan(Math.abs(lightness(resolved(input, ThemeToken.DIM))
                        - background));
            }
            boolean lighter = SystemTheme.generateSystemThemeColors(input).appearance()
                == ThemeAppearance.DARK;
            for (var panel : PANELS) {
                assertThat(SystemTheme.wcagContrast(resolved(input, panel), input.background()))
                    .as(entry.getKey() + " " + panel).isLessThan(2);
                assertThat(lightness(resolved(input, panel)) > background)
                    .as(entry.getKey() + " " + panel).isEqualTo(lighter);
            }
        }
    }

    private static double lightness(ThemeColor.Rgb color) {
        return ThemeColorMath.colorToOklch(
            ThemeColor.rgb(color.r(), color.g(), color.b())).l();
    }

    @Test
    void usesTerminalForegroundWhereReadable() {
        // pi：Dracula 前景足够强 ⇒ text 取终端默认（""）。
        assertThat(SystemTheme.generateSystemThemeColors(DRACULA).colors()
            .get(ThemeToken.TEXT)).isEqualTo("");
    }

    @Test
    void tier3IndexedColorsGolden() {
        // 无上报 ⇒ indexedColors tier-3：面板 ""、非 neutral 取 ANSI 槽位索引、
        // neutral 非正文令牌进 dim 表（pi :642-655 逐字对位）。
        var result = SystemTheme.generateSystemThemeColors(
            new SystemTheme.SystemThemeInput(null, null, null, null, ThemeAppearance.DARK));
        var colors = result.colors();
        assertThat(colors.get(ThemeToken.USER_MESSAGE_BG)).isEqualTo("");
        assertThat(colors.get(ThemeToken.SELECTED_BG)).isEqualTo("");
        assertThat(colors.get(ThemeToken.TEXT)).isEqualTo("");
        assertThat(colors.get(ThemeToken.ACCENT)).isEqualTo(5);   // violet 族 slot 5
        assertThat(colors.get(ThemeToken.ERROR)).isEqualTo(1);    // red 族 slot 1
        assertThat(colors.get(ThemeToken.SYNTAX_STRING)).isEqualTo(2);  // TOKEN_SLOTS 特判
        assertThat(colors.get(ThemeToken.SEARCH_MATCH_BG)).isEqualTo("");  // 面板
        assertThat(colors.get(ThemeToken.THINKING_XHIGH)).isEqualTo(13);
        assertThat(result.dim()).contains(ThemeToken.MUTED, ThemeToken.DIM,
            ThemeToken.BORDER_MUTED, ThemeToken.SCROLLBAR_TRACK);
        assertThat(result.dim()).doesNotContain(ThemeToken.TEXT,
            ThemeToken.USER_MESSAGE_TEXT, ThemeToken.TOOL_TITLE);
        assertThat(result.appearance()).isEqualTo(ThemeAppearance.DARK);
    }

    @Test
    void terminalAppearanceArms() {
        assertThat(SystemTheme.terminalAppearance(rgb("#000000"), null))
            .isEqualTo(ThemeAppearance.DARK);
        assertThat(SystemTheme.terminalAppearance(rgb("#ffffff"), null))
            .isEqualTo(ThemeAppearance.LIGHT);
    }

    @Test
    void midGrayRelaxesLevelsButKeepsTextContrast() {
        // pi：中灰触发松弛二分——最终 text 仍 ≥4.5。
        var input = TERMINALS.get("midGray");
        var result = SystemTheme.generateSystemThemeColors(input);
        var text = resolved(input, ThemeToken.TEXT);
        assertThat(SystemTheme.wcagContrast(text, input.background()))
            .isGreaterThanOrEqualTo(4.5);
        assertThat(result.appearance()).isEqualTo(ThemeAppearance.LIGHT);
    }

    @Test
    void builtinThemesParseAndResolve() {
        // 内建 dark/light 逐字拷贝的自洽性：全 56 令牌解析后齐全（fallback 填平）。
        var dark = Themes.getThemeByName("dark");
        assertThat(dark).isNotNull();
        assertThat(dark.colors()).hasSize(ThemeToken.values().length);
        assertThat(dark.appearance()).isEqualTo(ThemeAppearance.DARK);
        var light = Themes.getThemeByName("light");
        assertThat(light).isNotNull();
        assertThat(light.appearance()).isEqualTo(ThemeAppearance.LIGHT);
        // golden＝pi 锚点代码实跑值（tsx 直跑 colors.ts，批 21 取证）：
        // accent=vars.violet=okhsl(295 50% 67%) ⇒ #a798d7；
        // text=vars.text=okhsl(234 3% 89%) ⇒ #dee0e1；muted ⇒ #9da5a9。
        assertThat(ThemeColorMath.colorToHex(dark.colors().get(ThemeToken.ACCENT)))
            .isEqualTo("#a798d7");
        assertThat(ThemeColorMath.colorToHex(dark.colors().get(ThemeToken.TEXT)))
            .isEqualTo("#dee0e1");
        assertThat(ThemeColorMath.colorToHex(dark.colors().get(ThemeToken.MUTED)))
            .isEqualTo("#9da5a9");
    }

    @Test
    void colorsCacheInvalidatesOnTerminalColorsIdentity() {
        var theme = Themes.getThemeByName("dark");
        var before = theme.colors();
        Themes.setTerminalColors(new TerminalColors(
            new ThemeColor.Rgb(1, 2, 3), new ThemeColor.Rgb(4, 5, 6), null));
        try {
            var after = theme.colors();
            assertThat(after).isNotSameAs(before);
        } finally {
            Themes.setTerminalColors(TerminalColors.EMPTY);
        }
    }

    @Test
    void darkThemeFgAndDimSemantics() {
        var theme = Themes.getThemeByName("dark");
        assertThat(theme.fg(ThemeToken.ERROR, "x"))
            .startsWith("\033[").endsWith("x\033[39m");
        // 非 dim 令牌不带 SGR 2。
        assertThat(theme.fg(ThemeToken.ERROR, "x")).doesNotContain("\033[2m");
        assertThat(theme.bold("x")).isEqualTo("\033[1mx\033[22m");
        // 未知令牌不可能（enum 封闭）；dim 查询面：
        assertThat(theme.isDim(ThemeToken.TEXT)).isFalse();
        // 思考级别映射（pi :348-368）。
        assertThat(theme.thinkingBorderColor("max")).isNotNull();
        assertThat(theme.thinkingBorderColor("bogus"))
            .isNotNull(); // 回落 thinkingOff
        assertThat(lightnessWithin(theme, ThemeToken.THINKING_OFF, 0.3, 0.7)).isTrue();
    }

    private static boolean lightnessWithin(Theme theme, ThemeToken token,
                                           double low, double high) {
        var color = theme.colors().get(token);
        double l = ThemeColorMath.colorToOklch(color).l();
        return l >= low && l <= high;
    }

    @Test
    void varRefsCircularAndMissing() {
        var circular = ThemeJsonValidator.validate("t", parseLenient(
            "{\"name\":\"t\",\"vars\":{\"a\":\"b\",\"b\":\"a\"},\"colors\":{%s}}"
                .formatted(allRequired().replace("\"accent\":\"\"", "\"accent\":\"a\""))));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            Theme.createTheme(circular, TerminalColorMode.TRUECOLOR, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Circular variable reference detected");

        var missing = ThemeJsonValidator.validate("t", parseLenient(
            "{\"name\":\"t\",\"colors\":{%s}}"
                .formatted(allRequired().replace("\"accent\":\"\"", "\"accent\":\"nope\""))));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            Theme.createTheme(missing, TerminalColorMode.TRUECOLOR, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Variable reference not found: nope");
    }

    private static com.fasterxml.jackson.databind.JsonNode parseLenient(String json) {
        try {
            return com.pijava.coding.agent.core.Json.mapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 构造必填令牌全 "" 的 colors 对象文本。 */
    private static String allRequired() {
        var colors = new StringBuilder();
        for (var token : ThemeToken.values()) {
            if (token.fallback() == null) {
                if (colors.length() > 0) {
                    colors.append(',');
                }
                colors.append('"').append(token.piName()).append("\":\"\"");
            }
        }
        return colors.toString();
    }
}
