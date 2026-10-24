package com.pijava.tui.theme;

import com.pijava.coding.agent.theme.Theme;
import com.pijava.coding.agent.theme.ThemeColor;
import com.pijava.coding.agent.theme.ThemeColorMath;
import com.pijava.coding.agent.theme.ThemeToken;
import com.pijava.coding.agent.theme.Themes;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * 主题令牌 → TamboUI {@link Color}/{@link Style} 适配（批 21/docs/32 T10）。
 *
 * <p>pi 组件调 {@code theme.fg(token, text)} 把 ANSI 烘进字符串；java 的
 * TamboUI 用 {@link Style} 对象渲染——本桥是两种模型的接缝。色值映射：
 * {@code Indexed} ⇒ {@link Color#indexed}；{@code Rgb}/{@code Oklch} ⇒
 * {@link Color#rgb}（OKLCH 经色域映射到 sRGB）；{@code ""} 终端默认令牌 ⇒
 * {@link Color#RESET}（在 {@code colors()} 里已按终端默认色填实——TamboUI
 * 的 RESET 语义＝终端默认，pi 的 SGR 39/49 对位，这里用填实的具体色以支持
 * 面板底上的正确混色）。dim 令牌附加 {@link Style#dim()}（pi 的 SGR 2 对位）。</p>
 */
public final class TuiThemeBridge {

    private TuiThemeBridge() {}

    /** {@link ThemeColor} ⇒ TamboUI {@link Color}。 */
    public static Color toTamboui(ThemeColor color) {
        return switch (color) {
            case ThemeColor.Indexed indexed -> Color.indexed(indexed.index());
            case ThemeColor.Rgb rgb -> Color.rgb(
                (int) Math.round(rgb.r()), (int) Math.round(rgb.g()), (int) Math.round(rgb.b()));
            case ThemeColor.Oklch oklch -> {
                var rgb = ThemeColorMath.colorToRgb(oklch);
                yield Color.rgb((int) Math.round(rgb.r()), (int) Math.round(rgb.g()),
                    (int) Math.round(rgb.b()));
            }
        };
    }

    /** 活跃主题某令牌的解析色（{@code colors()} 含终端默认填实与 dim 混色）。 */
    public static Color tokenColor(ThemeToken token) {
        return toTamboui(Themes.current().colors().get(token));
    }

    /** pi {@code theme.fg(token, …)} 的 Style 对位（dim 令牌附加 dim 修饰）。 */
    public static Style fg(ThemeToken token) {
        var theme = Themes.current();
        var style = Style.create().fg(toTamboui(theme.colors().get(token)));
        return theme.isDim(token) ? style.dim() : style;
    }

    /** pi {@code theme.bg(token, …)} 的 Style 对位。 */
    public static Style bg(ThemeToken token) {
        return Style.create().bg(tokenColor(token));
    }

    /** 给既有样式叠令牌前景（pi 的复合上色对位；dim 令牌附加 dim 修饰）。 */
    public static Style withFg(Style base, ThemeToken token) {
        var theme = Themes.current();
        var style = base.fg(toTamboui(theme.colors().get(token)));
        return theme.isDim(token) ? style.dim() : style;
    }

    /** {@link Theme} 直给的便捷口（测试/非全局上下文）。 */
    public static Style fg(Theme theme, ThemeToken token) {
        var style = Style.create().fg(toTamboui(theme.colors().get(token)));
        return theme.isDim(token) ? style.dim() : style;
    }
}
