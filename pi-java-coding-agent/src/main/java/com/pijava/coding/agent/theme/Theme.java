package com.pijava.coding.agent.theme;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 一个已解析的主题（pi {@code theme.ts} 的 {@code Theme} 类 :185-373 对位，
 * 批 21/docs/32 T5）。
 *
 * <p>构造期把每个令牌预算成 ANSI 逃逸串缓存（fg/bg 热路径＝查表＋拼接）；
 * 值为 {@code ""}（终端默认色）的令牌没有自己的颜色，由 {@link #colors()} 从
 * 终端上报的默认色（未上报 ⇒ 按 {@link #appearance()} 猜）填。</p>
 */
public final class Theme {

    private final String name;
    private final String sourcePath;
    private final TerminalColorMode mode;
    /** 预算逃逸串（令牌自有槽位）。 */
    private final Map<ThemeToken, String> fgAnsi = new EnumMap<>(ThemeToken.class);
    private final Map<ThemeToken, String> bgAnsi = new EnumMap<>(ThemeToken.class);
    /** 非 "" 令牌的具体颜色。 */
    private final Map<ThemeToken, ThemeColor> concreteColors = new EnumMap<>(ThemeToken.class);
    private final List<ThemeToken> defaultForegroundTokens = new ArrayList<>();
    private final List<ThemeToken> defaultBackgroundTokens = new ArrayList<>();
    /** 前景令牌中渲染为暗淡（SGR 2）的集合（system 主题 tier-3 用）。 */
    private final Set<ThemeToken> dimTokens;
    private final ThemeAppearance ownAppearance;
    /** 按 {@link Themes#terminalColors()} 身份缓存的解析色表。 */
    private TerminalColors resolvedFor;
    private Map<ThemeToken, ThemeColor> resolvedColors;

    /**
     * @param resolvedValues 已填平 fallback 的全令牌色值表（{@code String} 或
     *                       {@code Integer}；{@code ""}＝终端默认）
     * @param options 可选元数据（name/sourcePath/appearance/dim）
     */
    Theme(Map<ThemeToken, Object> resolvedValues, TerminalColorMode mode, Options options) {
        this.name = options.name();
        this.sourcePath = options.sourcePath();
        this.mode = mode;
        this.dimTokens = options.dim() == null ? Set.of() : Set.copyOf(options.dim());
        this.ownAppearance = options.appearance();
        var concreteForegrounds = new ArrayList<ThemeColor>();
        var concreteBackgrounds = new ArrayList<ThemeColor>();
        for (var entry : resolvedValues.entrySet()) {
            var token = entry.getKey();
            boolean isBackground = token.slot() == ThemeToken.Slot.BACKGROUND;
            var value = entry.getValue();
            // pi addToken（theme.ts:226-235）。
            if ("".equals(value)) {
                (isBackground ? defaultBackgroundTokens : defaultForegroundTokens).add(token);
                (isBackground ? bgAnsi : fgAnsi).put(token, isBackground ? "\033[49m" : "\033[39m");
                continue;
            }
            var color = ThemeColorMath.parseColor(value);
            concreteColors.put(token, color);
            (isBackground ? concreteBackgrounds : concreteForegrounds).add(color);
            (isBackground ? bgAnsi : fgAnsi).put(token, isBackground
                ? ThemeColorMath.backgroundAnsi(color, mode)
                : ThemeColorMath.foregroundAnsi(color, mode));
        }
        if (this.ownAppearance == null) {
            var detected = detectAppearance(concreteForegrounds, concreteBackgrounds);
            this.resolvedAppearance = detected;
        } else {
            this.resolvedAppearance = this.ownAppearance;
        }
    }

    /** 主题元数据。 */
    public record Options(String name, String sourcePath, ThemeAppearance appearance,
                          Set<ThemeToken> dim) {
        /** 全缺席。 */
        public static Options none() {
            return new Options(null, null, null, null);
        }
    }

    private final ThemeAppearance resolvedAppearance;

    /** 主题名（内建/文件主题恒有；内存实例可空）。 */
    public String name() {
        return name;
    }

    /** 来源文件路径（无文件来源 ⇒ null）。 */
    public String sourcePath() {
        return sourcePath;
    }

    /**
     * pi {@code appearance} getter（theme.ts:248-251）：声明值 ?? 颜色检测 ??
     * 终端外观。
     */
    public ThemeAppearance appearance() {
        return resolvedAppearance != null ? resolvedAppearance : Themes.terminalTheme();
    }

    /**
     * pi {@code colors} getter（theme.ts:258-276）：全部令牌的具体颜色——
     * {@code ""} 令牌取终端上报默认色（未上报 ⇒ 按外观猜色）；dim 令牌色向
     * 背景混 0.4 近似。按终端色身份缓存。
     */
    public Map<ThemeToken, ThemeColor> colors() {
        var terminal = Themes.terminalColors();
        if (resolvedFor != terminal) {
            var guess = GUESSED_DEFAULT_COLORS.get(appearance());
            var foreground = terminal.foreground() != null ? terminal.foreground() : guess.foreground;
            var background = terminal.background() != null ? terminal.background() : guess.background;
            var colors = new EnumMap<ThemeToken, ThemeColor>(concreteColors);
            for (var token : defaultForegroundTokens) {
                colors.put(token, foreground);
            }
            for (var token : defaultBackgroundTokens) {
                colors.put(token, background);
            }
            for (var token : dimTokens) {
                var color = colors.get(token);
                if (color != null) {
                    colors.put(token, ThemeColorMath.mixColors(color, background, 0.4, false));
                }
            }
            resolvedFor = terminal;
            resolvedColors = java.util.Collections.unmodifiableMap(colors);
        }
        return resolvedColors;
    }

    /** pi {@code fg(color, text)}（:297-301）：dim 令牌夹 SGR 2。 */
    public String fg(ThemeToken color, String text) {
        var ansi = tokenAnsi(fgAnsi, color);
        if (dimTokens.contains(color)) {
            return ansi + "\033[2m" + text + "\033[22;39m";
        }
        return ansi + text + "\033[39m";
    }

    /** pi {@code bg(color, text)}（:303-306）。 */
    public String bg(ThemeToken color, String text) {
        return tokenAnsi(bgAnsi, color) + text + "\033[49m";
    }

    /**
     * pi {@code style(text, options)}（:278-295）：fg/bg 可给令牌或具体颜色；
     * fg 命中 dim 令牌自动加 dim 属性。
     */
    public String style(String text, ThemeColor fg, ThemeColor bg,
                        Set<ThemeColorMath.TextAttr> attrs) {
        return ThemeColorMath.styleTextWithAnsi(text,
            fg == null ? null : ThemeColorMath.foregroundAnsi(fg, mode),
            bg == null ? null : ThemeColorMath.backgroundAnsi(bg, mode),
            attrs == null ? Set.of() : attrs);
    }

    /** 令牌 fg 形 {@link #style}。 */
    public String style(String text, ThemeToken fg, ThemeToken bg,
                        Set<ThemeColorMath.TextAttr> attrs) {
        var effective = attrs == null ? new java.util.HashSet<ThemeColorMath.TextAttr>()
            : new java.util.HashSet<>(attrs);
        if (fg != null && dimTokens.contains(fg)) {
            effective.add(ThemeColorMath.TextAttr.DIM);
        }
        return ThemeColorMath.styleTextWithAnsi(text,
            fg == null ? null : tokenAnsi(fgAnsi, fg),
            bg == null ? null : tokenAnsi(bgAnsi, bg), effective);
    }

    /** pi chalk.bold 对位。 */
    public String bold(String text) {
        return ThemeColorMath.styleTextWithAnsi(text, null, null, Set.of(ThemeColorMath.TextAttr.BOLD));
    }

    /** pi chalk.italic 对位。 */
    public String italic(String text) {
        return ThemeColorMath.styleTextWithAnsi(text, null, null, Set.of(ThemeColorMath.TextAttr.ITALIC));
    }

    /** pi chalk.underline 对位。 */
    public String underline(String text) {
        return ThemeColorMath.styleTextWithAnsi(text, null, null, Set.of(ThemeColorMath.TextAttr.UNDERLINE));
    }

    /** pi chalk.inverse 对位。 */
    public String inverse(String text) {
        return ThemeColorMath.styleTextWithAnsi(text, null, null, Set.of(ThemeColorMath.TextAttr.INVERSE));
    }

    /** pi chalk.strikethrough 对位。 */
    public String strikethrough(String text) {
        return ThemeColorMath.styleTextWithAnsi(text, null, null,
            Set.of(ThemeColorMath.TextAttr.STRIKETHROUGH));
    }

    /** pi {@code getFgAnsi}（:335-338）：前景令牌的开序，dim 令牌带 SGR 2。 */
    public String getFgAnsi(ThemeToken color) {
        var ansi = tokenAnsi(fgAnsi, color);
        return dimTokens.contains(color) ? ansi + "\033[2m" : ansi;
    }

    /** pi {@code getBgAnsi}。 */
    public String getBgAnsi(ThemeToken color) {
        return tokenAnsi(bgAnsi, color);
    }

    /** pi {@code getColorMode}。 */
    public TerminalColorMode colorMode() {
        return mode;
    }

    /** 令牌是否 dim（SGR 2 暗淡渲染，system 主题 tier-3 的 neutral 令牌）。 */
    public boolean isDim(ThemeToken token) {
        return dimTokens.contains(token);
    }

    /** 令牌是否为 {@code ""}（终端默认色——pi 的 defaultForeground/BackgroundTokens）。 */
    public boolean isTerminalDefault(ThemeToken token) {
        return defaultForegroundTokens.contains(token) || defaultBackgroundTokens.contains(token);
    }

    /**
     * pi {@code getThinkingBorderColor}（:348-368）：思考级别 → 对应边框令牌
     的上色函数；未知级别回落 {@code thinkingOff}。
     *
     * @param level pi 的思考级别字面量（{@code off/minimal/low/medium/high/xhigh/max}）
     */
    public UnaryOperator<String> thinkingBorderColor(String level) {
        var token = switch (level) {
            case "minimal" -> ThemeToken.THINKING_MINIMAL;
            case "low" -> ThemeToken.THINKING_LOW;
            case "medium" -> ThemeToken.THINKING_MEDIUM;
            case "high" -> ThemeToken.THINKING_HIGH;
            case "xhigh" -> ThemeToken.THINKING_XHIGH;
            case "max" -> ThemeToken.THINKING_MAX;
            default -> ThemeToken.THINKING_OFF;
        };
        return text -> fg(token, text);
    }

    /** pi {@code getBashModeBorderColor}。 */
    public UnaryOperator<String> bashModeBorderColor() {
        return text -> fg(ThemeToken.BASH_MODE, text);
    }

    private static String tokenAnsi(Map<ThemeToken, String> ansi, ThemeToken token) {
        var value = ansi.get(token);
        if (value == null) {
            throw new IllegalArgumentException("Unknown theme color: " + token.piName());
        }
        return value;
    }

    // ============================================================================
    // 构造辅助（pi theme.ts:79-122/509-534）
    // ============================================================================

    /**
     * pi {@code resolveVarRefs}（:79-95 逐字）：变量引用解析——数字/{@code ""}/
     * {@code #}/oklch|okhsl 直返；环 ⇒ {@code Circular variable reference detected: x}；
     * 缺变量 ⇒ {@code Variable reference not found: x}。
     */
    static Object resolveVarRefs(Object value, Map<String, Object> vars, Set<String> visited) {
        if (value instanceof Number || "".equals(value) || (value instanceof String text
                && (text.startsWith("#") || text.matches("(?i)^ok(lch|hsl)\\(.*")))) {
            return value;
        }
        var name = (String) value;
        if (visited.contains(name)) {
            throw new IllegalArgumentException("Circular variable reference detected: " + name);
        }
        if (!vars.containsKey(name)) {
            throw new IllegalArgumentException("Variable reference not found: " + name);
        }
        visited.add(name);
        return resolveVarRefs(vars.get(name), vars, visited);
    }

    /**
     * pi {@code withThemeTokenFallbacks}（:108-122）：缺席可选令牌取 fallback 的
     已填值。
     */
    static Map<ThemeToken, Object> withTokenFallbacks(Map<ThemeToken, Object> values) {
        var resolved = new EnumMap<ThemeToken, Object>(values);
        for (var token : ThemeToken.values()) {
            if (token.fallback() == null || resolved.containsKey(token)) {
                continue;
            }
            var fallbackValue = resolved.get(token.fallback());
            if (fallbackValue == null) {
                throw new IllegalArgumentException("Theme token " + token.piName()
                    + " has unresolved fallback " + token.fallback().piName());
            }
            resolved.put(token, fallbackValue);
        }
        return resolved;
    }

    /**
     * pi {@code createTheme}（:525-534）：主题文档 → 解析 var 引用 → 填平 →
     * {@link Theme}。
     */
    static Theme createTheme(ThemeJson themeJson, TerminalColorMode mode, String sourcePath) {
        var resolved = new LinkedHashMap<ThemeToken, Object>();
        var vars = themeJson.vars();
        for (var entry : themeJson.colors().entrySet()) {
            var token = ThemeToken.fromPiName(entry.getKey());
            resolved.put(token, resolveVarRefs(entry.getValue(), vars,
                new java.util.LinkedHashSet<>()));
        }
        return new Theme(withTokenFallbacks(resolved), mode,
            new Options(themeJson.name(), sourcePath, themeJson.appearance(), null));
    }

    // ============================================================================
    // 外观检测（pi theme.ts:158-179）
    // ============================================================================

    /** 终端未上报默认色时的猜测色（pi {@code GUESSED_DEFAULT_COLORS}）。 */
    private static final Map<ThemeAppearance, Guess> GUESSED_DEFAULT_COLORS = Map.of(
        ThemeAppearance.DARK, new Guess((ThemeColor.Rgb) ThemeColor.rgb(229, 229, 231),
            (ThemeColor.Rgb) ThemeColor.rgb(0, 0, 0)),
        ThemeAppearance.LIGHT, new Guess((ThemeColor.Rgb) ThemeColor.rgb(0, 0, 0),
            (ThemeColor.Rgb) ThemeColor.rgb(255, 255, 255)));

    private record Guess(ThemeColor.Rgb foreground, ThemeColor.Rgb background) {}

    /** pi {@code averageLightness}：索引色 0-15 跟随用户调色板，不参与判定。 */
    private static Double averageLightness(List<ThemeColor> colors) {
        var fixed = colors.stream()
            .filter(color -> !(color instanceof ThemeColor.Indexed indexed && indexed.index() < 16))
            .toList();
        if (fixed.isEmpty()) {
            return null;
        }
        return fixed.stream().mapToDouble(color -> ThemeColorMath.colorToOklch(color).l())
            .average().orElseThrow();
    }

    /** pi {@code detectAppearance}：从主题自有颜色的亮度检测设计背景。 */
    static ThemeAppearance detectAppearance(List<ThemeColor> foregrounds,
                                            List<ThemeColor> backgrounds) {
        var fg = averageLightness(foregrounds);
        var bg = averageLightness(backgrounds);
        if (fg != null && bg != null) {
            return bg < fg ? ThemeAppearance.DARK : ThemeAppearance.LIGHT;
        }
        if (bg != null) {
            return bg < 0.5 ? ThemeAppearance.DARK : ThemeAppearance.LIGHT;
        }
        if (fg != null) {
            return fg > 0.5 ? ThemeAppearance.DARK : ThemeAppearance.LIGHT;
        }
        return null;
    }
}
