package com.pijava.examples;

import com.pijava.tui.util.MarkupTokens;
import com.pijava.tui.util.TextLayout;

/**
 * pi {@code @earendil-works/pi-tui} 的 {@code visibleWidth}/{@code truncateToWidth}
 * 在扩展示例里的对位（docs/27 W5）。
 *
 * <p>宿主槽位的着色通道是 TamboUI markup（{@code env.colorize} 产
 * {@code [dim]…[/]}），所以量宽与截断都必须<b>标签零宽</b>、转义按解码后计列——
 * 用 {@link TextLayout#displayWidthMarkup}／{@link MarkupTokens}，与 TUI 侧
 * {@code ExtensionInjectionLayer} 的口径一致。pi 的量宽对象是 ANSI 串，语义同构
 * （转义零宽）。</p>
 */
final class ExampleText {

    private ExampleText() {}

    /** pi {@code visibleWidth}：可见列数（markup 感知）。 */
    static int width(String text) {
        return TextLayout.displayWidthMarkup(text);
    }

    /**
     * pi {@code truncateToWidth(text, width, "")}：截到给定列数。
     *
     * <p>只在文本段内按码点裁切；标签整段保留，裁切处仍有未闭合标签时补
     * {@code [/]}（TamboUI 逐行独立解析，跨行样式必须本行闭合）。文本段内的
     * markup 转义（{@code [[}/{@code ]]}）按原文码点计列——本层示例的待截断文本
     * 不含转义，故不引入解码臂。</p>
     */
    static String truncate(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (width(text) <= maxWidth) {
            return text;
        }
        var out = new StringBuilder();
        var used = 0;
        var openTags = 0;
        for (var token : MarkupTokens.tokenize(text)) {
            if (token instanceof MarkupTokens.Token.Open open) {
                out.append(open.raw());
                openTags++;
                continue;
            }
            if (token instanceof MarkupTokens.Token.Close close) {
                if (openTags > 0) {
                    openTags--;
                }
                out.append(close.raw());
                continue;
            }
            var raw = ((MarkupTokens.Token.Text) token).raw();
            for (int i = 0; i < raw.length(); ) {
                int cp = raw.codePointAt(i);
                var ch = new String(Character.toChars(cp));
                int chWidth = TextLayout.displayWidth(ch);
                if (used + chWidth > maxWidth) {
                    return closeAll(out, openTags);
                }
                out.append(ch);
                used += chWidth;
                i += Character.charCount(cp);
            }
        }
        return closeAll(out, openTags);
    }

    private static String closeAll(StringBuilder out, int openTags) {
        for (int i = 0; i < openTags; i++) {
            out.append("[/]");
        }
        return out.toString();
    }
}
