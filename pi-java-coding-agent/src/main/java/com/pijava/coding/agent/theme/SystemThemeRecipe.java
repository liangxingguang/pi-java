package com.pijava.coding.agent.theme;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@link SystemTheme} 的配方数据族（结构债拆分：行为零变化纯移动）——色族与令牌
 * 归属表、对比度等级曲线与规则表、求解序。字段与方法包私有静态，供
 * {@link SystemTheme} 的数学内核引用。
 */
final class SystemThemeRecipe {

    private SystemThemeRecipe() {}

    // ============================================================================
    // 配方：色族与令牌归属（pi :41-131）
    // ============================================================================

    /** 一个色族的 OKHSL 色相与饱和度：中亮度取 max，向黑白两端收敛到 min。 */
    record Family(double hue, double satMin, double satMax, int slot) {}

    static final Map<String, Family> FAMILIES = Map.ofEntries(
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

    static final Map<ThemeToken, String> TOKEN_FAMILIES = new EnumMap<>(Map.ofEntries(
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
    static final Map<ThemeToken, Integer> TOKEN_SLOTS = Map.of(
        ThemeToken.SYNTAX_STRING, 2, ThemeToken.SYNTAX_NUMBER, 5, ThemeToken.SEARCH_MATCH_BG, 3);

    // ============================================================================
    // 对比度等级与规则（pi :137-365）
    // ============================================================================

    /**
     * 目标亮度曲线：表面 OKLab 亮度的多项式，给出令牌在其上需要的 OKLab 亮度。
     * {@code reachable} 是该等级可达的表面亮度区间；越界则放宽。
     */
    record Curve(double[] coefficients, double reachableLow, double reachableHigh) {}

    static Curve curve(double c0, double c1, double c2, double c3, double c4, double c5,
                               double low, double high) {
        return new Curve(new double[] {c0, c1, c2, c3, c4, c5}, low, high);
    }

    record LevelPair(Curve dark, Curve light) {}

    static final Map<String, LevelPair> LEVELS = Map.ofEntries(
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
    static final String SURFACE_BACKGROUND = "background";

    record Rule(ThemeToken token, List<Object> on, String level) {}

    static final List<Object> TOOL_PANELS = List.of(
        ThemeToken.TOOL_PENDING_BG, ThemeToken.TOOL_SUCCESS_BG, ThemeToken.TOOL_ERROR_BG);
    static final List<Object> MESSAGE_PANELS = List.of(
        ThemeToken.USER_MESSAGE_BG, ThemeToken.CUSTOM_MESSAGE_BG);
    static final List<ThemeToken> PANELS = List.of(
        ThemeToken.USER_MESSAGE_BG, ThemeToken.TOOL_PENDING_BG, ThemeToken.TOOL_SUCCESS_BG,
        ThemeToken.TOOL_ERROR_BG, ThemeToken.SELECTED_BG, ThemeToken.SEARCH_MATCH_BG,
        ThemeToken.CUSTOM_MESSAGE_BG);
    static final List<ThemeToken> THINKING = List.of(
        ThemeToken.THINKING_OFF, ThemeToken.THINKING_MINIMAL, ThemeToken.THINKING_LOW,
        ThemeToken.THINKING_MEDIUM, ThemeToken.THINKING_HIGH, ThemeToken.THINKING_XHIGH,
        ThemeToken.THINKING_MAX);
    static final List<String> THINKING_LEVELS = List.of(
        "thinking0", "thinking1", "thinking2", "thinking3", "thinking4", "thinking5", "thinking6");

    static List<Object> concat(List<Object> first, List<Object> second) {
        var out = new ArrayList<Object>(first);
        out.addAll(second);
        return out;
    }

    static List<Rule> each(List<ThemeToken> tokens, List<Object> on, String level) {
        return tokens.stream().map(token -> new Rule(token, on, level)).toList();
    }

    static final List<Rule> RULES = buildRules();

    static List<Rule> buildRules() {
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
    static final Map<ThemeAppearance, String> READABLE_FLOOR = Map.of(
        ThemeAppearance.DARK, "readable", ThemeAppearance.LIGHT, "subtle");

    /** 正文使用终端前景色的等级门槛——明显强于 muted。 */
    static final String FOREGROUND_LEVEL = "emphasis";

    /** 取终端前景色的文本级令牌（pi :347）。 */
    static final List<ThemeToken> FOREGROUND_TOKENS = List.of(
        ThemeToken.TEXT, ThemeToken.USER_MESSAGE_TEXT, ThemeToken.TOOL_TITLE);

    /** 依赖序令牌表：每个面先于绘制其上的令牌（pi SOLVE_ORDER :353-365 同构 DFS）。 */
    static final List<ThemeToken> SOLVE_ORDER = solveOrder();

    static List<ThemeToken> solveOrder() {
        var order = new ArrayList<ThemeToken>();
        for (var rule : RULES) {
            visit(rule.token(), order);
        }
        return List.copyOf(order);
    }

    static void visit(ThemeToken token, List<ThemeToken> order) {
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
}
