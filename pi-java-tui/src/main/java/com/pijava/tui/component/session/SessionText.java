package com.pijava.tui.component.session;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.util.TextLayout;

/**
 * 选择器的文本度量工具（批 14，docs/25 T10）：pi {@code truncateToWidth}/
 * {@code visibleWidth}（pi-tui utils.ts）的列宽纪律——逐 codepoint 累计显示宽，
 * 宽字符不越界（批 9/10 同手法，此为本包的 ellipsis 参数化形）。
 */
final class SessionText {

    private SessionText() {}

    /** pi {@code truncateToWidth(text, maxWidth, ellipsis)}：超长截断并补省略号。 */
    static String truncateToWidth(String text, int maxWidth, String ellipsis) {
        if (maxWidth <= 0) {
            return "";
        }
        if (TextLayout.displayWidth(text) <= maxWidth) {
            return text;
        }
        int ellipsisWidth = TextLayout.displayWidth(ellipsis);
        if (maxWidth <= ellipsisWidth) {
            return ellipsis.isEmpty() ? "" : ellipsis.substring(0, Math.min(maxWidth, ellipsis.length()));
        }
        int target = maxWidth - ellipsisWidth;
        var out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            int w = TextLayout.displayWidth(new String(Character.toChars(cp)));
            if (used + w > target) {
                break;
            }
            out.appendCodePoint(cp);
            used += w;
            i += Character.charCount(cp);
        }
        return out.append(ellipsis).toString();
    }

    /** span 序列的显示宽（pi {@code visibleWidth} 对位）。 */
    static int widthOf(List<SessionListRenderer.Span> spans) {
        int width = 0;
        for (var span : spans) {
            width += TextLayout.displayWidth(span.text());
        }
        return width;
    }

    /**
     * pi 的行级 {@code truncateToWidth(line, width)} 安全网对位：逐 codepoint
     * 截到 maxWidth（不带省略号——行都按宽度公式构造，这刀是兜底）。
     */
    static List<SessionListRenderer.Span> truncateSpans(List<SessionListRenderer.Span> spans,
                                                        int maxWidth) {
        if (maxWidth <= 0) {
            return List.of();
        }
        var out = new ArrayList<SessionListRenderer.Span>();
        int used = 0;
        for (var span : spans) {
            if (used >= maxWidth) {
                break;
            }
            int spanWidth = TextLayout.displayWidth(span.text());
            if (used + spanWidth <= maxWidth) {
                out.add(span);
                used += spanWidth;
                continue;
            }
            var cut = new StringBuilder();
            for (int i = 0; i < span.text().length();) {
                int cp = span.text().codePointAt(i);
                int w = TextLayout.displayWidth(new String(Character.toChars(cp)));
                if (used + w > maxWidth) {
                    break;
                }
                cut.appendCodePoint(cp);
                used += w;
                i += Character.charCount(cp);
            }
            out.add(new SessionListRenderer.Span(cut.toString(), span.style()));
            break;
        }
        return List.copyOf(out);
    }
}
