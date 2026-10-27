package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 行级 Markdown 块 parser（批 25/docs/36 T3，pi marked lexer 的 java 对位子集）。
 *
 * <p>块型：ATX 标题 1-6 级／段落（连续非空行合并，软换行保留 {@code \n}）／
 * 围栏代码（{@code ```}·{@code ~~~}，≥3 符）／列表（无序 {@code - * +}、有序
 * {@code 1.}·{@code 1)}、任务 {@code [ ]/[x]}、按缩进嵌套）／表格（管道）／
 * 引用（连续 {@code >} 行合并）／hr（{@code ---}/&#42;&#42;&#42;/___ ≥3）／
 * 图片占位行。R5 流式局部围栏修剪（pi markdown.ts:146-169 语义）在解析期应用：
 * 未闭合代码块的尾行若是开栏符的同字符真前缀 ⇒ 修剪（issue #5825）。</p>
 *
 * <p>子集边界（窄差 D-new1）：无 setext 标题／松散列表空行即断列／列表项无跨行
 * 续行／围栏 info 串不拆属性／引用内块不递归（按段落行处理）。</p>
 */
final class MarkdownBlocks {

    /** 块 ADT。 */
    sealed interface Block {
        /** ATX 标题（level 1-6）。 */
        record Heading(int level, String text) implements Block {}
        /** 段落（{@code text} 可含 {@code \n} 软换行）。 */
        record Paragraph(String text) implements Block {}
        /** 围栏代码（{@code closed}=false ⇒ 流式未闭合，已经 R5 修剪）。 */
        record Code(String language, String code) implements Block {}
        /** 列表（项已规整：marker 含序号/任务标记，depth 为嵌套深）。 */
        record ListBlock(List<Item> items) implements Block {}
        /** 引用（inner 为去 {@code > } 前缀的物理行）。 */
        record Quote(List<String> lines) implements Block {}
        /** 图片占位（R14/R15 不在批——维持占位行）。 */
        record Image(String alt, String url) implements Block {}
        /** 管道表格（首行为表头）。 */
        record Table(List<List<String>> rows) implements Block {}
        /** 水平线。 */
        record Hr() implements Block {}
    }

    /** 列表项：marker 已规整（无序 ⇒ {@code "- "}；有序 ⇒ 连号 {@code "N. "}；任务缀在后）。 */
    record Item(int depth, String marker, String text) {}

    /** 块＋「源中块后是否有空行」（pi 的 space token 对位——块后间距规则用）。 */
    record Spaced(Block block, boolean blankAfter) {}

    private MarkdownBlocks() {}

    private static final Pattern FENCE_OPEN = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \t]+(.*)$");
    private static final Pattern HR = Pattern.compile(
        "^ {0,3}((?:-[ \t]*){3,}|(?:\\*[ \t]*){3,}|(?:_[ \t]*){3,})$");
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}>[ \t]?(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile(
        "^( *)([-+*]|\\d{1,9}[.)])(?:[ \t]+)(.*)$");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|[\\s:|-]+\\|?\\s*$");
    private static final Pattern TASK = Pattern.compile("^\\[([ xX])\\][ \t]+(.*)$");

    /** 解析（带 space token 对位信息）。 */
    static List<Spaced> parse(String markdown) {
        var lines = markdown.split("\r?\n", -1);
        var out = new ArrayList<Spaced>();
        int i = 0;
        boolean pendingBlank = false;
        while (i < lines.length) {
            if (lines[i].isBlank()) {
                pendingBlank = true;
                i++;
                continue;
            }
            var result = parseOne(lines, i);
            if (!out.isEmpty()) {
                // 前块的 blankAfter＝本块前的 pendingBlank
                var prev = out.remove(out.size() - 1);
                out.add(new Spaced(prev.block(), pendingBlank));
            }
            pendingBlank = false;
            out.add(new Spaced(result.block(), false));
            i = result.next();
        }
        return out;
    }

    /** 解析（纯块表，测试面）。 */
    static List<Block> parseBlocks(String markdown) {
        return parse(markdown).stream().map(Spaced::block).toList();
    }

    private record ParseResult(Block block, int next) {}

    private static ParseResult parseOne(String[] lines, int i) {
        var line = lines[i];
        var fence = FENCE_OPEN.matcher(line);
        if (fence.matches()) {
            return parseCode(lines, i, fence.group(1), fence.group(2).trim());
        }
        if (isTableStart(lines, i)) {
            return parseTable(lines, i);
        }
        if (IMAGE.matcher(line.trim()).matches()) {
            var m = IMAGE.matcher(line.trim());
            m.matches();
            return new ParseResult(new Block.Image(m.group(1), m.group(2)), i + 1);
        }
        var heading = HEADING.matcher(line);
        if (heading.matches()) {
            return new ParseResult(new Block.Heading(
                heading.group(1).length(), heading.group(2).trim()), i + 1);
        }
        if (HR.matcher(line).matches()) {
            return new ParseResult(new Block.Hr(), i + 1);
        }
        if (QUOTE.matcher(line).matches()) {
            return parseQuote(lines, i);
        }
        if (LIST_ITEM.matcher(line).matches()) {
            return parseList(lines, i);
        }
        return parseParagraph(lines, i);
    }

    /** 块起始判定（段落收集的中断条件）。 */
    private static boolean isBlockStart(String[] lines, int i) {
        var line = lines[i];
        return FENCE_OPEN.matcher(line).matches()
            || HEADING.matcher(line).matches()
            || HR.matcher(line).matches()
            || QUOTE.matcher(line).matches()
            || LIST_ITEM.matcher(line).matches()
            || IMAGE.matcher(line.trim()).matches()
            || isTableStart(lines, i);
    }

    // ── 围栏代码（R5 修剪在末块未闭合臂） ─────────────────────

    private static ParseResult parseCode(String[] lines, int i, String fence, String lang) {
        char fenceChar = fence.charAt(0);
        int fenceLen = fence.length();
        var content = new ArrayList<String>();
        int j = i + 1;
        boolean closed = false;
        while (j < lines.length) {
            var l = lines[j];
            if (isFenceClose(l, fenceChar, fenceLen)) {
                closed = true;
                j++;
                break;
            }
            content.add(l);
            j++;
        }
        if (!closed) {
            trimPartialClosingFence(content, fenceChar, fenceLen);   // R5（裁决⑨）
        }
        return new ParseResult(new Block.Code(lang, String.join("\n", content)), j);
    }

    private static boolean isFenceClose(String line, char fenceChar, int fenceLen) {
        int k = 0;
        while (k < line.length() && line.charAt(k) == ' ') {
            k++;
        }
        if (k > 3) {
            return false;
        }
        int run = 0;
        while (k + run < line.length() && line.charAt(k + run) == fenceChar) {
            run++;
        }
        if (run < fenceLen) {
            return false;
        }
        return line.substring(k + run).isBlank();
    }

    /**
     * R5（pi markdown.ts:146-169 {@code trimPartialClosingFences} 语义对位）：
     * 未闭合代码块尾行＝开栏符的同字符真前缀（长度 &lt; 开栏长）⇒ 修剪。
     */
    private static void trimPartialClosingFence(List<String> content, char fenceChar, int fenceLen) {
        if (content.isEmpty()) {
            return;
        }
        var last = content.get(content.size() - 1);
        if (last.isEmpty() || last.length() >= fenceLen) {
            return;
        }
        for (int k = 0; k < last.length(); k++) {
            if (last.charAt(k) != fenceChar) {
                return;
            }
        }
        content.remove(content.size() - 1);
    }

    // ── 引用 ────────────────────────────────────────────

    private static ParseResult parseQuote(String[] lines, int i) {
        var inner = new ArrayList<String>();
        int j = i;
        while (j < lines.length) {
            var m = QUOTE.matcher(lines[j]);
            if (!m.matches()) {
                break;
            }
            inner.add(m.group(1));
            j++;
        }
        while (!inner.isEmpty() && inner.get(inner.size() - 1).isBlank()) {
            inner.remove(inner.size() - 1);   // pi：尾随空 quote 行弹出
        }
        return new ParseResult(new Block.Quote(inner), j);
    }

    // ── 列表（嵌套按缩进；marker 规整＝pi 非 preserve 臂） ──────

    private static ParseResult parseList(String[] lines, int i) {
        var items = new ArrayList<Item>();
        var levels = new ArrayList<Integer>();          // 各深度锚定缩进列
        Map<Integer, int[]> ordered = new HashMap<>();  // depth → {start, seq}
        int j = i;
        while (j < lines.length) {
            var m = LIST_ITEM.matcher(lines[j]);
            if (!m.matches()) {
                break;
            }
            int indent = m.group(1).length();
            while (!levels.isEmpty() && levels.get(levels.size() - 1) > indent) {
                levels.remove(levels.size() - 1);       // 回浅 ⇒ 弹出更深级
            }
            if (levels.isEmpty() || levels.get(levels.size() - 1) < indent) {
                levels.add(indent);                     // 加深 ⇒ 新一级
            }
            int depth = levels.size() - 1;
            ordered.keySet().removeIf(d -> d > depth);  // 更深级序号作废

            var marker = m.group(2);
            var body = m.group(3);
            String prefix;
            if (Character.isDigit(marker.charAt(0))) {
                var counter = ordered.computeIfAbsent(depth,
                    d -> new int[]{Integer.parseInt(
                        marker.substring(0, marker.length() - 1)), 0});
                prefix = (counter[0] + counter[1]++) + ". ";
            } else {
                prefix = "- ";
            }
            var task = TASK.matcher(body);
            if (task.matches()) {
                prefix += "[" + (" ".equals(task.group(1)) ? " " : "x") + "] ";
                body = task.group(2);
            }
            items.add(new Item(depth, prefix, body));
            j++;
        }
        return new ParseResult(new Block.ListBlock(items), j);
    }

    // ── 表格 ────────────────────────────────────────────

    private static boolean isTableStart(String[] lines, int index) {
        if (index + 1 >= lines.length || !lines[index].trim().startsWith("|")) {
            return false;
        }
        var separator = lines[index + 1].trim();
        return TABLE_SEPARATOR.matcher(separator).matches()
            && separator.chars().filter(c -> c == '-').count() > 0;
    }

    private static ParseResult parseTable(String[] lines, int i) {
        var rows = new ArrayList<List<String>>();
        rows.add(parseTableRow(lines[i]));
        int j = i + 2;   // header + separator
        while (j < lines.length && lines[j].trim().startsWith("|")) {
            rows.add(parseTableRow(lines[j]));
            j++;
        }
        return new ParseResult(new Block.Table(rows), j);
    }

    /** 解析单行管道表格：去掉首尾 | 后按 | 切分并 trim。 */
    private static List<String> parseTableRow(String line) {
        var content = line.trim();
        if (content.startsWith("|")) {
            content = content.substring(1);
        }
        if (content.endsWith("|")) {
            content = content.substring(0, content.length() - 1);
        }
        return java.util.Arrays.stream(content.split("\\|")).map(String::trim).toList();
    }

    // ── 段落（连续非空行合并，块起始行中断） ─────────────────

    private static ParseResult parseParagraph(String[] lines, int i) {
        var parts = new ArrayList<String>();
        int j = i;
        while (j < lines.length && !lines[j].isBlank()
                && (j == i || !isBlockStart(lines, j))) {
            parts.add(lines[j]);
            j++;
        }
        return new ParseResult(new Block.Paragraph(String.join("\n", parts)), j);
    }
}
