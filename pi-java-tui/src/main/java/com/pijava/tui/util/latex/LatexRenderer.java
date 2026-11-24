package com.pijava.tui.util.latex;

import java.util.ArrayList;
import java.util.List;

/**
 * LaTeX → Unicode 文本渲染（L7 W7，R8）：pi {@code tui/src/latex.ts
 * renderLatex} 的 java 对位。行内（{@code $…$}）走纯文本替换；块级
 * （{@code $$…$$}）按 pi 规则竖向堆叠分数 / 上下标 / 极限操作符 / 矩阵。
 * 不支持的构造返回 {@code null}，调用方回落原文（pi 同）。
 */
public final class LatexRenderer {

    static final char MARK_START = '\uF000';
    static final char MARK_END = '\uF001';

    private LatexRenderer() {}

    /** 渲染 LaTeX；不支持 ⇒ null。 */
    public static String render(String source, boolean display) {
        var parser = new Parser(source, display);
        String rendered = parser.render();
        if (rendered == null) {
            return null;
        }
        if (parser.nodes.isEmpty()) {
            return rendered;
        }
        var output = LatexLayout.render(rendered, parser.nodes);
        int indent = Integer.MAX_VALUE;
        for (String line : output) {
            if (!line.isBlank()) {
                indent = Math.min(indent, line.length() - line.stripLeading().length());
            }
        }
        if (indent == Integer.MAX_VALUE) {
            indent = 0;
        }
        var result = new ArrayList<String>();
        for (String line : output) {
            result.add(line.length() >= indent ? line.substring(indent).stripTrailing() : "");
        }
        while (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) {
            result.remove(result.size() - 1);
        }
        return String.join("\n", result);
    }

    /** 文本替换：分数 {@code a/b}、根号、上下标、符号、重音、间距。 */
    static String formatFraction(String numerator, String denominator) {
        String num = numerator.trim();
        String den = denominator.trim();
        boolean simpleNum = num.matches("[\\p{L}\\p{N}.]+");
        boolean simpleDen = den.matches("[\\p{N}.]+") || den.codePointCount(0, den.length()) == 1;
        return (simpleNum ? num : "(" + num + ")") + "/" + (simpleDen ? den : "(" + den + ")");
    }

    static String formatRoot(String value, String symbol) {
        String v = value.trim();
        return v.matches("[\\p{L}\\p{N}.]+") ? symbol + v : symbol + "(" + v + ")";
    }

    static String formatScript(String value, boolean superscript) {
        String text = normalize(value);
        var out = new StringBuilder();
        var map = superscript ? LatexSymbols.SUPERSCRIPTS : LatexSymbols.SUBSCRIPTS;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Character mapped = map.get(c);
            if (mapped == null) {
                if (c == ' ') {
                    continue;
                }
                return (superscript ? "^{" : "_{") + text + "}";
            }
            out.append(mapped);
        }
        return out.length() == 0 ? "" : out.toString();
    }

    /** 符号命令两侧空格（pi normalizeOutput 简化版）。 */
    static String normalize(String value) {
        var lines = new ArrayList<String>();
        for (String line : value.split("\n", -1)) {
            lines.add(line.replaceAll("[ \\t]+", " ").strip());
        }
        while (!lines.isEmpty() && lines.get(0).isEmpty()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return String.join("\n", lines);
    }

    // ── 解析器 ─────────────────────────────────────────────────────────

    record Fraction(String numerator, String denominator) {}
    record Script(String lower, String upper) {}
    record Operator(String operator, String lower, String upper) {}
    record Matrix(List<String> lines) {}
    private static final class Parser {
        private final String source;
        private final boolean display;
        private final List<Object> nodes = new ArrayList<>();
        private int position;
        private boolean supported = true;
        private boolean stackFractions = true;
        private int scriptDepth;

        Parser(String source, boolean display) {
            this.source = source;
            this.display = display;
        }

        String render() {
            String rendered = parseSequence('\0');
            if (!supported || position != source.length()) {
                return null;
            }
            return normalize(rendered);
        }

        private String parseSequence(char endCharacter) {
            var result = new StringBuilder();
            while (position < source.length()) {
                char c = source.charAt(position);
                if (endCharacter != '\0' && c == endCharacter) {
                    position++;
                    return result.toString();
                }
                if (c == '}') {
                    supported = false;
                    return result.toString();
                }
                if (c == '{') {
                    position++;
                    result.append(parseSequence('}'));
                    continue;
                }
                if (c == '\\') {
                    result.append(parseCommand());
                    continue;
                }
                if (c == '^' || c == '_') {
                    position++;
                    result.append(parseScripts(c));
                    continue;
                }
                if (Character.isWhitespace(c)) {
                    while (position < source.length()
                        && Character.isWhitespace(source.charAt(position))) {
                        position++;
                    }
                    result.append(' ');
                    continue;
                }
                if (c == '=' || c == '<' || c == '>') {
                    result.append(' ').append(c).append(' ');
                    position++;
                    continue;
                }
                if (c == '&' || c == '~') {
                    position++;
                    result.append(c == '~' ? " " : "");
                    continue;
                }
                result.append(c);
                position++;
            }
            if (endCharacter != '\0') {
                supported = false;
            }
            return result.toString();
        }

        private String parseScripts(char initial) {
            String[] scripts = new String[2];
            var order = new ArrayList<Character>();
            parseOne(initial, scripts, order);
            int next = position;
            while (next < source.length() && Character.isWhitespace(source.charAt(next))) {
                next++;
            }
            if (next < source.length()) {
                char marker = source.charAt(next);
                if ((marker == '^' || marker == '_') && marker != initial) {
                    position = next + 1;
                    parseOne(marker, scripts, order);
                }
            }
            boolean subUnicode = scripts[0] != null && canMap(scripts[0], false);
            boolean supUnicode = scripts[1] != null && canMap(scripts[1], true);
            boolean needsLayout = display && (scriptDepth > 0
                || (scripts[0] != null && !subUnicode)
                || (scripts[1] != null && !supUnicode));
            if (!needsLayout) {
                var out = new StringBuilder();
                for (char kind : order) {
                    out.append(kind == '_'
                        ? formatScript(scripts[0], false) : formatScript(scripts[1], true));
                }
                return out.toString();
            }
            nodes.add(new Script(
                scripts[0] == null ? null : normalize(scripts[0]),
                scripts[1] == null ? null : normalize(scripts[1])));
            return marker(nodes.size() - 1);
        }

        private void parseOne(char marker, String[] scripts, List<Character> order) {
            scriptDepth++;
            try {
                if (marker == '_') {
                    scripts[0] = parseRequiredArgument(false);
                } else {
                    scripts[1] = parseRequiredArgument(false);
                }
            } finally {
                scriptDepth--;
            }
            order.add(marker);
        }

        private String parseCommand() {
            position++;
            if (position >= source.length()) {
                supported = false;
                return "";
            }
            char first = source.charAt(position);
            if (first == '\n' || first == '\r') {
                position++;
                if (first == '\r' && position < source.length() && source.charAt(position) == '\n') {
                    position++;
                }
                return " ";
            }
            String command;
            if (Character.isLetter(first)) {
                int start = position;
                while (position < source.length() && Character.isLetter(source.charAt(position))) {
                    position++;
                }
                command = source.substring(start, position);
            } else {
                command = String.valueOf(first);
                position++;
            }
            return applyCommand(command);
        }

        private String applyCommand(String command) {
            if ("frac".equals(command) || "dfrac".equals(command) || "tfrac".equals(command)) {
                boolean stack = display && stackFractions && !"tfrac".equals(command);
                String numerator = parseRequiredArgument(!stack);
                String denominator = parseRequiredArgument(!stack);
                if (stack) {
                    nodes.add(new Fraction(normalize(numerator), normalize(denominator)));
                    return marker(nodes.size() - 1);
                }
                return formatFraction(numerator, denominator);
            }
            if ("sqrt".equals(command)) {
                int save = position;
                while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
                    position++;
                }
                String symbol = "√";
                if (position < source.length() && source.charAt(position) == '[') {
                    int close = source.indexOf(']', position);
                    if (close < 0) {
                        supported = false;
                        return "";
                    }
                    String index = source.substring(position + 1, close);
                    position = close + 1;
                    symbol = formatScript(index, true) + "√";
                } else {
                    position = save;
                }
                return formatRoot(parseRequiredArgument(true), symbol);
            }
            if ("not".equals(command)) {
                if (position < source.length() && source.charAt(position) == '\\') {
                    String inner = parseCommand();
                    return inner + "\u0338";
                }
                String inner = parseRequiredArgument(true);
                return inner + "\u0338";
            }
            if ("text".equals(command) || "operatorname".equals(command)
                || LatexSymbols.FONT_SWITCHES.contains(command)) {
                String inner = parseRequiredArgument(true);
                if (LatexSymbols.NAMED_OPERATORS.contains(command)) {
                    return " " + inner + " ";
                }
                return inner;
            }
            if (LatexSymbols.SYMBOLS.containsKey(command)) {
                String symbol = LatexSymbols.SYMBOLS.get(command);
                return LatexSymbols.RELATION_COMMANDS.contains(command)
                    ? " " + symbol + " " : symbol;
            }
            if (LatexSymbols.NAMED_OPERATORS.contains(command)) {
                if (display && LatexSymbols.LIMIT_OPERATORS.contains(command)) {
                    nodes.add(new Operator(command, null, null));
                    return marker(nodes.size() - 1);
                }
                return " " + command + " ";
            }
            if (LatexSymbols.ACCENTS.containsKey(command)) {
                String inner = parseRequiredArgument(true);
                return inner + LatexSymbols.ACCENTS.get(command);
            }
            if (LatexSymbols.SPACING.contains(command)) {
                return " ";
            }
            if (LatexSymbols.IGNORED.contains(command) || LatexSymbols.SIZE_COMMANDS.contains(command)) {
                return "";
            }
            if ("begin".equals(command)) {
                return parseEnvironment();
            }
            if ("\\".equals(command)) {
                return "\n";
            }
            supported = false;
            return "";
        }

        private String parseEnvironment() {
            String name = readRawArgument();
            if (name == null) {
                supported = false;
                return "";
            }
            String terminator = "\\end{" + name + "}";
            int end = source.indexOf(terminator, position);
            if (end < 0) {
                supported = false;
                return "";
            }
            String body = source.substring(position, end);
            position = end + terminator.length();
            var rows = new ArrayList<List<String>>();
            for (String row : body.split("\\\\\\\\", -1)) {
                var cells = new ArrayList<String>();
                for (String cell : row.split("&", -1)) {
                    String rendered = new Parser(cell, display).render();
                    cells.add(rendered == null ? cell.strip() : rendered);
                }
                if (!row.isBlank()) {
                    rows.add(cells);
                }
            }
            int columns = rows.stream().mapToInt(List::size).max().orElse(0);
            int[] widths = new int[columns];
            for (var r : rows) {
                for (int c = 0; c < r.size(); c++) {
                    widths[c] = Math.max(widths[c], LatexLayout.displayWidth(r.get(c)));
                }
            }
            var lines = new ArrayList<String>();
            for (var row : rows) {
                var line = new StringBuilder();
                for (int c = 0; c < columns; c++) {
                    String cell = c < row.size() ? row.get(c) : "";
                    int width = widths[c];
                    if (c > 0) {
                        line.append("  ");
                    }
                    line.append(cell).append(" ".repeat(Math.max(0,
                        width - LatexLayout.displayWidth(cell))));
                }
                lines.add(line.toString().stripTrailing());
            }
            if ("cases".equals(name)) {
                for (int i = 0; i < lines.size(); i++) {
                    lines.set(i, (i == 0 ? "⎧ " : i == lines.size() - 1 ? "⎩ " : "⎨ ") + lines.get(i));
                }
            }
            nodes.add(new Matrix(lines));
            return marker(nodes.size() - 1);
        }

        private String readRawArgument() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
                position++;
            }
            if (position >= source.length() || source.charAt(position) != '{') {
                return null;
            }
            int close = source.indexOf('}', position);
            if (close < 0) {
                return null;
            }
            String value = source.substring(position + 1, close);
            position = close + 1;
            return value;
        }

        private String parseRequiredArgument(boolean allowStack) {
            boolean previous = stackFractions;
            stackFractions = previous && allowStack;
            try {
                while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
                    position++;
                }
                if (position >= source.length()) {
                    supported = false;
                    return "";
                }
                if (source.charAt(position) == '{') {
                    position++;
                    return parseSequence('}');
                }
                char c = source.charAt(position);
                if (c == '\\') {
                    return parseCommand();
                }
                position++;
                return String.valueOf(c);
            } finally {
                stackFractions = previous;
            }
        }

        private static boolean canMap(String value, boolean superscript) {
            var map = superscript ? LatexSymbols.SUPERSCRIPTS : LatexSymbols.SUBSCRIPTS;
            if (value.isEmpty()) {
                return false;
            }
            for (int i = 0; i < value.length(); i++) {
                if (!map.containsKey(value.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        private static String marker(int index) {
            return MARK_START + Integer.toString(index) + MARK_END;
        }
    }
}
