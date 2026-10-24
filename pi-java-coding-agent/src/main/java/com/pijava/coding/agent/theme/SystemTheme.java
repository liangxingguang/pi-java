package com.pijava.coding.agent.theme;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code system} 主题：pi 的颜色从终端自身主题推导（pi {@code system-theme.ts}
 * 655 行逐字移植，批 21/docs/32 T6）。
 *
 * <p>每个令牌属一个色族（定色相）并有对比度规则：在背景上、在它被绘制到的
 * 面板上必须达到某对比度等级。色相与饱和度取自终端调色板中该族 ANSI 槽位
 * 的颜色，终端没报调色板时用族自己的色相。亮度全由规则决定。颜色用 OKHSL
 * 构造（饱和度相对于 sRGB 色域），近黑近白处向灰收敛。调色板颜色移到别的
 * 亮度时 OKLCH 彩度不增（pastel 调色板保持 pastel）。</p>
 *
 * <p>对比度等级是一条目标亮度曲线：给定下面的表面的 OKLab 亮度，给出令牌
 * 需要的 OKLab 亮度。曲线拟合自 "Pi themes: system and light/dark" 评审的参考
 * 主题设计。</p>
 *
 * <p><b>裁决③（窄差 D123）</b>：java 侧终端色输入恒空 ⇒ 实际恒走
 * {@link #indexedColors} tier-3；其余层结构全留（注入口在
 * {@link Themes#setTerminalColors}）。</p>
 */
public final class SystemTheme {

    private SystemTheme() {}

    /** pi {@code SYSTEM_THEME_NAME}（保留名：同名自定义主题被忽略）。 */
    public static final String SYSTEM_THEME_NAME = "system";

    // ============================================================================
    // 配方：色族与令牌归属（pi :41-131）
    // ============================================================================

    /** 一个色族的 OKHSL 色相与饱和度：中亮度取 max，向黑白两端收敛到 min。 */
    private record Family(double hue, double satMin, double satMax, int slot) {}

    private static final Map<String, Family> FAMILIES = Map.ofEntries(
        Map.entry("neutral", new Family(231.49, 0.02, 0.08, 8)),
        Map.entry("blue", new Family(231.49, 0.1, 0.68, 4)),
        Map.entry("green", new Family(158.68, 0.1, 0.76, 2)),
        Map.entry("red", new Family(20, 0.1, 0.92, 1)),
        Map.entry("yellow", new Family(82.36, 0.5, 1, 3)),
        Map.entry("orange", new Family(52, 0.12, 0.85, 3)),
        Map.entry("violet", new Family(295, 0.2, 0.6, 5)),
        Map.entry("calamine", new Family(202.43, 0.1, 0.74, 6)),
        Map.entry("thinkingSlate", new Family(231.49, 0.08, 0.2, 4)),
        Map.entry("thinkingBlue", new Family(231.49, 0.2, 0.45, 4)),
        Map.entry("thinkingPeriwinkle", new Family(263.25, 0.3, 0.6, 6)),
        Map.entry("thinkingViolet", new Family(295, 0.4, 0.75, 5)),
        Map.entry("thinkingMagenta", new Family(337.5, 0.5, 0.85, 13)),
        Map.entry("thinkingRed", new Family(20, 0.95, 1, 1)));

    private static final Map<ThemeToken, String> TOKEN_FAMILIES = new EnumMap<>(Map.ofEntries(
        Map.entry(ThemeToken.SELECTED_BG, "blue"),
        Map.entry(ThemeToken.SEARCH_MATCH_BG, "orange"),
        Map.entry(ThemeToken.USER_MESSAGE_BG, "blue"),
        Map.entry(ThemeToken.CUSTOM_MESSAGE_BG, "violet"),
        Map.entry(ThemeToken.TOOL_PENDING_BG, "neutral"),
        Map.entry(ThemeToken.TOOL_SUCCESS_BG, "green"),
        Map.entry(ThemeToken.TOOL_ERROR_BG, "red"),
        Map.entry(ThemeToken.TEXT, "neutral"),
        Map.entry(ThemeToken.USER_MESSAGE_TEXT, "neutral"),
        Map.entry(ThemeToken.CUSTOM_MESSAGE_TEXT, "neutral"),
        Map.entry(ThemeToken.TOOL_TITLE, "neutral"),
        Map.entry(ThemeToken.SYNTAX_OPERATOR, "neutral"),
        Map.entry(ThemeToken.SYNTAX_PUNCTUATION, "neutral"),
        Map.entry(ThemeToken.MUTED, "neutral"),
        Map.entry(ThemeToken.DIM, "neutral"),
        Map.entry(ThemeToken.THINKING_TEXT, "neutral"),
        Map.entry(ThemeToken.TOOL_OUTPUT, "neutral"),
        Map.entry(ThemeToken.MD_LINK_URL, "neutral"),
        Map.entry(ThemeToken.MD_QUOTE, "neutral"),
        Map.entry(ThemeToken.MD_QUOTE_BORDER, "neutral"),
        Map.entry(ThemeToken.MD_HR, "neutral"),
        Map.entry(ThemeToken.MD_CODE_BLOCK_BORDER, "neutral"),
        Map.entry(ThemeToken.TOOL_DIFF_CONTEXT, "neutral"),
        Map.entry(ThemeToken.SYNTAX_COMMENT, "neutral"),
        Map.entry(ThemeToken.SCROLLBAR_TRACK, "neutral"),
        Map.entry(ThemeToken.SCROLLBAR_THUMB, "neutral"),
        Map.entry(ThemeToken.SEARCH_MATCH_TEXT, "neutral"),
        Map.entry(ThemeToken.BORDER_MUTED, "neutral"),
        Map.entry(ThemeToken.ACCENT, "violet"),
        Map.entry(ThemeToken.BORDER_ACCENT, "violet"),
        Map.entry(ThemeToken.CUSTOM_MESSAGE_LABEL, "violet"),
        Map.entry(ThemeToken.MD_CODE, "violet"),
        Map.entry(ThemeToken.MD_LIST_BULLET, "violet"),
        Map.entry(ThemeToken.SYNTAX_TYPE, "violet"),
        Map.entry(ThemeToken.BORDER, "blue"),
        Map.entry(ThemeToken.MD_LINK, "blue"),
        Map.entry(ThemeToken.SYNTAX_KEYWORD, "blue"),
        Map.entry(ThemeToken.SYNTAX_VARIABLE, "calamine"),
        Map.entry(ThemeToken.SUCCESS, "green"),
        Map.entry(ThemeToken.MD_CODE_BLOCK, "green"),
        Map.entry(ThemeToken.TOOL_DIFF_ADDED, "green"),
        Map.entry(ThemeToken.BASH_MODE, "green"),
        Map.entry(ThemeToken.SYNTAX_NUMBER, "green"),
        Map.entry(ThemeToken.ERROR, "red"),
        Map.entry(ThemeToken.TOOL_DIFF_REMOVED, "red"),
        Map.entry(ThemeToken.WARNING, "yellow"),
        Map.entry(ThemeToken.MD_HEADING, "yellow"),
        Map.entry(ThemeToken.SYNTAX_FUNCTION, "yellow"),
        Map.entry(ThemeToken.SYNTAX_STRING, "orange"),
        Map.entry(ThemeToken.THINKING_OFF, "neutral"),
        Map.entry(ThemeToken.THINKING_MINIMAL, "thinkingSlate"),
        Map.entry(ThemeToken.THINKING_LOW, "thinkingBlue"),
        Map.entry(ThemeToken.THINKING_MEDIUM, "thinkingPeriwinkle"),
        Map.entry(ThemeToken.THINKING_HIGH, "thinkingViolet"),
        Map.entry(ThemeToken.THINKING_XHIGH, "thinkingMagenta"),
        Map.entry(ThemeToken.THINKING_MAX, "thinkingRed")));

    /** 与相似令牌共享色相的令牌的调色板槽位特判（pi :131）。 */
    private static final Map<ThemeToken, Integer> TOKEN_SLOTS = Map.of(
        ThemeToken.SYNTAX_STRING, 2, ThemeToken.SYNTAX_NUMBER, 5, ThemeToken.SEARCH_MATCH_BG, 3);

    // ============================================================================
    // 对比度等级与规则（pi :137-365）
    // ============================================================================

    /**
     * 目标亮度曲线：表面 OKLab 亮度的多项式，给出令牌在其上需要的 OKLab 亮度。
     * {@code reachable} 是该等级可达的表面亮度区间；越界则放宽。
     */
    private record Curve(double[] coefficients, double reachableLow, double reachableHigh) {}

    private static Curve curve(double c0, double c1, double c2, double c3, double c4, double c5,
                               double low, double high) {
        return new Curve(new double[] {c0, c1, c2, c3, c4, c5}, low, high);
    }

    private record LevelPair(Curve dark, Curve light) {}

    private static final Map<String, LevelPair> LEVELS = Map.ofEntries(
        Map.entry("panel", new LevelPair(
            curve(0.29131, -0.39746, 2.33185, -0.85524, -1.2076, 0.86276, 0, 0.979),
            curve(-3.74073, 27.94549, -78.44258, 112.6798, -79.60015, 22.11277, 0.348, 1))),
        Map.entry("track", new LevelPair(
            curve(0.39028, -0.23015, 0.83573, 2.43829, -4.38292, 2.01582, 0, 0.946),
            curve(-5.24921, 38.37322, -107.28833, 152.10005, -106.17127, 29.18061, 0.368, 1))),
        Map.entry("thinking0", new LevelPair(
            curve(0.52988, -0.05809, -0.30924, 4.63567, -6.52933, 2.89108, 0, 0.873),
            curve(-28.27749, 182.85284, -469.62416, 603.15916, -384.59976, 97.35147, 0.51, 1))),
        Map.entry("thinking1", new LevelPair(
            curve(0.55278, -0.03667, -0.45659, 4.95347, -6.90265, 3.0706, 0, 0.858),
            curve(-37.10484, 235.86282, -596.62344, 754.3633, -474.00763, 118.3551, 0.535, 1))),
        Map.entry("thinking2", new LevelPair(
            curve(0.57486, -0.01765, -0.58987, 5.25227, -7.27175, 3.25532, 0, 0.842),
            curve(-59.89653, 377.05024, -945.07843, 1182.03145, -734.96375, 181.68658, 0.556, 1))),
        Map.entry("thinking3", new LevelPair(
            curve(0.59621, -0.00062, -0.71148, 5.53588, -7.6392, 3.44606, 0, 0.827),
            curve(-72.07122, 445.84082, -1099.57352, 1353.88793, -829.53392, 202.26164, 0.58, 1))),
        Map.entry("thinking4", new LevelPair(
            curve(0.61691, 0.01462, -0.82288, 5.80651, -8.00641, 3.64333, 0, 0.811),
            curve(-110.14338, 674.21488, -1645.75941, 2004.32367, -1215.15899, 293.3183, 0.6, 1))),
        Map.entry("thinking5", new LevelPair(
            curve(0.63702, 0.02826, -0.92498, 6.06465, -8.37246, 3.84651, 0, 0.795),
            curve(-175.47701, 1063.54495, -2570.70594, 3098.80776, -1860.15527, 444.76392, 0.62, 1))),
        Map.entry("thinking6", new LevelPair(
            curve(0.65658, 0.04044, -1.01835, 6.30989, -8.73529, 4.05439, 0, 0.779),
            curve(-183.81712, 1094.70055, -2602.68539, 3088.71276, -1826.91131, 430.75931, 0.643, 1))),
        Map.entry("subtle", new LevelPair(
            curve(0.56762, -0.02475, -0.5383, 5.12628, -7.10931, 3.17324, 0, 0.848),
            curve(-232.85459, 1376.54473, -3249.11801, 3827.91186, -2248.29472, 526.55751, 0.657, 1))),
        Map.entry("thumb", new LevelPair(
            curve(0.60323, 0.00278, -0.73328, 5.57157, -7.68067, 3.46933, 0, 0.823),
            curve(-82.89897, 511.01355, -1255.98095, 1540.76821, -940.68087, 228.58523, 0.586, 1))),
        Map.entry("readable", new LevelPair(
            curve(0.66937, 0.04704, -1.06871, 6.43941, -8.9332, 4.17229, 0, 0.77),
            curve(-1554.52576, 8733.56817, -19604.93507, 21977.72696, -12300.99599, 2749.81288,
                0.751, 1))),
        Map.entry("emphasis", new LevelPair(
            curve(0.7303, 0.07695, -1.31626, 7.1681, -10.14436, 4.92846, 0, 0.712),
            curve(-4948.31942, 26870.91986, -58334.48399, 63280.17197, -34298.01053, 7430.30146,
                0.811, 1))),
        Map.entry("textOnPanel", new LevelPair(
            curve(0.86713, 0.05232, -0.89428, 4.79014, -5.5432, 1.75023, 0, 0.542),
            curve(-8570.89457, 43954.60805, -90084.00702, 92220.6791, -47152.15802, 9632.27113,
                0.867, 1))),
        Map.entry("text", new LevelPair(
            curve(0.89242, 0.02311, -0.44862, 2.34417, -0.06084, -2.63844, 0, 0.5),
            curve(-2004.67048, 6664.47299, -6060.70202, -1792.61209, 5133.82359, -1939.85583,
                0.894, 1))));

    /** 规则面：背景令牌、字面 {@code "background"}（终端背景）、或滚动条轨道。 */
    private static final String SURFACE_BACKGROUND = "background";

    private record Rule(ThemeToken token, List<Object> on, String level) {}

    private static final List<Object> TOOL_PANELS = List.of(
        ThemeToken.TOOL_PENDING_BG, ThemeToken.TOOL_SUCCESS_BG, ThemeToken.TOOL_ERROR_BG);
    private static final List<Object> MESSAGE_PANELS = List.of(
        ThemeToken.USER_MESSAGE_BG, ThemeToken.CUSTOM_MESSAGE_BG);
    private static final List<ThemeToken> PANELS = List.of(
        ThemeToken.USER_MESSAGE_BG, ThemeToken.TOOL_PENDING_BG, ThemeToken.TOOL_SUCCESS_BG,
        ThemeToken.TOOL_ERROR_BG, ThemeToken.SELECTED_BG, ThemeToken.SEARCH_MATCH_BG,
        ThemeToken.CUSTOM_MESSAGE_BG);
    private static final List<ThemeToken> THINKING = List.of(
        ThemeToken.THINKING_OFF, ThemeToken.THINKING_MINIMAL, ThemeToken.THINKING_LOW,
        ThemeToken.THINKING_MEDIUM, ThemeToken.THINKING_HIGH, ThemeToken.THINKING_XHIGH,
        ThemeToken.THINKING_MAX);
    private static final List<String> THINKING_LEVELS = List.of(
        "thinking0", "thinking1", "thinking2", "thinking3", "thinking4", "thinking5", "thinking6");

    private static List<Object> concat(List<Object> first, List<Object> second) {
        var out = new ArrayList<Object>(first);
        out.addAll(second);
        return out;
    }

    private static List<Rule> each(List<ThemeToken> tokens, List<Object> on, String level) {
        return tokens.stream().map(token -> new Rule(token, on, level)).toList();
    }

    private static final List<Rule> RULES = buildRules();

    private static List<Rule> buildRules() {
        var rules = new ArrayList<Rule>();
        rules.addAll(each(PANELS, List.of(SURFACE_BACKGROUND), "panel"));
        rules.add(new Rule(ThemeToken.TEXT, List.of(SURFACE_BACKGROUND), "text"));
        rules.add(new Rule(ThemeToken.TEXT, List.of(ThemeToken.SELECTED_BG), "textOnPanel"));
        rules.add(new Rule(ThemeToken.USER_MESSAGE_TEXT, List.of(ThemeToken.USER_MESSAGE_BG),
            "textOnPanel"));
        rules.add(new Rule(ThemeToken.TOOL_TITLE, TOOL_PANELS, "textOnPanel"));
        rules.addAll(each(List.of(ThemeToken.ACCENT, ThemeToken.SUCCESS, ThemeToken.ERROR,
            ThemeToken.WARNING), concat(List.of(SURFACE_BACKGROUND, ThemeToken.SELECTED_BG),
            TOOL_PANELS), "readable"));
        rules.add(new Rule(ThemeToken.MUTED, concat(List.of(SURFACE_BACKGROUND,
            ThemeToken.SELECTED_BG, ThemeToken.CUSTOM_MESSAGE_BG), TOOL_PANELS), "readable"));
        rules.add(new Rule(ThemeToken.DIM, concat(List.of(SURFACE_BACKGROUND,
            ThemeToken.SELECTED_BG, ThemeToken.CUSTOM_MESSAGE_BG), TOOL_PANELS), "subtle"));
        rules.add(new Rule(ThemeToken.THINKING_TEXT, List.of(SURFACE_BACKGROUND), "readable"));
        rules.add(new Rule(ThemeToken.CUSTOM_MESSAGE_TEXT, concat(List.of(
            ThemeToken.CUSTOM_MESSAGE_BG), TOOL_PANELS), "readable"));
        rules.add(new Rule(ThemeToken.CUSTOM_MESSAGE_LABEL, concat(List.of(SURFACE_BACKGROUND,
            ThemeToken.CUSTOM_MESSAGE_BG, ThemeToken.SELECTED_BG), TOOL_PANELS), "readable"));
        rules.add(new Rule(ThemeToken.TOOL_OUTPUT, concat(List.of(SURFACE_BACKGROUND),
            TOOL_PANELS), "readable"));
        rules.addAll(each(List.of(ThemeToken.MD_HEADING, ThemeToken.MD_LINK,
            ThemeToken.MD_LINK_URL, ThemeToken.MD_CODE, ThemeToken.MD_QUOTE,
            ThemeToken.MD_CODE_BLOCK_BORDER, ThemeToken.MD_LIST_BULLET),
            concat(List.of(SURFACE_BACKGROUND), MESSAGE_PANELS), "readable"));
        rules.add(new Rule(ThemeToken.MD_CODE_BLOCK, concat(concat(List.of(SURFACE_BACKGROUND),
            MESSAGE_PANELS), TOOL_PANELS), "readable"));
        rules.addAll(each(List.of(ThemeToken.TOOL_DIFF_ADDED, ThemeToken.TOOL_DIFF_REMOVED,
            ThemeToken.TOOL_DIFF_CONTEXT), concat(List.of(SURFACE_BACKGROUND), TOOL_PANELS),
            "readable"));
        rules.addAll(each(List.of(ThemeToken.SYNTAX_COMMENT, ThemeToken.SYNTAX_KEYWORD,
            ThemeToken.SYNTAX_FUNCTION, ThemeToken.SYNTAX_VARIABLE, ThemeToken.SYNTAX_STRING,
            ThemeToken.SYNTAX_NUMBER, ThemeToken.SYNTAX_TYPE, ThemeToken.SYNTAX_OPERATOR,
            ThemeToken.SYNTAX_PUNCTUATION), concat(concat(List.of(SURFACE_BACKGROUND),
            MESSAGE_PANELS), TOOL_PANELS), "readable"));
        rules.add(new Rule(ThemeToken.SEARCH_MATCH_TEXT, List.of(ThemeToken.SEARCH_MATCH_BG),
            "readable"));
        rules.addAll(each(List.of(ThemeToken.BASH_MODE, ThemeToken.BORDER,
            ThemeToken.BORDER_ACCENT), List.of(SURFACE_BACKGROUND), "readable"));
        rules.add(new Rule(ThemeToken.BORDER_MUTED, List.of(SURFACE_BACKGROUND), "subtle"));
        rules.addAll(each(List.of(ThemeToken.MD_QUOTE_BORDER, ThemeToken.MD_HR),
            concat(concat(List.of(SURFACE_BACKGROUND), MESSAGE_PANELS), TOOL_PANELS), "readable"));
        rules.add(new Rule(ThemeToken.SCROLLBAR_TRACK, List.of(SURFACE_BACKGROUND), "track"));
        rules.add(new Rule(ThemeToken.SCROLLBAR_THUMB, List.of(ThemeToken.SCROLLBAR_TRACK),
            "thumb"));
        for (int i = 0; i < THINKING.size(); i++) {
            rules.add(new Rule(THINKING.get(i), List.of(SURFACE_BACKGROUND), THINKING_LEVELS.get(i)));
        }
        return List.copyOf(rules);
    }

    /** 放宽先把强于此的等级压向它，再整体减弱所有等级（pi :341）。 */
    private static final Map<ThemeAppearance, String> READABLE_FLOOR = Map.of(
        ThemeAppearance.DARK, "readable", ThemeAppearance.LIGHT, "subtle");

    /** 正文使用终端前景色的等级门槛——明显强于 muted。 */
    private static final String FOREGROUND_LEVEL = "emphasis";

    /** 取终端前景色的文本级令牌（pi :347）。 */
    private static final List<ThemeToken> FOREGROUND_TOKENS = List.of(
        ThemeToken.TEXT, ThemeToken.USER_MESSAGE_TEXT, ThemeToken.TOOL_TITLE);

    /** 正文在其被绘制的表面上必须达到的 WCAG 2 对比度（pi :350）。 */
    private static final double TEXT_MINIMUM_WCAG_CONTRAST = 4.5;

    /** 依赖序令牌表：每个面先于绘制其上的令牌（pi SOLVE_ORDER :353-365 同构 DFS）。 */
    private static final List<ThemeToken> SOLVE_ORDER = solveOrder();

    private static List<ThemeToken> solveOrder() {
        var order = new ArrayList<ThemeToken>();
        for (var rule : RULES) {
            visit(rule.token(), order);
        }
        return List.copyOf(order);
    }

    private static void visit(ThemeToken token, List<ThemeToken> order) {
        if (order.contains(token)) {
            return;
        }
        for (var rule : RULES) {
            if (rule.token() != token) {
                continue;
            }
            for (var surface : rule.on()) {
                if (surface instanceof ThemeToken surfaceToken) {
                    visit(surfaceToken, order);
                }
            }
        }
        order.add(token);
    }

    // ============================================================================
    // 公开 API（pi :371-388）
    // ============================================================================

    /** system 主题生成输入。 */
    public record SystemThemeInput(ThemeColor.Rgb foreground, ThemeColor.Rgb background,
                                   List<ThemeColor.Rgb> palette, Double saturation,
                                   ThemeAppearance appearanceHint) {}

    /** system 主题生成结果：hex 色/ANSI 索引/"" 终端默认 + dim 令牌 + 外观。 */
    public record SystemThemeColors(Map<ThemeToken, Object> colors, List<ThemeToken> dim,
                                    ThemeAppearance appearance) {}

    /** OKLab lightness of an sRGB color, 0-1. */
    private static double oklabLightness(ThemeColor.Rgb color) {
        return ThemeColorMath.colorToOklch(ThemeColor.rgb(color.r(), color.g(), color.b())).l();
    }

    /** WCAG 2 relative luminance（pi :396-402）。 */
    public static double relativeLuminance(ThemeColor.Rgb rgb) {
        java.util.function.DoubleUnaryOperator linear = channel -> {
            double value = channel / 255;
            return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
        };
        return 0.2126 * linear.applyAsDouble(rgb.r()) + 0.7152 * linear.applyAsDouble(rgb.g())
            + 0.0722 * linear.applyAsDouble(rgb.b());
    }

    /** WCAG 2 contrast ratio, 1-21（pi :405-409）。 */
    public static double wcagContrast(ThemeColor.Rgb first, ThemeColor.Rgb second) {
        double a = relativeLuminance(first);
        double b = relativeLuminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /**
     * pi {@code terminalAppearance}（:416-431）：由上报色判明暗——正文能可读时
     听终端前景的方向，否则看白字还是黑字在背景上对比更高。
     */
    public static ThemeAppearance terminalAppearance(ThemeColor.Rgb background,
                                                     ThemeColor.Rgb foreground) {
        var white = new ThemeColor.Rgb(255, 255, 255);
        var black = new ThemeColor.Rgb(0, 0, 0);
        double whiteContrast = wcagContrast(white, background);
        double blackContrast = wcagContrast(black, background);
        if (foreground != null) {
            double foregroundL = oklabLightness(foreground);
            double backgroundL = oklabLightness(background);
            if (Math.abs(foregroundL - backgroundL) > 0.05) {
                var appearance = foregroundL > backgroundL
                    ? ThemeAppearance.DARK : ThemeAppearance.LIGHT;
                double best = appearance == ThemeAppearance.DARK ? whiteContrast : blackContrast;
                if (best >= TEXT_MINIMUM_WCAG_CONTRAST) {
                    return appearance;
                }
            }
        }
        return whiteContrast >= blackContrast ? ThemeAppearance.DARK : ThemeAppearance.LIGHT;
    }

    // ============================================================================
    // 生成（pi :437-655）
    // ============================================================================

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static String hexOf(ThemeColor.Rgb rgb) {
        return "#%02x%02x%02x".formatted(Math.round(rgb.r()), Math.round(rgb.g()),
            Math.round(rgb.b()));
    }

    /** Saturation weight at a lightness: a Gaussian (center 0.5, sigma 0.25). */
    private static double bellWeight(double lightness) {
        java.util.function.DoubleUnaryOperator gaussian = x ->
            Math.exp(-((x - 0.5) * (x - 0.5)) / (2 * 0.25 * 0.25));
        return (gaussian.applyAsDouble(lightness) - gaussian.applyAsDouble(0))
            / (1 - gaussian.applyAsDouble(0));
    }

    /** A family's saturation curve relative to its maximum. */
    private static double saturationCurve(Family family, double lightness) {
        double floor = family.satMax() > 0 ? family.satMin() / family.satMax() : 1;
        return floor + (1 - floor) * bellWeight(lightness);
    }

    /** The target lightness for a level on a surface, or null where unreachable. */
    private static Double levelTarget(String level, ThemeAppearance appearance, double surfaceL) {
        var pair = LEVELS.get(level);
        var curve = appearance == ThemeAppearance.DARK ? pair.dark() : pair.light();
        if (surfaceL < curve.reachableLow() || surfaceL > curve.reachableHigh()) {
            return null;
        }
        double sum = 0;
        var coefficients = curve.coefficients();
        for (int power = 0; power < coefficients.length; power++) {
            sum += coefficients[power] * Math.pow(surfaceL, power);
        }
        return sum;
    }

    /**
     * pi {@code generateSystemThemeColors}（:465-587）：从终端上报色生成 system
     主题颜色。
     */
    public static SystemThemeColors generateSystemThemeColors(SystemThemeInput input) {
        double saturation = clamp(input.saturation() != null ? input.saturation() : 1, 0, 1);
        var background = input.background();
        var foreground = input.foreground();
        if (background == null) {
            return indexedColors(saturation, input.appearanceHint());
        }
        List<SourceColor> palette = input.palette() != null && input.palette().size() == 16
            ? input.palette().stream().map(SystemTheme::sourceOf).toList() : null;

        var appearance = terminalAppearance(background, foreground);
        boolean lighter = appearance == ThemeAppearance.DARK;
        double extreme = lighter ? 1 : 0;
        double backgroundL = oklabLightness(background);
        var familyOf = new java.util.function.Function<ThemeToken, Family>() {
            @Override public Family apply(ThemeToken token) {
                return FAMILIES.get(TOKEN_FAMILIES.get(token));
            }
        };

        // A token's color at an OKLab lightness（pi paint :480-488）。
        java.util.function.BiFunction<ThemeToken, Double, ThemeColor.Rgb> paint = (token, oklabL) -> {
            double lightness = Oklab.oklabToOkhslLightness(oklabL);
            var family = familyOf.apply(token);
            if (palette == null) {
                return ThemeColorMath.okhslColor(family.hue(),
                    (family.satMin() + (family.satMax() - family.satMin()) * bellWeight(lightness))
                        * saturation, lightness);
            }
            return anchored(palette.get(TOKEN_SLOTS.getOrDefault(token, family.slot())),
                family, lightness, saturation);
        };

        // pi limitPanel :510-520。
        var extremeText = lighter ? new ThemeColor.Rgb(255, 255, 255) : new ThemeColor.Rgb(0, 0, 0);
        java.util.function.Predicate<ThemeColor.Rgb> readable = color ->
            wcagContrast(extremeText, color) >= TEXT_MINIMUM_WCAG_CONTRAST;
        java.util.function.BiFunction<ThemeToken, Double, ThemeColor.Rgb> limitPanel = (token, l) -> {
            var color = paint.apply(token, l);
            if (readable.test(color)) {
                return color;
            }
            double low = backgroundL;
            double high = l;
            for (int i = 0; i < 20; i++) {
                double middle = (low + high) / 2;
                if (readable.test(paint.apply(token, middle))) {
                    low = middle;
                } else {
                    high = middle;
                }
            }
            return paint.apply(token, low);
        };

        java.util.function.DoubleFunction<Map<Object, ThemeColor.Rgb>> solve = t -> {
            var colors = new HashMap<Object, ThemeColor.Rgb>();
            colors.put(SURFACE_BACKGROUND, background);
            for (var token : SOLVE_ORDER) {
                var targets = new ArrayList<Double>();
                for (var rule : RULES) {
                    if (rule.token() != token) {
                        continue;
                    }
                    for (var surface : rule.on()) {
                        var surfaceColor = colors.get(surface) != null ? colors.get(surface) : background;
                        var value = target(rule.level(), appearance, oklabLightness(surfaceColor),
                            t, extreme);
                        if (value == null || value < 0 || value > 1) {
                            return null;
                        }
                        targets.add(value);
                    }
                }
                double l = lighter
                    ? targets.stream().mapToDouble(Double::doubleValue).max().orElseThrow()
                    : targets.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
                colors.put(token, PANELS.contains(token) ? limitPanel.apply(token, l)
                    : paint.apply(token, l));
            }
            return colors;
        };

        double relaxation = 0;
        var colors = solve.apply(0.0);
        if (colors == null) {
            // Mid-gray backgrounds cannot fit every level: relax as little as possible.
            double low = 0;
            double high = 2;
            colors = solve.apply(high);
            for (int i = 0; i < 20; i++) {
                double middle = (low + high) / 2;
                var attempt = solve.apply(middle);
                if (attempt != null) {
                    high = middle;
                    colors = attempt;
                } else {
                    low = middle;
                }
            }
            relaxation = high;
        }
        final var solved = colors != null ? colors : new HashMap<Object, ThemeColor.Rgb>();
        final double finalRelaxation = relaxation;
        java.util.function.Function<ThemeToken, List<ThemeColor.Rgb>> surfacesOf = token ->
            RULES.stream().filter(rule -> rule.token() == token).flatMap(rule ->
                rule.on().stream().map(surface -> {
                    var value = solved.get(surface);
                    return value != null ? value : background;
                })).toList();

        var result = new EnumMap<ThemeToken, Object>(ThemeToken.class);
        for (var token : TOKEN_FAMILIES.keySet()) {
            var color = solved.get(token);
            result.put(token, color != null ? hexOf(color) : "");
        }

        for (var token : FOREGROUND_TOKENS) {
            var surfaces = surfacesOf.apply(token);
            ThemeColor.Rgb text = solved.get(token);
            if (foreground != null) {
                var targets = surfaces.stream()
                    .map(surface -> target(FOREGROUND_LEVEL, appearance, oklabLightness(surface),
                        finalRelaxation, extreme))
                    .toList();
                if (targets.stream().allMatch(value -> value != null && value >= 0 && value <= 1)) {
                    double needed = lighter
                        ? targets.stream().mapToDouble(Double::doubleValue).max().orElseThrow()
                        : targets.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
                    double foregroundL = oklabLightness(foreground);
                    if (lighter ? foregroundL >= needed : foregroundL <= needed) {
                        result.put(token, "");
                        continue;
                    }
                    text = anchored(sourceOf(foreground), FAMILIES.get("neutral"),
                        Oklab.oklabToOkhslLightness(needed), saturation);
                }
            }
            // Body text keeps at least 4.5:1 on the surfaces it is drawn on.
            if (text != null) {
                result.put(token, hexOf(withTextContrast(text, surfaces, lighter)));
            }
        }
        return new SystemThemeColors(result, List.of(), appearance);
    }

    /**
     * pi {@code target}（:494-502）：规则在表面上需要的亮度，按 t 放宽——0→1
     强于 readable 下限的等级压向下限；1→2 所有等级压向表面自身。
     */
    private static Double target(String level, ThemeAppearance appearance, double surfaceL,
                                 double t, double extreme) {
        var reached = levelTarget(level, appearance, surfaceL);
        if (reached == null && t == 0) {
            return null;
        }
        double distance = (reached != null ? reached : extreme) - surfaceL;
        var floorTarget = levelTarget(READABLE_FLOOR.get(appearance), appearance, surfaceL);
        double floor = (floorTarget != null ? floorTarget : extreme) - surfaceL;
        double compressed = Math.abs(distance) > Math.abs(floor)
            ? distance - (distance - floor) * Math.min(t, 1) : distance;
        return surfaceL + compressed * (1 - Math.max(0, t - 1));
    }

    private static double[] okhslOf(ThemeColor.Rgb rgb) {
        return ThemeColorMath.colorToOkhsl(ThemeColor.rgb(rgb.r(), rgb.g(), rgb.b()));
    }

    /** A terminal color's OKHSL channels and its OKLCH chroma（pi :594-600）。 */
    private record SourceColor(double h, double s, double l, double chroma) {}

    private static SourceColor sourceOf(ThemeColor.Rgb color) {
        var okhsl = okhslOf(color);
        double chroma = ThemeColorMath.colorToOklch(ThemeColor.rgb(color.r(), color.g(), color.b())).c();
        return new SourceColor(okhsl[0], okhsl[1], okhsl[2], chroma);
    }

    /**
     * pi {@code anchored}（:610-617）：源色的色相在另一 OKHSL 亮度上。饱和度在
     其自身亮度生效并沿族的饱和曲线向黑白收敛、恒不超过；彩度同样封顶。
     */
    private static ThemeColor.Rgb anchored(SourceColor source, Family family, double lightness,
                                           double saturation) {
        double anchor = saturationCurve(family, source.l());
        double falloff = anchor > 0 ? Math.min(1, saturationCurve(family, lightness) / anchor) : 1;
        var color = ThemeColorMath.okhslColor(source.h(), source.s() * falloff * saturation, lightness);
        double cap = source.chroma() * falloff * saturation;
        var oklch = ThemeColorMath.colorToOklch(color);
        return oklch.c() <= cap ? color
            : ThemeColorMath.colorToRgb(ThemeColor.oklch(oklch.l(), cap, source.h()));
    }

    /** Move a text color toward white or black until it reaches the WCAG minimum on every surface. */
    private static ThemeColor.Rgb withTextContrast(ThemeColor.Rgb color,
                                                   List<ThemeColor.Rgb> surfaces, boolean lighter) {
        java.util.function.Predicate<ThemeColor.Rgb> meets = candidate ->
            surfaces.stream().allMatch(surface ->
                wcagContrast(candidate, surface) >= TEXT_MINIMUM_WCAG_CONTRAST);
        if (meets.test(color)) {
            return color;
        }
        var okhsl = okhslOf(color);
        java.util.function.DoubleFunction<ThemeColor.Rgb> at = lightness ->
            ThemeColorMath.okhslColor(okhsl[0], okhsl[1], lightness);
        double extreme = lighter ? 1 : 0;
        if (!meets.test(at.apply(extreme))) {
            return at.apply(extreme);
        }
        double low = okhsl[2];
        double high = extreme;
        for (int i = 0; i < 20; i++) {
            double middle = (low + high) / 2;
            if (meets.test(at.apply(middle))) {
                high = middle;
            } else {
                low = middle;
            }
        }
        return at.apply(high);
    }

    /**
     * pi {@code indexedColors}（:642-655）：终端什么都没报时的颜色——终端用自己
     的主题渲染 ANSI 索引 0-15 与默认色，适配任何背景。正文以下的 neutral 令牌
     用暗淡（SGR 2）替代亮黑；面板无底色。
     */
    static SystemThemeColors indexedColors(double saturation, ThemeAppearance appearance) {
        var colors = new EnumMap<ThemeToken, Object>(ThemeToken.class);
        var dim = new ArrayList<ThemeToken>();
        for (var entry : TOKEN_FAMILIES.entrySet()) {
            var token = entry.getKey();
            if (PANELS.contains(token)) {
                colors.put(token, "");
                continue;
            }
            boolean neutral = "neutral".equals(entry.getValue());
            colors.put(token, !neutral && saturation > 0
                ? TOKEN_SLOTS.getOrDefault(token, FAMILIES.get(entry.getValue()).slot()) : "");
            if (neutral && !FOREGROUND_TOKENS.contains(token)) {
                dim.add(token);
            }
        }
        return new SystemThemeColors(colors, dim, appearance);
    }
}
