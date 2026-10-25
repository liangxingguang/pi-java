package com.pijava.coding.agent.theme;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link ThemeColorMath} 测试（pi colors.ts 对位，批 21/docs/32 §7）。
 */
class ThemeColorMathTest {

    // ── parseColor 六形 ─────────────────────────────────────────

    @Test
    void parsesHexThreeAndSixDigits() {
        assertThat(ThemeColorMath.parseColor("#0af"))
            .isEqualTo(new ThemeColor.Rgb(0, 170, 255));
        assertThat(ThemeColorMath.parseColor("#00aaff"))
            .isEqualTo(new ThemeColor.Rgb(0, 170, 255));
    }

    @Test
    void parsesIndexInteger() {
        assertThat(ThemeColorMath.parseColor(39)).isEqualTo(new ThemeColor.Indexed(39));
        assertThatThrownBy(() -> ThemeColorMath.parseColor(256))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ANSI color index must be an integer from 0 to 255");
    }

    @Test
    void parsesOklchWithPercentAndDeg() {
        var color = ThemeColorMath.parseColor("oklch(62% 0.1 200)");
        assertThat(color).isInstanceOf(ThemeColor.Oklch.class);
        var oklch = (ThemeColor.Oklch) color;
        assertThat(oklch.l()).isCloseTo(0.62, within(1e-9));
        assertThat(oklch.c()).isCloseTo(0.1, within(1e-9));
        assertThat(oklch.h()).isCloseTo(200, within(1e-9));
    }

    @Test
    void parsesOklchFractionalLightness() {
        var oklch = (ThemeColor.Oklch) ThemeColorMath.parseColor("oklch(0.5 0.1 200)");
        assertThat(oklch.l()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void parsesOkhslToRgb() {
        // okhsl 直接落成 sRGB（pi okhslColor）。
        var rgb = ThemeColorMath.parseColor("okhsl(250 60% 55%)");
        assertThat(rgb).isInstanceOf(ThemeColor.Rgb.class);
    }

    @Test
    void rejectsUnknownColorValue() {
        assertThatThrownBy(() -> ThemeColorMath.parseColor("banana"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid color value: banana");
    }

    // ── 转换 golden ────────────────────────────────────────────

    @Test
    void colorToHexRoundTripsHex() {
        assertThat(ThemeColorMath.colorToHex(ThemeColorMath.parseColor("#00aaff")))
            .isEqualTo("#00aaff");
    }

    @Test
    void okhslKnownValues() {
        // golden＝pi 锚点代码实跑值（tsx 直跑 colors.ts，批 21 取证）。
        assertThat(ThemeColorMath.colorToHex(ThemeColorMath.parseColor("okhsl(234 3% 89%)")))
            .isEqualTo("#dee0e1");
        assertThat(ThemeColorMath.colorToHex(ThemeColorMath.parseColor("oklch(62% 0.1 200)")))
            .isEqualTo("#1c989e");
        assertThat(ThemeColorMath.colorToHex(ThemeColorMath.parseColor("#0af")))
            .isEqualTo("#00aaff");
    }

    @Test
    void oklchOutOfGamutMapsIntoGamut() {
        // pi colors.ts:211 注释案例：oklch(100% 0.3 150) 必须映到白。
        var rgb = ThemeColorMath.colorToRgb(ThemeColorMath.parseColor("oklch(100% 0.3 150)"));
        assertThat(ThemeColorMath.colorToHex(ThemeColor.rgb(rgb.r(), rgb.g(), rgb.b())))
            .isEqualTo("#ffffff");
    }

    @Test
    void indexedToRgbTables() {
        assertThat(ThemeColorMath.indexedToRgb(1)).isEqualTo(new ThemeColor.Rgb(128, 0, 0));
        assertThat(ThemeColorMath.indexedToRgb(16)).isEqualTo(new ThemeColor.Rgb(0, 0, 0));
        assertThat(ThemeColorMath.indexedToRgb(21)).isEqualTo(new ThemeColor.Rgb(0, 0, 255));
        assertThat(ThemeColorMath.indexedToRgb(196)).isEqualTo(new ThemeColor.Rgb(255, 0, 0));
        assertThat(ThemeColorMath.indexedToRgb(232)).isEqualTo(new ThemeColor.Rgb(8, 8, 8));
        assertThat(ThemeColorMath.indexedToRgb(255)).isEqualTo(new ThemeColor.Rgb(238, 238, 238));
    }

    @Test
    void rgbToAnsi256CubeAndGray() {
        assertThat(ThemeColorMath.rgbToAnsi256(new ThemeColor.Rgb(255, 0, 0))).isEqualTo(196);
        assertThat(ThemeColorMath.rgbToAnsi256(new ThemeColor.Rgb(0, 0, 0))).isEqualTo(16);
        // 近灰（spread<10 且灰更近）⇒ 灰阶梯。
        assertThat(ThemeColorMath.rgbToAnsi256(new ThemeColor.Rgb(128, 128, 128)))
            .isEqualTo(232 + 12); // GRAY_VALUES[12] = 128
    }

    @Test
    void mixColorsMidpoint() {
        var mixed = ThemeColorMath.mixColors(
            ThemeColor.rgb(0, 0, 0), ThemeColor.rgb(255, 255, 255), 0.5, false);
        var rgb = ThemeColorMath.colorToRgb(mixed);
        assertThat(rgb.r()).isCloseTo(rgb.g(), within(2.0));
        assertThat(rgb.g()).isCloseTo(rgb.b(), within(2.0));
    }

    // ── ANSI 与属性包装 ─────────────────────────────────────────

    @Test
    void foregroundAnsiIndexedAndTruecolor() {
        assertThat(ThemeColorMath.foregroundAnsi(ThemeColor.indexed(39),
            TerminalColorMode.TRUECOLOR)).isEqualTo("\033[38;5;39m");
        assertThat(ThemeColorMath.foregroundAnsi(ThemeColor.rgb(255, 0, 0),
            TerminalColorMode.TRUECOLOR)).isEqualTo("\033[38;2;255;0;0m");
        assertThat(ThemeColorMath.foregroundAnsi(ThemeColor.rgb(255, 0, 0),
            TerminalColorMode.COLOR_256)).isEqualTo("\033[38;5;196m");
        assertThat(ThemeColorMath.backgroundAnsi(ThemeColor.indexed(2),
            TerminalColorMode.COLOR_256)).isEqualTo("\033[48;5;2m");
    }

    @Test
    void styleTextWithAnsiOrderAndClosers() {
        // pi colors.ts:330-367：开序 fg→bg→bold→italic；关码逆序。
        var styled = ThemeColorMath.styleTextWithAnsi("x", "\033[31m", "\033[41m",
            Set.of(ThemeColorMath.TextAttr.BOLD, ThemeColorMath.TextAttr.ITALIC));
        assertThat(styled).isEqualTo("\033[31m\033[41m\033[1m\033[3mx\033[23m\033[22m\033[49m\033[39m");
    }

    @Test
    void styleTextPlainPassthrough() {
        assertThat(ThemeColorMath.styleTextWithAnsi("x", null, null, Set.of()))
            .isEqualTo("x");
    }

    @Test
    void detectTerminalColorModeArms() {
        // 显式覆盖优先（settings terminal.trueColor 对位）。
        assertThat(ThemeColorMath.detectTerminalColorMode(Boolean.FALSE))
            .isEqualTo(TerminalColorMode.COLOR_256);
        assertThat(ThemeColorMath.detectTerminalColorMode(Boolean.TRUE))
            .isEqualTo(TerminalColorMode.TRUECOLOR);
        // 无覆盖 ⇒ 环境探测（本机结果不定，只断言非空）。
        assertThat(ThemeColorMath.detectTerminalColorMode(null)).isNotNull();
    }
}
