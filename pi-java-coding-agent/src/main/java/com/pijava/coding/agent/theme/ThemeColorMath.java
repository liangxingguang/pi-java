package com.pijava.coding.agent.theme;

import java.util.regex.Pattern;

/**
 * 色彩数学（pi {@code packages/tui/src/colors.ts} 367 行逐字移植，批 21/docs/32 T4）：
 * 六形色值解析、sRGB/OKLCH/OKHSL 互换、256 色量化、ANSI 逃逸串与文本属性包装。
 */
public final class ThemeColorMath {

    private ThemeColorMath() {}

    // ============================================================================
    // 解析（pi parseColor :121-148）
    // ============================================================================

    private static final String NUMBER_PATTERN = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:e[+-]?\\d+)?";
    private static final Pattern HEX_PATTERN = Pattern.compile("^#([\\da-f]{3}|[\\da-f]{6})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OKLCH_PATTERN = Pattern.compile(
        "^oklch\\(\\s*(" + NUMBER_PATTERN + ")(%)?\\s+(" + NUMBER_PATTERN + ")\\s+("
            + NUMBER_PATTERN + ")(?:deg)?\\s*\\)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OKHSL_PATTERN = Pattern.compile(
        "^okhsl\\(\\s*(" + NUMBER_PATTERN + ")(?:deg)?\\s+(" + NUMBER_PATTERN + ")(%)?\\s+("
            + NUMBER_PATTERN + ")(%)?\\s*\\)$", Pattern.CASE_INSENSITIVE);

    /**
     * 解析主题文件里的一个色值：整数 ⇒ 256 索引；否则 `#rgb`/`#rrggbb`/
     * `oklch(...)`/`okhsl(...)`。变量引用与 {@code ""} 在进这里之前已被
     * {@code Theme} 解析/分流。
     *
     * @param value 色值字符串或索引整数
     * @return 具体颜色
     * @throws IllegalArgumentException 无法解析（pi {@code Invalid color value: <value>}）
     */
    public static ThemeColor parseColor(Object value) {
        if (value instanceof Number number) {
            return ThemeColor.indexed(number.intValue());
        }
        var text = (String) value;

        var hex = HEX_PATTERN.matcher(text);
        if (hex.find()) {
            String digits = hex.group(1);
            if (digits.length() == 3) {
                var expanded = new StringBuilder(6);
                for (char digit : digits.toCharArray()) {
                    expanded.append(digit).append(digit);
                }
                digits = expanded.toString();
            }
            return ThemeColor.rgb(
                Integer.parseInt(digits.substring(0, 2), 16),
                Integer.parseInt(digits.substring(2, 4), 16),
                Integer.parseInt(digits.substring(4, 6), 16));
        }

        var oklch = OKLCH_PATTERN.matcher(text);
        if (oklch.find()) {
            double lightness = Double.parseDouble(oklch.group(1)) / (oklch.group(2) != null ? 100 : 1);
            return ThemeColor.oklch(lightness, Double.parseDouble(oklch.group(3)),
                Double.parseDouble(oklch.group(4)));
        }

        var okhsl = OKHSL_PATTERN.matcher(text);
        if (okhsl.find()) {
            double saturation = Double.parseDouble(okhsl.group(2)) / (okhsl.group(3) != null ? 100 : 1);
            double lightness = Double.parseDouble(okhsl.group(4)) / (okhsl.group(5) != null ? 100 : 1);
            return okhslColor(Double.parseDouble(okhsl.group(1)), saturation, lightness);
        }

        throw new IllegalArgumentException("Invalid color value: " + text);
    }

    /**
     * pi {@code okhslColor}（colors.ts:107-115）：OKHSL → sRGB。饱和度相对于该
     * 色相与亮度下 sRGB 色域的最大值，故每个值都在色域内、等饱和看起来等艳。
     */
    public static ThemeColor.Rgb okhslColor(double h, double s, double l) {
        if (!Double.isFinite(h) || !Double.isFinite(s) || !Double.isFinite(l)) {
            throw new IllegalArgumentException("h/s/l must be finite");
        }
        if (s < 0 || s > 1) {
            throw new IllegalArgumentException("s must be between 0 and 1: " + s);
        }
        if (l < 0 || l > 1) {
            throw new IllegalArgumentException("l must be between 0 and 1: " + l);
        }
        return Oklab.okhslToRgb(h, s, l);
    }

    // ============================================================================
    // 转换
    // ============================================================================

    private static final ThemeColor.Rgb[] BASIC_COLORS = {
        new ThemeColor.Rgb(0, 0, 0), new ThemeColor.Rgb(128, 0, 0),
        new ThemeColor.Rgb(0, 128, 0), new ThemeColor.Rgb(128, 128, 0),
        new ThemeColor.Rgb(0, 0, 128), new ThemeColor.Rgb(128, 0, 128),
        new ThemeColor.Rgb(0, 128, 128), new ThemeColor.Rgb(192, 192, 192),
        new ThemeColor.Rgb(128, 128, 128), new ThemeColor.Rgb(255, 0, 0),
        new ThemeColor.Rgb(0, 255, 0), new ThemeColor.Rgb(255, 255, 0),
        new ThemeColor.Rgb(0, 0, 255), new ThemeColor.Rgb(255, 0, 255),
        new ThemeColor.Rgb(0, 255, 255), new ThemeColor.Rgb(255, 255, 255),
    };
    private static final int[] CUBE_VALUES = {0, 95, 135, 175, 215, 255};
    private static final int[] GRAY_VALUES = new int[24];

    static {
        for (int i = 0; i < GRAY_VALUES.length; i++) {
            GRAY_VALUES[i] = 8 + i * 10;
        }
    }

    /** pi {@code indexedToRgb}：ANSI 索引 → 约定 sRGB（0-15 走固定基础表）。 */
    public static ThemeColor.Rgb indexedToRgb(int index) {
        if (index < 16) {
            return BASIC_COLORS[index];
        }
        if (index < 232) {
            int cubeIndex = index - 16;
            return new ThemeColor.Rgb(CUBE_VALUES[cubeIndex / 36],
                CUBE_VALUES[(cubeIndex % 36) / 6], CUBE_VALUES[cubeIndex % 6]);
        }
        int gray = 8 + (index - 232) * 10;
        return new ThemeColor.Rgb(gray, gray, gray);
    }

    private static boolean isInSrgbGamut(double[] linear) {
        double epsilon = 1e-7;
        for (double channel : linear) {
            if (channel < -epsilon || channel > 1 + epsilon) {
                return false;
            }
        }
        return true;
    }

    /**
     * pi {@code oklchToRgb}（colors.ts:190-216）：色域映射保持色相不变，方向只算
     * 一次按 chroma 缩放；超色域则二分降 chroma（20 轮），无色点恒在色域内兜底。
     */
    private static ThemeColor.Rgb oklchToRgb(ThemeColor.Oklch oklch) {
        double radians = (oklch.h() * Math.PI) / 180;
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        java.util.function.DoubleFunction<double[]> atChroma = chroma ->
            Oklab.oklabToLinearSrgb(new double[] {oklch.l(), chroma * cos, chroma * sin});

        var direct = atChroma.apply(oklch.c());
        if (isInSrgbGamut(direct)) {
            return Oklab.linearSrgbToRgb(direct);
        }

        // Reduce chroma until the color fits.
        double[] linear = atChroma.apply(0);
        double low = 0;
        double high = oklch.c();
        for (int i = 0; i < 20; i++) {
            double chroma = (low + high) / 2;
            var candidate = atChroma.apply(chroma);
            if (isInSrgbGamut(candidate)) {
                low = chroma;
                linear = candidate;
            } else {
                high = chroma;
            }
        }
        return Oklab.linearSrgbToRgb(linear);
    }

    /** pi {@code colorToRgb}：任何颜色 → sRGB（索引走约定表，oklch 走色域映射）。 */
    public static ThemeColor.Rgb colorToRgb(ThemeColor color) {
        return switch (color) {
            case ThemeColor.Indexed indexed -> indexedToRgb(indexed.index());
            case ThemeColor.Rgb rgb -> rgb;
            case ThemeColor.Oklch oklch -> oklchToRgb(oklch);
        };
    }

    /** pi {@code colorToOklch}。 */
    public static ThemeColor.Oklch colorToOklch(ThemeColor color) {
        if (color instanceof ThemeColor.Oklch oklch) {
            return oklch;
        }
        var lab = Oklab.rgbToOklab(colorToRgb(color));
        return new ThemeColor.Oklch(lab[0], Math.hypot(lab[1], lab[2]),
            ((Math.atan2(lab[2], lab[1]) * 180) / Math.PI + 360) % 360);
    }

    /** pi {@code colorToOkhsl}：返回 {@code [h, s, l]}。 */
    public static double[] colorToOkhsl(ThemeColor color) {
        return Oklab.rgbToOkhsl(colorToRgb(color));
    }

    /** pi {@code colorToHex}：sRGB 通道取整后两位小写 hex。 */
    public static String colorToHex(ThemeColor color) {
        var rgb = colorToRgb(color);
        return "#%02x%02x%02x".formatted(
            Math.round(rgb.r()), Math.round(rgb.g()), Math.round(rgb.b()));
    }

    /**
     * pi {@code mixColors}（colors.ts:241-257）：oklch 空间混色（默认）——无色
     相边取对方色相、 hue 差取短弧；或 srgb 空间线性插值。
     *
     * @param amount 第二色的占比 0-1
     */
    public static ThemeColor mixColors(ThemeColor first, ThemeColor second, double amount, boolean srgbSpace) {
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount must be finite");
        }
        if (amount < 0 || amount > 1) {
            throw new IllegalArgumentException("amount must be between 0 and 1: " + amount);
        }

        if (srgbSpace) {
            var a = colorToRgb(first);
            var b = colorToRgb(second);
            return ThemeColor.rgb(a.r() + (b.r() - a.r()) * amount,
                a.g() + (b.g() - a.g()) * amount, a.b() + (b.b() - a.b()) * amount);
        }

        var a = colorToOklch(first);
        var b = colorToOklch(second);
        double firstHue = a.c() < 1e-7 ? b.h() : a.h();
        double secondHue = b.c() < 1e-7 ? firstHue : b.h();
        double hueDelta = ((secondHue - firstHue + 540) % 360) - 180;
        return ThemeColor.oklch(a.l() + (b.l() - a.l()) * amount,
            a.c() + (b.c() - a.c()) * amount, firstHue + hueDelta * amount);
    }

    // ============================================================================
    // 256 色量化（pi rgbToAnsi256 :279-297）
    // ============================================================================

    private static int findClosest(int[] values, double target) {
        int closestIndex = 0;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < values.length; i++) {
            double distance = Math.abs(target - values[i]);
            if (distance < closestDistance) {
                closestIndex = i;
                closestDistance = distance;
            }
        }
        return closestIndex;
    }

    private static double colorDistance(ThemeColor.Rgb first, ThemeColor.Rgb second) {
        double dr = first.r() - second.r();
        double dg = first.g() - second.g();
        double db = first.b() - second.b();
        return dr * dr * 0.299 + dg * dg * 0.587 + db * db * 0.114;
    }

    /** pi {@code rgbToAnsi256}：立方格最近邻 vs（近灰时）灰阶梯最近邻。 */
    public static int rgbToAnsi256(ThemeColor.Rgb color) {
        int rIndex = findClosest(CUBE_VALUES, color.r());
        int gIndex = findClosest(CUBE_VALUES, color.g());
        int bIndex = findClosest(CUBE_VALUES, color.b());
        var cubeColor = new ThemeColor.Rgb(CUBE_VALUES[rIndex], CUBE_VALUES[gIndex], CUBE_VALUES[bIndex]);
        int cubeIndex = 16 + 36 * rIndex + 6 * gIndex + bIndex;

        int gray = (int) Math.round(0.299 * color.r() + 0.587 * color.g() + 0.114 * color.b());
        int grayOffset = findClosest(GRAY_VALUES, gray);
        int grayValue = GRAY_VALUES[grayOffset];
        double spread = Math.max(color.r(), Math.max(color.g(), color.b()))
            - Math.min(color.r(), Math.min(color.g(), color.b()));
        if (spread < 10
            && colorDistance(color, new ThemeColor.Rgb(grayValue, grayValue, grayValue))
                < colorDistance(color, cubeColor)) {
            return 232 + grayOffset;
        }
        return cubeIndex;
    }

    // ============================================================================
    // ANSI 逃逸串（pi colorAnsi/foregroundAnsi/backgroundAnsi :299-315）
    // ============================================================================

    private static String colorAnsi(ThemeColor color, TerminalColorMode mode, boolean background) {
        if (color instanceof ThemeColor.Indexed indexed) {
            return "\033[" + (background ? 48 : 38) + ";5;" + indexed.index() + "m";
        }
        var rgb = colorToRgb(color);
        if (mode == TerminalColorMode.TRUECOLOR) {
            return "\033[" + (background ? 48 : 38) + ";2;" + Math.round(rgb.r())
                + ";" + Math.round(rgb.g()) + ";" + Math.round(rgb.b()) + "m";
        }
        return "\033[" + (background ? 48 : 38) + ";5;" + rgbToAnsi256(rgb) + "m";
    }

    /** pi {@code foregroundAnsi}。 */
    public static String foregroundAnsi(ThemeColor color, TerminalColorMode mode) {
        return colorAnsi(color, mode, false);
    }

    /** pi {@code backgroundAnsi}。 */
    public static String backgroundAnsi(ThemeColor color, TerminalColorMode mode) {
        return colorAnsi(color, mode, true);
    }

    /**
     * pi {@code styleTextWithAnsi}（colors.ts:330-367 逐字）：预算逃逸串＋属性
     包装文本。关闭码按开序逆序前置。
     *
     * @param fgAnsi 前景开序（null＝无）
     * @param bgAnsi 背景开序（null＝无）
     * @param attrs 属性集（bold/dim/italic/underline/inverse/strikethrough 的子集）
     */
    public static String styleTextWithAnsi(String text, String fgAnsi, String bgAnsi,
                                           java.util.Set<TextAttr> attrs) {
        var prefix = new StringBuilder();
        var suffix = new StringBuilder();
        if (fgAnsi != null) {
            prefix.append(fgAnsi);
            suffix.append("\033[39m");
        }
        if (bgAnsi != null) {
            prefix.append(bgAnsi);
            suffix.insert(0, "\033[49m");
        }
        if (attrs.contains(TextAttr.BOLD)) {
            prefix.append("\033[1m");
        }
        if (attrs.contains(TextAttr.DIM)) {
            prefix.append("\033[2m");
        }
        if (attrs.contains(TextAttr.BOLD) || attrs.contains(TextAttr.DIM)) {
            suffix.insert(0, "\033[22m");
        }
        if (attrs.contains(TextAttr.ITALIC)) {
            prefix.append("\033[3m");
            suffix.insert(0, "\033[23m");
        }
        if (attrs.contains(TextAttr.UNDERLINE)) {
            prefix.append("\033[4m");
            suffix.insert(0, "\033[24m");
        }
        if (attrs.contains(TextAttr.INVERSE)) {
            prefix.append("\033[7m");
            suffix.insert(0, "\033[27m");
        }
        if (attrs.contains(TextAttr.STRIKETHROUGH)) {
            prefix.append("\033[9m");
            suffix.insert(0, "\033[29m");
        }
        return prefix.append(text).append(suffix).toString();
    }

    /** 文本属性（pi {@code TextAttributes} 对位——纯常量闭集 ⇒ enum）。 */
    public enum TextAttr {
        /** 粗体 SGR 1。 */ BOLD,
        /** 暗淡 SGR 2。 */ DIM,
        /** 斜体 SGR 3。 */ ITALIC,
        /** 下划线 SGR 4。 */ UNDERLINE,
        /** 反显 SGR 7。 */ INVERSE,
        /** 删除线 SGR 9。 */ STRIKETHROUGH
    }

    // ============================================================================
    // 终端色彩档探测（裁决④，窄差 D124）
    // ============================================================================

    /**
     * pi {@code getTerminalColorMode}（terminal-image.ts:178-180）的简化对位：
     * {@code PI_TRUE_COLOR=1/0} env ⇒ 定档；否则 {@code COLORTERM ∈
     * {truecolor, 24bit}} ⇒ truecolor；否则默认 truecolor（pi 的 terminfo 探测
     * 无 java 对位，窄差 D124）。
     *
     * @param capabilityOverride settings {@code terminal.trueColor} 覆盖值（可空），
     *                           优先于 env 之外的探测；pi 的 capabilityOverrides 对位
     */
    public static TerminalColorMode detectTerminalColorMode(Boolean capabilityOverride) {
        if (capabilityOverride != null) {
            return capabilityOverride ? TerminalColorMode.TRUECOLOR : TerminalColorMode.COLOR_256;
        }
        var envOverride = System.getenv("PI_TRUE_COLOR");
        if ("1".equals(envOverride)) {
            return TerminalColorMode.TRUECOLOR;
        }
        if ("0".equals(envOverride)) {
            return TerminalColorMode.COLOR_256;
        }
        var colorterm = System.getenv("COLORTERM");
        if (colorterm != null) {
            var lower = colorterm.toLowerCase(java.util.Locale.ROOT);
            if (!lower.equals("truecolor") && !lower.equals("24bit")) {
                return TerminalColorMode.COLOR_256;
            }
        }
        return TerminalColorMode.TRUECOLOR;
    }
}
