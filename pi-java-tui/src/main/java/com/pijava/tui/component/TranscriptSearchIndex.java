package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.pijava.tui.util.TextLayout;

/**
 * L7 W6（S2a/K21，pi {@code tui/src/alt-screen-search.ts} 对位）：转录搜索语料与匹配。
 *
 * <p>行→可见文本（去 markup）；行间以换行连接，多词查询按空白归一（{@code \s+}）
 * 后不区分大小写匹配；命中切成行内列段（显示列）。行内容与查询未变时复用缓存
 * （{@code changed=false}），与 pi 的 {@code AltScreenSearchIndex} 同义。</p>
 */
public final class TranscriptSearchIndex {

    /** 命中在单行内的列段（{@code [startCol,endCol)}，显示列）。 */
    public record Segment(int row, int startCol, int endCol) {}

    /** 一次命中（跨行时多段）。 */
    public record Match(List<Segment> segments) {}

    /** 搜索结果；{@code changed=false} ⇒ 命中复用上一轮缓存。 */
    public record Result(List<Match> matches, boolean changed) {}

    private String cachedKey;
    private List<Match> cached = List.of();

    /**
     * 搜索转录行。
     *
     * @param rows  当前渲染行（含草稿）
     * @param query 原始查询
     * @return 命中与是否重算
     */
    public Result search(List<RenderRow> rows, String query) {
        var plain = new ArrayList<String>(rows == null ? 0 : rows.size());
        if (rows != null) {
            for (var row : rows) {
                plain.add(TextLayout.plainText(row.text()));
            }
        }
        String normalized = normalize(query);
        String key = String.join("\u0000", plain) + "\u0001" + normalized;
        if (key.equals(cachedKey)) {
            return new Result(cached, false);
        }
        cachedKey = key;
        cached = normalized.isEmpty() ? List.of() : find(plain, normalized);
        return new Result(cached, true);
    }

    /** 归一化查询：空白折叠为单空格、去首尾空白。 */
    public static String normalize(String query) {
        if (query == null) {
            return "";
        }
        return query.replaceAll("\\s+", " ").trim();
    }

    /** 稳定命中键（{@code first.row:first.startCol:last.row:last.endCol}）。 */
    public static String matchKey(Match match) {
        if (match == null || match.segments().isEmpty()) {
            return "";
        }
        var first = match.segments().get(0);
        var last = match.segments().get(match.segments().size() - 1);
        return first.row() + ":" + first.startCol() + ":" + last.row() + ":" + last.endCol();
    }

    private static List<Match> find(List<String> lines, String normalized) {
        var flat = new StringBuilder();
        var starts = new int[lines.size() + 1];
        for (int i = 0; i < lines.size(); i++) {
            starts[i] = flat.length();
            flat.append(lines.get(i));
            if (i + 1 < lines.size()) {
                flat.append('\n');
            }
        }
        starts[lines.size()] = flat.length();

        var parts = normalized.split(" ");
        var pattern = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                pattern.append("\\s+");
            }
            pattern.append(Pattern.quote(parts[i]));
        }
        var regex = Pattern.compile(pattern.toString(),
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        var matcher = regex.matcher(flat);
        var matches = new ArrayList<Match>();
        while (matcher.find()) {
            var segments = segments(lines, starts, matcher.start(), matcher.end());
            if (!segments.isEmpty()) {
                matches.add(new Match(segments));
            }
        }
        return matches;
    }

    private static List<Segment> segments(List<String> lines, int[] starts,
                                          int start, int end) {
        var out = new ArrayList<Segment>();
        for (int row = 0; row + 1 < starts.length; row++) {
            int rowStart = starts[row];
            int rowEnd = rowStart + lines.get(row).length();
            int from = Math.max(start, rowStart);
            int to = Math.min(end, rowEnd);
            if (from >= to) {
                continue;
            }
            String line = lines.get(row);
            int startCol = TextLayout.displayWidth(line.substring(0, from - rowStart));
            int endCol = startCol + TextLayout.displayWidth(
                line.substring(from - rowStart, to - rowStart));
            out.add(new Segment(row, startCol, endCol));
        }
        return out;
    }
}
