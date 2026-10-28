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

    /** 正文在其被绘制的表面上必须达到的 WCAG 2 对比度（pi :350）。 */
    private static final double TEXT_MINIMUM_WCAG_CONTRAST = 4.5;

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
    private static double saturationCurve(SystemThemeRecipe.Family family, double lightness) {
        double floor = family.satMax() > 0 ? family.satMin() / family.satMax() : 1;
        return floor + (1 - floor) * bellWeight(lightness);
    }

    /** The target lightness for a level on a surface, or null where unreachable. */
    private static Double levelTarget(String level, ThemeAppearance appearance, double surfaceL) {
        var pair = SystemThemeRecipe.LEVELS.get(level);
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
        var familyOf = new java.util.function.Function<ThemeToken, SystemThemeRecipe.Family>() {
            @Override public SystemThemeRecipe.Family apply(ThemeToken token) {
                return SystemThemeRecipe.FAMILIES.get(SystemThemeRecipe.TOKEN_FAMILIES.get(token));
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
            return anchored(palette.get(SystemThemeRecipe.TOKEN_SLOTS.getOrDefault(
                token, family.slot())), family, lightness, saturation);
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
            colors.put(SystemThemeRecipe.SURFACE_BACKGROUND, background);
            for (var token : SystemThemeRecipe.SOLVE_ORDER) {
                var targets = new ArrayList<Double>();
                for (var rule : SystemThemeRecipe.RULES) {
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
                colors.put(token, SystemThemeRecipe.PANELS.contains(token)
                    ? limitPanel.apply(token, l) : paint.apply(token, l));
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
            SystemThemeRecipe.RULES.stream().filter(rule -> rule.token() == token).flatMap(rule ->
                rule.on().stream().map(surface -> {
                    var value = solved.get(surface);
                    return value != null ? value : background;
                })).toList();

        var result = new EnumMap<ThemeToken, Object>(ThemeToken.class);
        for (var token : SystemThemeRecipe.TOKEN_FAMILIES.keySet()) {
            var color = solved.get(token);
            result.put(token, color != null ? hexOf(color) : "");
        }

        for (var token : SystemThemeRecipe.FOREGROUND_TOKENS) {
            var surfaces = surfacesOf.apply(token);
            ThemeColor.Rgb text = solved.get(token);
            if (foreground != null) {
                var targets = surfaces.stream()
                    .map(surface -> target(SystemThemeRecipe.FOREGROUND_LEVEL, appearance,
                        oklabLightness(surface), finalRelaxation, extreme))
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
                    text = anchored(sourceOf(foreground), SystemThemeRecipe.FAMILIES.get("neutral"),
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
        var floorTarget = levelTarget(SystemThemeRecipe.READABLE_FLOOR.get(appearance),
            appearance, surfaceL);
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
    private static ThemeColor.Rgb anchored(SourceColor source, SystemThemeRecipe.Family family,
                                           double lightness, double saturation) {
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
        for (var entry : SystemThemeRecipe.TOKEN_FAMILIES.entrySet()) {
            var token = entry.getKey();
            if (SystemThemeRecipe.PANELS.contains(token)) {
                colors.put(token, "");
                continue;
            }
            boolean neutral = "neutral".equals(entry.getValue());
            colors.put(token, !neutral && saturation > 0
                ? SystemThemeRecipe.TOKEN_SLOTS.getOrDefault(token,
                    SystemThemeRecipe.FAMILIES.get(entry.getValue()).slot()) : "");
            if (neutral && !SystemThemeRecipe.FOREGROUND_TOKENS.contains(token)) {
                dim.add(token);
            }
        }
        return new SystemThemeColors(colors, dim, appearance);
    }
}
