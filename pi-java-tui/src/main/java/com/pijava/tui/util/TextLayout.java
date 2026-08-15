package com.pijava.tui.util;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.component.LogicalLine;
import com.pijava.tui.component.RenderRow;

import dev.tamboui.style.Style;

/**
 * Display-width aware layout utilities (Phase 3 alignment design §5.1):
 * logical-line splitting and render-time wrapping. Wrapping is derived from
 * the current width every frame, so resize/scroll never corrupts content
 * (Codex TUI2 PR #8761 semantics).
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
     * Returns the display width of a string: wide characters count 2 columns,
     * everything else 1 (follows the MessageBubble.isWide rule).
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

    private static void wrapLogicalLine(LogicalLine line, int width,
                                        List<RenderRow> out) {
        var text = line.markup() == null ? "" : line.markup();
        if (text.isEmpty()) {
            out.add(new RenderRow("", line.style()));
            return;
        }
        int firstIndent = Math.min(line.initialIndent(), Math.max(0, width - 1));
        int restIndent = Math.min(line.subsequentIndent(), Math.max(0, width - 1));
        var words = text.split(" ");
        var current = new StringBuilder();
        int currentWidth = 0;
        boolean firstRow = true;
        boolean emitted = false;
        for (var word : words) {
            int indent = firstRow ? firstIndent : restIndent;
            int usable = width - indent;
            int wordWidth = displayWidth(word);
            if (currentWidth + wordWidth + (current.isEmpty() ? 0 : 1) > usable
                    && currentWidth > 0) {
                out.add(new RenderRow(indent(current, indent), line.style()));
                emitted = true;
                current.setLength(0);
                currentWidth = 0;
                firstRow = false;
                indent = restIndent;
                usable = width - indent;
            }
            if (currentWidth + wordWidth + (current.isEmpty() ? 0 : 1) > usable) {
                // Single word wider than the usable width: hard-break it.
                hardBreak(word, indent, usable, line.style(), out);
                emitted = true;
                firstRow = false;
                continue;
            }
            if (!current.isEmpty()) {
                current.append(' ');
                currentWidth += 1;
            }
            current.append(word);
            currentWidth += wordWidth;
        }
        if (currentWidth > 0) {
            out.add(new RenderRow(
                indent(current, firstRow ? firstIndent : restIndent), line.style()));
        } else if (!emitted) {
            // Whitespace-only content still occupies one blank row.
            out.add(new RenderRow(indent(current, firstIndent), line.style()));
        }
    }

    private static String indent(CharSequence content, int indent) {
        if (indent <= 0) {
            return content.toString();
        }
        return " ".repeat(indent) + content;
    }

    private static void hardBreak(String word, int indent, int usable,
                                  Style style, List<RenderRow> out) {
        var chunk = new StringBuilder();
        int chunkWidth = 0;
        boolean first = true;
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            int w = displayWidth(String.valueOf(Character.toChars(cp)));
            if (chunkWidth + w > usable && chunkWidth > 0) {
                out.add(new RenderRow(indent(chunk, first ? indent : 0), style));
                chunk.setLength(0);
                chunkWidth = 0;
                first = false;
            }
            chunk.appendCodePoint(cp);
            chunkWidth += w;
            i += Character.charCount(cp);
        }
        if (chunkWidth > 0) {
            out.add(new RenderRow(indent(chunk, first ? indent : 0), style));
        }
    }

    private static RenderRow hardTruncate(LogicalLine line, int width) {
        var text = line.markup() == null ? "" : line.markup();
        int indent = Math.min(line.initialIndent(), Math.max(0, width - 1));
        int usable = Math.max(1, width - indent);
        if (displayWidth(text) <= usable) {
            return new RenderRow(indent(text, indent), line.style());
        }
        var out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int w = displayWidth(String.valueOf(Character.toChars(cp)));
            if (used + w > usable - 1) {
                break; // keep one cell for the ellipsis
            }
            out.appendCodePoint(cp);
            used += w;
            i += Character.charCount(cp);
        }
        return new RenderRow(indent(out + "…", indent), line.style());
    }

    private static boolean isWide(int cp) {
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
