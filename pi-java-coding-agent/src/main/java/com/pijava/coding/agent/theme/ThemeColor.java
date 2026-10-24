package com.pijava.coding.agent.theme;

/**
 * 一个具体颜色（pi {@code packages/tui/src/colors.ts} 的 {@code Color} 联合对位，
 * 批 21/docs/32 T4）。每种颜色都能转成 sRGB，色数学因此从不失败。
 *
 * <p>Erasable Java 形态裁决：变体携带不同字段 ⇒ {@code sealed interface}＋
 * {@code record}（CLAUDE.md 编码规范）。通道值可为小数（{@code mixColors} 的
 * srgb 空间插值产生），产 hex/ANSI 时才取整。</p>
 */
public sealed interface ThemeColor {

    /** ANSI 256 调色板索引色。 */
    record Indexed(int index) implements ThemeColor {
        /** @throws IllegalArgumentException 索引非 0-255 */
        public Indexed {
            if (index < 0 || index > 255) {
                throw new IllegalArgumentException(
                    "ANSI color index must be an integer from 0 to 255: " + index);
            }
        }
    }

    /** sRGB 颜色（通道 0-255，可为小数）。 */
    record Rgb(double r, double g, double b) implements ThemeColor {
        /** @throws IllegalArgumentException 通道非有限值或越界 0-255 */
        public Rgb {
            requireFinite(r, "r");
            requireFinite(g, "g");
            requireFinite(b, "b");
            if (r < 0 || r > 255) {
                throw new IllegalArgumentException("r must be between 0 and 255: " + r);
            }
            if (g < 0 || g > 255) {
                throw new IllegalArgumentException("g must be between 0 and 255: " + g);
            }
            if (b < 0 || b > 255) {
                throw new IllegalArgumentException("b must be between 0 and 255: " + b);
            }
        }
    }

    /** OKLCH 颜色（感知亮度 l 0-1、彩度 c ≥ 0、色相 h 度数，构造时归一化到 0-360）。 */
    record Oklch(double l, double c, double h) implements ThemeColor {
        /** @throws IllegalArgumentException 通道非有限值、l 越界或 c 为负 */
        public Oklch {
            requireFinite(l, "l");
            requireFinite(c, "c");
            requireFinite(h, "h");
            if (l < 0 || l > 1) {
                throw new IllegalArgumentException("l must be between 0 and 1: " + l);
            }
            if (c < 0) {
                throw new IllegalArgumentException("c must not be negative: " + c);
            }
            h = ((h % 360) + 360) % 360;
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    /** pi {@code indexedColor}。 */
    static ThemeColor indexed(int index) {
        return new Indexed(index);
    }

    /** pi {@code rgbColor}。 */
    static ThemeColor rgb(double r, double g, double b) {
        return new Rgb(r, g, b);
    }

    /** pi {@code oklchColor}。 */
    static ThemeColor oklch(double l, double c, double h) {
        return new Oklch(l, c, h);
    }
}
