package com.pijava.tui.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.component.LogicalLine;
import com.pijava.tui.component.RenderRow;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.MarkupParser;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;

/**
 * Display-width aware layout utilities (Phase 3 alignment design §5.1):
 * logical-line splitting and render-time wrapping. Wrapping is derived from
 * the current width every frame, so resize/scroll never corrupts content
 * (Codex TUI2 PR #8761 semantics).
 *
 * <p><b>批 25（docs/36 T2，裁决①）</b>：wrap 地基 markup 感知——量宽/切词/硬断/
 * 截断全部走 {@link MarkupTokens} token 流：标签零宽、切词不切标签、跨行时
 * 本行尾补 {@code [/]}×开栈深、下行首按原序重开（{@code RenderRow} 逐行独立
 * {@code MarkupParser.parse}，行内样式标签跨换行必须本行闭合、下行重开）。
 * 存量纯转义文本路径行为不变（除裁决⑧的量宽修真：{@code [[}/{@code ]]}/{@code \\}
 * 旧按 2 列量宽，新按 1 列）。</p>
 */
public final class TextLayout {

    private TextLayout() {}

    /**
     * Splits text into logical lines on newline boundaries (empty lines are
     * preserved; CRLF is normalized). Preformatted text stays as a single
     * logical line regardless of embedded newlines.
     *
     * @param text          the source text (may be null)
     * @param preformatted  keep the whole text as one line (hard truncation)
     * @return logical lines; empty when {@code text} is null or empty
     */
    public static List<LogicalLine> split(String text, boolean preformatted) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (preformatted) {
            return List.of(new LogicalLine(text, 0, 0, true, Style.EMPTY));
        }
        var lines = new ArrayList<LogicalLine>();
        for (var raw : text.split("\r?\n", -1)) {
            lines.add(new LogicalLine(raw, 0, 0, false, Style.EMPTY));
        }
        return lines;
    }

    /**
     * Escapes TamboUI markup metacharacters so arbitrary user/assistant text
     * renders literally — code like {@code a[0]}, {@code [red]} or a lone
     * backslash must never be parsed as a style tag. {@code [} → {@code [[},
     * {@code ]} → {@code ]]}, {@code \} → {@code \\} (the parser's own
     * escape forms).
     *
     * @param text the raw text (may be null)
     * @return markup-safe text
     */
    public static String escapeMarkup(String text) {
        if (text == null) {
            return "";
        }
        var out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '[' -> out.append("[[");
                case ']' -> out.append("]]");
                case '\\' -> out.append("\\\\");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Wraps logical lines into render rows for the given content width.
     * Preformatted lines are hard-truncated with a trailing ellipsis; other
     * lines wrap at word boundaries with a hard-break fallback, honoring the
     * first-line/continuation indents.
     *
     * @param lines logical lines
     * @param width target display width (clamped to at least 1)
     * @return render rows
     */
    public static List<RenderRow> wrap(List<LogicalLine> lines, int width) {
        int effectiveWidth = Math.max(1, width);
        var rows = new ArrayList<RenderRow>();
        for (var line : lines) {
            if (line.preformatted()) {
                rows.add(hardTruncate(line, effectiveWidth));
            } else {
                wrapLogicalLine(line, effectiveWidth, rows);
            }
        }
        return rows;
    }

    /**
     * Returns the display width of a plain string: wide characters count 2
     * columns, everything else 1 (follows the MessageBubble.isWide rule).
     *
     * <p>量的是<b>纯文本</b>；markup 串（含标签/转义）用
     * {@link #displayWidthMarkup}——标签零宽、转义按解码后计列。</p>
     *
     * @param s the string to measure (may be null)
     * @return the display width
     */
    public static int displayWidth(String s) {
        if (s == null) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            width += isWide(cp) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return width;
    }

    /** markup 感知可见宽（批 25/docs/36 T2）：标签零宽，转义按解码后字符计列。 */
    public static int displayWidthMarkup(String markup) {
        return MarkupTokens.displayWidth(markup);
    }

    /**
     * {@link RenderRow} → TamboUI {@link Line}（批 25/docs/36 T2 补）：行 style 作底、
     * 行内 markup span 优先（行内标签是更具体意图——pi 的 codespan 色在标题/引用内
     * 不被块色盖掉的对位）。{@code Line.patchStyle} 的方向是参数覆盖 span，与本义
     * 相反，故逐 span 重建。存量内容（行 style 与标签从不共存）行为不变。
     */
    public static Line toLine(RenderRow row) {
        Text parsed = MarkupParser.parse(row.text());
        Line line = parsed.lines().isEmpty() ? Line.empty() : parsed.lines().get(0);
        if (row.style().equals(Style.EMPTY)) {
            return line;
        }
        var spans = line.spans().stream()
            .map(span -> Span.styled(span.content(), row.style().patch(span.style())))
            .toList();
        return Line.from(spans);
    }

    // ── 批 25（docs/36 T2）：markup 感知 wrap ────────────────────

    /** wrap 原子：标签（零宽）或可见字符（记解码后码点，发射时自重转义）。 */
    private sealed interface Atom {
        record Tag(String raw, boolean open, String name) implements Atom {}
        record Ch(int cp, int width) implements Atom {
            String emit() {
                // 与 escapeMarkup 同形：[ → [[，] → ]]，\ → \\
                return switch (cp) {
                    case '[' -> "[[";
                    case ']' -> "]]";
                    case '\\' -> "\\\\";
                    default -> new String(Character.toChars(cp));
                };
            }
        }
    }

    /** wrap 过程态：跨行开标签栈＋下行首重开串＋首行/已发射标记。 */
    private static final class WrapState {
        final ArrayDeque<Atom.Tag> openTags = new ArrayDeque<>();
        List<Atom.Tag> reopen = List.of();   // 下行首重开（原序，底→顶）
        boolean firstRow = true;
        boolean emitted = false;
    }

    private static void wrapLogicalLine(LogicalLine line, int width,
                                        List<RenderRow> out) {
        var text = line.markup() == null ? "" : line.markup();
        var atoms = atomsOf(text);
        if (atoms.isEmpty()) {
            out.add(new RenderRow("", line.style()));
            return;
        }
        int firstIndent = Math.min(line.initialIndent(), Math.max(0, width - 1));
        int restIndent = Math.min(line.subsequentIndent(), Math.max(0, width - 1));
        var words = splitWords(atoms);
        var state = new WrapState();
        var current = new ArrayList<Atom>();
        int currentWidth = 0;
        for (var word : words) {
            int indent = state.firstRow ? firstIndent : restIndent;
            int usable = width - indent;
            int wordWidth = wordWidth(word);
            if (currentWidth + wordWidth + (current.isEmpty() ? 0 : 1) > usable
                    && currentWidth > 0) {
                out.add(buildRow(indent, current, state, line.style()));
                afterBreak(state);
                current = new ArrayList<>();
                currentWidth = 0;
                state.firstRow = false;
                state.emitted = true;
                indent = restIndent;
                usable = width - indent;
            }
            if (currentWidth + wordWidth + (current.isEmpty() ? 0 : 1) > usable) {
                // Single word wider than the usable width: hard-break it.
                hardBreak(word, indent, usable, line.style(), out, state);
                state.emitted = true;
                state.firstRow = false;
                continue;
            }
            if (!current.isEmpty()) {
                current.add(SPACE);
                currentWidth += 1;
            }
            for (var atom : word) {
                appendAtom(current, atom, state);
            }
            currentWidth += wordWidth;
        }
        if (!current.isEmpty()) {
            out.add(buildRow(state.firstRow ? firstIndent : restIndent, current,
                state, line.style()));
        } else if (!state.emitted) {
            // Whitespace-only content still occupies one blank row.
            out.add(new RenderRow(indent(new StringBuilder(), firstIndent), line.style()));
        }
    }

    private static final Atom SPACE = new Atom.Ch(' ', 1);

    /** markup → 原子流（标签零宽；文本段解码转义为可见字符）。 */
    private static List<Atom> atomsOf(String markup) {
        var out = new ArrayList<Atom>();
        for (var token : MarkupTokens.tokenize(markup)) {
            switch (token) {
                case MarkupTokens.Token.Open open ->
                    out.add(new Atom.Tag(open.raw(), true, open.name()));
                case MarkupTokens.Token.Close close ->
                    out.add(new Atom.Tag(close.raw(), false, close.name()));
                case MarkupTokens.Token.Text text -> appendTextAtoms(out, text.raw());
            }
        }
        return out;
    }

    private static void appendTextAtoms(List<Atom> out, String raw) {
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < n && isMeta(raw.charAt(i + 1))) {
                out.add(charAtom(raw.charAt(i + 1)));
                i += 2;
                continue;
            }
            if ((c == '[' || c == ']') && i + 1 < n && raw.charAt(i + 1) == c) {
                out.add(charAtom(c));
                i += 2;
                continue;
            }
            int cp = raw.codePointAt(i);
            out.add(charAtom(cp));
            i += Character.charCount(cp);
        }
    }

    private static Atom.Ch charAtom(int cp) {
        return new Atom.Ch(cp, isWide(cp) ? 2 : 1);
    }

    private static boolean isMeta(char c) {
        return c == '[' || c == ']' || c == '\\';
    }

    /** 按空格切词（同 String.split(" ") 语义：空格丢弃、尾部空词丢弃）。 */
    private static List<List<Atom>> splitWords(List<Atom> atoms) {
        var words = new ArrayList<List<Atom>>();
        var current = new ArrayList<Atom>();
        for (var atom : atoms) {
            if (atom instanceof Atom.Ch ch && ch.cp() == ' ') {
                words.add(current);
                current = new ArrayList<>();
            } else {
                current.add(atom);
            }
        }
        words.add(current);
        while (words.size() > 1 && words.get(words.size() - 1).isEmpty()) {
            words.remove(words.size() - 1);
        }
        return words;
    }

    private static int wordWidth(List<Atom> word) {
        int width = 0;
        for (var atom : word) {
            if (atom instanceof Atom.Ch ch) {
                width += ch.width();
            }
        }
        return width;
    }

    private static void appendAtom(List<Atom> row, Atom atom, WrapState state) {
        row.add(atom);
        if (atom instanceof Atom.Tag tag) {
            applyTag(state, tag);
        }
    }

    /**
     * 标签栈更新（镜像 parser：开⇒压栈；{@code [/]}⇒弹顶；{@code [/name]}⇒
     * 弹到最近同名——其上条目回压，净效果＝只摘匹配项）。
     */
    private static void applyTag(WrapState state, Atom.Tag tag) {
        if (tag.open()) {
            state.openTags.push(tag);
        } else if (tag.name().isEmpty()) {
            state.openTags.poll();
        } else {
            var it = state.openTags.iterator();   // 顶→底
            while (it.hasNext()) {
                if (it.next().name().equals(tag.name())) {
                    it.remove();
                    break;
                }
            }
        }
    }

    /** 行发射：indent＋下行重开串＋原子发射＋开栈全闭（{@code [/]}×深）。 */
    private static RenderRow buildRow(int indent, List<Atom> atoms,
                                      WrapState state, Style style) {
        var sb = new StringBuilder();
        if (indent > 0) {
            sb.append(" ".repeat(indent));
        }
        for (var tag : state.reopen) {
            sb.append(tag.raw());
        }
        for (var atom : atoms) {
            sb.append(switch (atom) {
                case Atom.Tag tag -> tag.raw();
                case Atom.Ch ch -> ch.emit();
            });
        }
        sb.append("[/]".repeat(state.openTags.size()));
        return new RenderRow(sb.toString(), style);
    }

    /** 断行后：记下当前开栈（底→顶原序）作为下行首的重开串。 */
    private static void afterBreak(WrapState state) {
        var reopen = new ArrayList<Atom.Tag>(state.openTags.size());
        var it = state.openTags.descendingIterator();   // 底→顶
        while (it.hasNext()) {
            reopen.add(it.next());
        }
        state.reopen = reopen;
    }

    private static void hardBreak(List<Atom> word, int indent, int usable,
                                  Style style, List<RenderRow> out, WrapState state) {
        var chunk = new ArrayList<Atom>();
        int chunkWidth = 0;
        boolean first = true;
        for (var atom : word) {
            if (atom instanceof Atom.Tag tag) {
                chunk.add(tag);
                applyTag(state, tag);
                continue;
            }
            var ch = (Atom.Ch) atom;
            if (chunkWidth + ch.width() > usable && chunkWidth > 0) {
                out.add(buildRow(first ? indent : 0, chunk, state, style));
                afterBreak(state);
                chunk = new ArrayList<>();
                chunkWidth = 0;
                first = false;
            }
            chunk.add(ch);
            chunkWidth += ch.width();
        }
        if (!chunk.isEmpty()) {
            out.add(buildRow(first ? indent : 0, chunk, state, style));
        }
    }

    private static String indent(CharSequence content, int indent) {
        if (indent <= 0) {
            return content.toString();
        }
        return " ".repeat(indent) + content;
    }

    private static RenderRow hardTruncate(LogicalLine line, int width) {
        var text = line.markup() == null ? "" : line.markup();
        int indent = Math.min(line.initialIndent(), Math.max(0, width - 1));
        int usable = Math.max(1, width - indent);
        if (MarkupTokens.displayWidth(text) <= usable) {
            return new RenderRow(indent(text, indent), line.style());
        }
        var sb = new StringBuilder();
        if (indent > 0) {
            sb.append(" ".repeat(indent));
        }
        var state = new WrapState();
        int used = 0;
        for (var atom : atomsOf(text)) {
            if (atom instanceof Atom.Tag tag) {
                applyTag(state, tag);
                sb.append(tag.raw());
                continue;
            }
            var ch = (Atom.Ch) atom;
            if (used + ch.width() > usable - 1) {
                break; // keep one cell for the ellipsis
            }
            sb.append(ch.emit());
            used += ch.width();
        }
        // 省略号前闭栈（裁决③）：样式不漏进省略号与后续同行内容。
        sb.append("[/]".repeat(state.openTags.size()));
        sb.append('…');
        return new RenderRow(sb.toString(), line.style());
    }

    static boolean isWide(int cp) {
        return cp >= 0x1100 && (cp <= 0x115F
                || cp == 0x2329 || cp == 0x232A
                || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE10 && cp <= 0xFE19)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1F64F)
                || (cp >= 0x1F900 && cp <= 0x1F9FF)
                || (cp >= 0x20000 && cp <= 0x2FFFD)
                || (cp >= 0x30000 && cp <= 0x3FFFD));
    }
}
