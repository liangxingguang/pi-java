package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.pijava.coding.agent.theme.ThemeToken;
import com.pijava.tui.theme.TuiThemeBridge;
import com.pijava.tui.util.TextLayout;

import dev.tamboui.style.Style;

/**
 * Unified diff renderer (P6-26, L7 W2): colors a pi-format display diff into
 * width-agnostic {@link LogicalLine}s, mirroring pi's
 * {@code modes/interactive/components/diff.ts renderDiff} line by line:
 *
 * <ul>
 *   <li>{@code parseDiffLine} — {@code ^([+\- ])(\s*\d*)\s(.*)$} → prefix,
 *       line number, content; unmatched lines (including {@code @@}/
 *       {@code ---} headers) render dim ({@code toolDiffContext});</li>
 *   <li>a block of consecutive {@code -} lines followed by a block of
 *       consecutive {@code +} lines renders removed-first; <b>exactly one
 *       removed and one added line</b> triggers the word-level intra-line
 *       diff with {@code [reversed]} highlighting on changed tokens
 *       (≙ pi's {@code theme.inverse});</li>
 *   <li>{@code renderIntraLineDiff} strips the common leading whitespace
 *       before diffing (pi 漏项#5) so a leading insertion cannot consume it;</li>
 *   <li>tabs expand to three spaces ({@code replaceTabs});</li>
 *   <li>line colors come from the {@code toolDiffAdded/Removed/Context}
 *       theme tokens (pi {@code theme.fg}).</li>
 * </ul>
 *
 * <p>Lines are preformatted (never wrapped) so the {@code -}/{@code +} column
 * stays aligned. The diff text itself is produced by
 * {@code LineDiff.generateDiffString} ({@code +NN content}/
 * {@code -NN content}/{@code NN content}/{@code NN ...}), the Java port of
 * pi's {@code edit-diff.ts formatDisplayDiff}.</p>
 */
public final class DiffView {

    /** pi {@code parseDiffLine}（diff.ts:15-19）逐字正则（Java Pattern 贪婪+回溯语义同 JS）。 */
    private static final Pattern DIFF_LINE =
        Pattern.compile("^([+\\- ])(\\s*\\d*)\\s(.*)$", Pattern.DOTALL);

    /** Tab expands to three spaces (pi {@code replaceTabs}). */
    private static final String TAB_REPLACEMENT = "   ";

    private DiffView() {}

    /** pi {@code parseDiffLine} 的产物；{@code null} ＝ 未匹配（整行 dim）。 */
    private record ParsedLine(char prefix, String lineNum, String content) {}

    /**
     * Colors a pi-format display diff into logical lines.
     *
     * @param diffText the diff text (lines prefixed with {@code -}/{@code +}/
     *        {@code ' '} plus right-aligned line numbers, as produced by
     *        {@code LineDiff.generateDiffString})
     * @return logical lines, empty when {@code diffText} is null or blank
     */
    public static List<LogicalLine> lines(String diffText) {
        if (diffText == null || diffText.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<LogicalLine>();
        var lines = diffText.split("\r?\n", -1);
        int i = 0;
        while (i < lines.length) {
            var parsed = parseDiffLine(lines[i]);
            if (parsed == null) {
                out.add(line(TextLayout.escapeMarkup(lines[i]), contextStyle()));
                i++;
                continue;
            }
            if (parsed.prefix() == '-') {
                // Collect consecutive removed lines, then consecutive added lines
                // (pi diff.ts:88-107).
                var removed = new ArrayList<ParsedLine>();
                while (i < lines.length) {
                    var p = parseDiffLine(lines[i]);
                    if (p == null || p.prefix() != '-') {
                        break;
                    }
                    removed.add(p);
                    i++;
                }
                var added = new ArrayList<ParsedLine>();
                while (i < lines.length) {
                    var p = parseDiffLine(lines[i]);
                    if (p == null || p.prefix() != '+') {
                        break;
                    }
                    added.add(p);
                    i++;
                }
                if (removed.size() == 1 && added.size() == 1) {
                    // Single-line modification: word-level intra-line diff.
                    var pair = intraLine(replaceTabs(removed.get(0).content()),
                        replaceTabs(added.get(0).content()));
                    out.add(line("-" + removed.get(0).lineNum() + " " + pair[0], removedStyle()));
                    out.add(line("+" + added.get(0).lineNum() + " " + pair[1], addedStyle()));
                } else {
                    for (var r : removed) {
                        out.add(line("-" + r.lineNum() + " " + esc(replaceTabs(r.content())), removedStyle()));
                    }
                    for (var a : added) {
                        out.add(line("+" + a.lineNum() + " " + esc(replaceTabs(a.content())), addedStyle()));
                    }
                }
            } else if (parsed.prefix() == '+') {
                out.add(line("+" + parsed.lineNum() + " " + esc(replaceTabs(parsed.content())), addedStyle()));
                i++;
            } else {
                out.add(line(" " + parsed.lineNum() + " " + esc(replaceTabs(parsed.content())), contextStyle()));
                i++;
            }
        }
        return List.copyOf(out);
    }

    private static ParsedLine parseDiffLine(String line) {
        Matcher matcher = DIFF_LINE.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        return new ParsedLine(matcher.group(1).charAt(0), matcher.group(2), matcher.group(3));
    }

    private static String replaceTabs(String text) {
        return text.replace("\t", TAB_REPLACEMENT);
    }

    /** 内容进 markup 前转义（内容可能是任意代码文本）。 */
    private static String esc(String text) {
        return TextLayout.escapeMarkup(text);
    }

    private static LogicalLine line(String markup, Style style) {
        return new LogicalLine(markup, 0, 0, true, style);
    }

    private static Style removedStyle() {
        return TuiThemeBridge.fg(ThemeToken.TOOL_DIFF_REMOVED);
    }

    private static Style addedStyle() {
        return TuiThemeBridge.fg(ThemeToken.TOOL_DIFF_ADDED);
    }

    private static Style contextStyle() {
        return TuiThemeBridge.fg(ThemeToken.TOOL_DIFF_CONTEXT);
    }

    // ── 词级 intra-line diff（pi renderIntraLineDiff，diff.ts:34-83）────────

    /**
     * Word-level diff of a 1:1 removed/added pair with the common leading
     * whitespace stripped before diffing. Returns {@code {removedMarkup,
     * addedMarkup}} — escaped markup with changed tokens wrapped in
     * {@code [reversed]} (pi {@code theme.inverse}).
     */
    static String[] intraLine(String oldContent, String newContent) {
        int commonIndentLength = 0;
        while (commonIndentLength < oldContent.length()
                && commonIndentLength < newContent.length()
                && oldContent.charAt(commonIndentLength) == newContent.charAt(commonIndentLength)
                && Character.isWhitespace(oldContent.charAt(commonIndentLength))) {
            commonIndentLength++;
        }
        var commonIndent = TextLayout.escapeMarkup(oldContent.substring(0, commonIndentLength));
        var parts = WordDiff.diffWords(
            oldContent.substring(commonIndentLength), newContent.substring(commonIndentLength));

        StringBuilder removedLine = new StringBuilder(commonIndent);
        StringBuilder addedLine = new StringBuilder(commonIndent);
        boolean isFirstRemoved = true;
        boolean isFirstAdded = true;
        for (var part : parts) {
            if (part.removed()) {
                var value = part.value();
                if (isFirstRemoved) {
                    // Strip leading whitespace from the first removed part (pi diff.ts:58-64).
                    int leading = leadingWhitespace(value);
                    removedLine.append(TextLayout.escapeMarkup(value.substring(0, leading)));
                    value = value.substring(leading);
                    isFirstRemoved = false;
                }
                if (!value.isEmpty()) {
                    removedLine.append("[reversed]").append(TextLayout.escapeMarkup(value)).append("[/]");
                }
            } else if (part.added()) {
                var value = part.value();
                if (isFirstAdded) {
                    int leading = leadingWhitespace(value);
                    addedLine.append(TextLayout.escapeMarkup(value.substring(0, leading)));
                    value = value.substring(leading);
                    isFirstAdded = false;
                }
                if (!value.isEmpty()) {
                    addedLine.append("[reversed]").append(TextLayout.escapeMarkup(value)).append("[/]");
                }
            } else {
                removedLine.append(TextLayout.escapeMarkup(part.value()));
                addedLine.append(TextLayout.escapeMarkup(part.value()));
            }
        }
        return new String[] {removedLine.toString(), addedLine.toString()};
    }

    private static int leadingWhitespace(String value) {
        int i = 0;
        while (i < value.length() && Character.isWhitespace(value.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * Word-level diff（pi 的 {@code Diff.diffWords} 文本级等价）：tokenize 近
     * npm {@code diffWords}——word run（字母数字）／其他非空白 run／whitespace
     * run 交替；LCS 对齐后相邻同类归并。value 为<b>原始未转义</b>文本。
     */
    private static final class WordDiff {

        /** One aligned run: exactly one of removed/added/common is true. */
        record Part(String value, boolean removed, boolean added) {}

        private WordDiff() {}

        static List<Part> diffWords(String oldText, String newText) {
            var a = tokenize(oldText);
            var b = tokenize(newText);
            // LCS table over tokens.
            int n = a.size();
            int m = b.size();
            var lcs = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    lcs[i][j] = a.get(i).equals(b.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
            var parts = new ArrayList<Part>();
            int i = 0;
            int j = 0;
            while (i < n && j < m) {
                if (a.get(i).equals(b.get(j))) {
                    append(parts, a.get(i), false, false);
                    i++;
                    j++;
                } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                    append(parts, a.get(i++), true, false);
                } else {
                    append(parts, b.get(j++), false, true);
                }
            }
            while (i < n) {
                append(parts, a.get(i++), true, false);
            }
            while (j < m) {
                append(parts, b.get(j++), false, true);
            }
            return parts;
        }

        /** Merges into the last part when the kind matches（同 npm diffWords 的归并段）。 */
        private static void append(List<Part> parts, String value, boolean removed, boolean added) {
            if (!parts.isEmpty()) {
                var last = parts.get(parts.size() - 1);
                if (last.removed() == removed && last.added() == added) {
                    parts.set(parts.size() - 1,
                        new Part(last.value() + value, removed, added));
                    return;
                }
            }
            parts.add(new Part(value, removed, added));
        }

        /** word run／非空白非 word run／whitespace run 三类交替。 */
        private static List<String> tokenize(String text) {
            var tokens = new ArrayList<String>();
            int i = 0;
            int n = text.length();
            while (i < n) {
                char c = text.charAt(i);
                int start = i;
                if (Character.isWhitespace(c)) {
                    while (i < n && Character.isWhitespace(text.charAt(i))) {
                        i++;
                    }
                } else if (isWordChar(c)) {
                    while (i < n && isWordChar(text.charAt(i))) {
                        i++;
                    }
                } else {
                    while (i < n && !Character.isWhitespace(text.charAt(i)) && !isWordChar(text.charAt(i))) {
                        i++;
                    }
                }
                tokens.add(text.substring(start, i));
            }
            return tokens;
        }

        private static boolean isWordChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }
    }
}
