package com.pijava.tui.component.tree;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.core.SessionTreeNode;
import com.pijava.tui.component.tree.TreeListModel.ToolCallInfo;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TextLayout;

import dev.tamboui.style.Style;

/**
 * pi tree-selector.ts 的渲染层对位（docs/19 §T3，裁决⑦）：输入模型可见行
 * ＋宽度，输出 span 行（{@code (text, Style)}，不接 ANSI 串）。行构建
 * （render :669-771）、水平视口（renderHorizontalViewport :62-103）、文案表
 * （getEntryDisplayText :773-860）、formatToolCall（:946-1002）、
 * formatLabelTimestamp（:862-885）逐字移植。
 */
public final class TreeListRenderer {

    /** 一行文本的一个样式段。 */
    public record Span(String text, Style style) {}

    /** pi {@code HorizontalViewportRow}（:41-47）＋测试/调试用的 entryId。 */
    public record Row(List<Span> gutter, List<Span> body, int anchorCol,
                      int bodyWidth, boolean selected, String entryId) {}

    /** pi TREE_GUTTER_WIDTH（:49）。 */
    private static final int TREE_GUTTER_WIDTH = 2;

    private final Clock clock;

    /** 系统时钟实例（生产路径）。 */
    public TreeListRenderer() {
        this(Clock.systemDefaultZone());
    }

    /** 注入时钟（label 时间戳三档的测试面）。 */
    public TreeListRenderer(Clock clock) {
        this.clock = clock;
    }

    // ── pi render() 行构建（:669-771）─────────────────────────

    /**
     * 当前窗口的可见行（窗口起始 pi :678-685：
     * {@code selectedIndex - maxVisibleLines/2} 钳制）。空过滤 ⇒ 空表
     * （调用方渲染 {@link #emptyLine()}＋{@link #statusLine}）。
     */
    public List<Row> buildRows(TreeListModel m) {
        var filtered = m.filteredNodes();
        if (filtered.isEmpty()) {
            return List.of();
        }
        int startIndex = Math.max(0, Math.min(
            m.selectedIndex() - m.maxVisibleLines() / 2,
            filtered.size() - m.maxVisibleLines()));
        int endIndex = Math.min(startIndex + m.maxVisibleLines(), filtered.size());
        var rows = new ArrayList<Row>();
        for (int i = startIndex; i < endIndex; i++) {
            rows.add(buildRow(m, filtered.get(i), i == m.selectedIndex()));
        }
        return rows;
    }

    private Row buildRow(TreeListModel m, FlatNode flatNode, boolean isSelected) {
        var node = flatNode.node();
        var entry = node.entry();
        int displayIndent = m.multipleRoots()
            ? Math.max(0, flatNode.indent() - 1) : flatNode.indent();

        // pi :700-735：prefix 逐字符摆放（每级 3 列；gutter 第 0 列 │/ ；
        // connector 层 ├/└＋第 1 列折叠指示 ⊞/⊟/─）。
        boolean showsConnector = flatNode.showConnector() && !flatNode.isVirtualRootChild();
        int connectorPosition = showsConnector ? displayIndent - 1 : -1;
        boolean isFolded = m.isFolded(entry.id());
        var prefix = new StringBuilder();
        int totalChars = displayIndent * 3;
        for (int i = 0; i < totalChars; i++) {
            int level = i / 3;
            int posInLevel = i % 3;
            GutterInfo gutter = null;
            for (var g : flatNode.gutters()) {
                if (g.position() == level) {
                    gutter = g;
                    break;
                }
            }
            if (gutter != null) {
                prefix.append(posInLevel == 0 ? (gutter.show() ? "│" : " ") : " ");
            } else if (showsConnector && level == connectorPosition) {
                if (posInLevel == 0) {
                    prefix.append(flatNode.isLast() ? "└" : "├");
                } else if (posInLevel == 1) {
                    prefix.append(isFolded ? "⊞" : m.isFoldable(entry.id()) ? "⊟" : "─");
                } else {
                    prefix.append(" ");
                }
            } else {
                prefix.append(" ");
            }
        }

        // pi :737-743：无 connector 的根行折叠标记独立（accent）；活动路径 •。
        var prefixPart = new ArrayList<Span>();
        prefixPart.add(new Span(prefix.toString(), SemanticColors.dim()));
        if (isFolded && !showsConnector) {
            prefixPart.add(new Span("⊞ ", SemanticColors.accent()));
        }
        if (m.isOnActivePath(entry.id())) {
            prefixPart.add(new Span("• ", SemanticColors.accent()));
        }
        int anchorCol = prefixPart.stream()
            .mapToInt(s -> TextLayout.displayWidth(s.text())).sum();

        // pi :745-749：label＋时间戳。
        var labelSpans = new ArrayList<Span>();
        if (node.label() != null) {
            labelSpans.add(new Span("[" + node.label() + "] ", SemanticColors.warning()));
            if (m.showLabelTimestamps() && node.labelTimestamp() != null) {
                labelSpans.add(new Span(
                    formatLabelTimestamp(node.labelTimestamp()) + " ", SemanticColors.muted()));
            }
        }

        var content = displayText(node, m.toolCallMap(), isSelected);

        var gutter = new ArrayList<Span>();
        gutter.add(new Span(isSelected ? "› " : "  ",
            isSelected ? SemanticColors.accent() : SemanticColors.plain()));
        var body = new ArrayList<Span>();
        body.addAll(prefixPart);
        body.addAll(labelSpans);
        body.addAll(content);

        if (isSelected) {
            // pi :755-758：选中行 gutter/body 整体套 selectedBg。
            gutter = gutter.stream()
                .map(s -> new Span(s.text(), SemanticColors.withSelectedBg(s.style())))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            body = body.stream()
                .map(s -> new Span(s.text(), SemanticColors.withSelectedBg(s.style())))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        int bodyWidth = body.stream().mapToInt(s -> TextLayout.displayWidth(s.text())).sum();
        return new Row(List.copyOf(gutter), List.copyOf(body), anchorCol, bodyWidth,
            isSelected, entry.id());
    }

    // ── pi renderHorizontalViewport（:62-103）─────────────────

    /** gutter 恒可见；body 仅在选中行锚点不可见时左移；行尾无省略号截断。 */
    public List<List<Span>> viewport(List<Row> rows, int width) {
        int viewportWidth = Math.max(0, width - TREE_GUTTER_WIDTH);
        int maxBodyWidth = rows.stream().mapToInt(Row::bodyWidth).max().orElse(0);
        int maxHorizontalScroll = Math.max(0, maxBodyWidth - viewportWidth);
        var selectedRow = rows.stream().filter(Row::selected).findFirst().orElse(null);

        int horizontalScroll = 0;
        if (selectedRow != null && maxHorizontalScroll > 0) {
            int minVisibleAnchorContentWidth = Math.min(20, Math.max(4, viewportWidth / 3));
            if (selectedRow.anchorCol() > viewportWidth - minVisibleAnchorContentWidth) {
                int anchorContextWidth = Math.min(12, Math.max(2, viewportWidth / 4));
                horizontalScroll = Math.min(maxHorizontalScroll,
                    selectedRow.anchorCol() - anchorContextWidth);
            }
        }

        var lines = new ArrayList<List<Span>>();
        for (var row : rows) {
            var line = new ArrayList<Span>(row.gutter());
            if (horizontalScroll > 0) {
                line.addAll(sliceColumns(row.body(), horizontalScroll, viewportWidth));
            } else {
                line.addAll(row.body());
            }
            lines.add(sliceColumns(line, 0, width));   // truncateToWidth(line, width, "")
        }
        return lines;
    }

    /**
     * span 级按列切片（pi {@code sliceByColumn(strict=true)} 对位）：跨界宽
     * 字符整个丢弃；{@code maxCols} 内能整放才保留。
     */
    static List<Span> sliceColumns(List<Span> spans, int startCol, int maxCols) {
        if (maxCols <= 0) {
            return List.of();
        }
        var out = new ArrayList<Span>();
        int col = 0;
        for (var span : spans) {
            var text = new StringBuilder();
            for (int i = 0; i < span.text().length();) {
                int cp = span.text().codePointAt(i);
                int charCount = Character.charCount(cp);
                int w = TextLayout.displayWidth(new String(Character.toChars(cp)));
                if (col >= startCol && col + w <= startCol + maxCols) {
                    text.appendCodePoint(cp);
                }
                col += w;
                i += charCount;
            }
            if (!text.isEmpty()) {
                out.add(new Span(text.toString(), span.style()));
            }
        }
        return out;
    }

    // ── pi getEntryDisplayText（:773-860）────────────────────

    /** 全类型文案表（§1.4）；选中 ⇒ 各段 bold（pi :859）。 */
    static List<Span> displayText(SessionTreeNode node,
                                  Map<String, ToolCallInfo> toolCallMap,
                                  boolean isSelected) {
        var spans = new ArrayList<Span>();
        switch (node.entry()) {
            case Entry.Message m -> {
                switch (m.message()) {
                    case Message.UserMessage u -> {
                        spans.add(new Span("user: ", SemanticColors.accent()));
                        spans.add(new Span(TreeTextSupport.normalize(
                            TreeTextSupport.extractContent(u.content())), SemanticColors.plain()));
                    }
                    case Message.AssistantMessage am -> {
                        spans.add(new Span("assistant: ", SemanticColors.success()));
                        String text = TreeTextSupport.normalize(
                            TreeTextSupport.extractContent(am.content()));
                        if (!text.isEmpty()) {
                            spans.add(new Span(text, SemanticColors.plain()));
                        } else if (am.stopReason() == StopReason.ABORTED) {
                            spans.add(new Span("(aborted)", SemanticColors.muted()));
                        } else if (am.errorMessage() != null) {
                            spans.add(new Span(TreeTextSupport.truncate(
                                TreeTextSupport.normalize(am.errorMessage()), 80),
                                SemanticColors.error()));
                        } else {
                            spans.add(new Span("(no content)", SemanticColors.muted()));
                        }
                    }
                    case Message.ToolResultMessage tr -> {
                        var toolCall = tr.toolUseId() == null ? null : toolCallMap.get(tr.toolUseId());
                        if (toolCall != null) {
                            spans.add(new Span(formatToolCall(toolCall.name(),
                                toolCall.arguments()), SemanticColors.muted()));
                        } else {
                            spans.add(new Span("[" + (tr.toolName() != null
                                ? tr.toolName() : "tool") + "]", SemanticColors.muted()));
                        }
                    }
                    case Message.SystemMessage sys -> {
                        // pi :811-812：其他 role ⇒ [role]（dim）
                        spans.add(new Span("[" + TreeTextSupport.piRole(sys) + "]",
                            SemanticColors.dim()));
                    }
                }
            }
            case Entry.BashExecution b ->
                spans.add(new Span("[bash]: " + TreeTextSupport.normalize(
                    b.command() == null ? "" : b.command()), SemanticColors.dim()));
            case Entry.CustomMessage cm -> {
                spans.add(new Span("[" + cm.customType() + "]: ",
                    SemanticColors.customMessageLabel()));
                spans.add(new Span(TreeTextSupport.normalize(
                    TreeTextSupport.extractCustomContent(cm.content())), SemanticColors.plain()));
            }
            case Entry.Compaction c ->
                spans.add(new Span("[compaction: " + Math.round(c.tokensBefore() / 1000.0)
                    + "k tokens]", SemanticColors.borderAccent()));
            case Entry.BranchSummary bs -> {
                spans.add(new Span("[branch summary]: ", SemanticColors.warning()));
                spans.add(new Span(TreeTextSupport.normalize(bs.summary()),
                    SemanticColors.plain()));
            }
            case Entry.ModelChange mc ->
                spans.add(new Span("[model: " + mc.modelId() + "]", SemanticColors.dim()));
            case Entry.ThinkingLevelChange tc ->
                spans.add(new Span("[thinking: " + tc.thinkingLevel() + "]",
                    SemanticColors.dim()));
            case Entry.Custom c ->
                spans.add(new Span("[custom: " + c.customType() + "]", SemanticColors.dim()));
            case Entry.ContextEdit ce ->
                spans.add(new Span("[context " + (ce.replacement() == null ? "omit" : "replace")
                    + ": " + ce.targetId() + "]", SemanticColors.dim()));
            case Entry.Usage u -> {
                // 恒隐（过滤层），无文案
            }
        }
        if (isSelected) {
            return spans.stream()
                .map(s -> new Span(s.text(), s.style().bold()))
                .toList();
        }
        return spans;
    }

    // ── pi formatToolCall（:946-1002）────────────────────────

    /** 七型＋自定义；home ⇒ {@code ~} 缩写（HOME 或 USERPROFILE）。 */
    public static String formatToolCall(String name, Map<String, Object> args) {
        switch (name) {
            case "read": {
                String path = shortenPath(str(args.getOrDefault("path",
                    args.getOrDefault("file_path", ""))));
                Object offset = args.get("offset");
                Object limit = args.get("limit");
                String display = path;
                if (offset != null || limit != null) {
                    long start = offset instanceof Number n ? n.longValue() : 1;
                    String end = limit instanceof Number n
                        ? String.valueOf(start + n.longValue() - 1) : "";
                    display += ":" + start + (end.isEmpty() ? "" : "-" + end);
                }
                return "[read: " + display + "]";
            }
            case "write":
                return "[write: " + shortenPath(str(args.getOrDefault("path",
                    args.getOrDefault("file_path", "")))) + "]";
            case "edit":
                return "[edit: " + shortenPath(str(args.getOrDefault("path",
                    args.getOrDefault("file_path", "")))) + "]";
            case "bash": {
                String rawCmd = str(args.getOrDefault("command", ""));
                String cmd = TreeTextSupport.truncate(TreeTextSupport.normalize(rawCmd), 50);
                return "[bash: " + cmd + (rawCmd.length() > 50 ? "..." : "") + "]";
            }
            case "grep":
                return "[grep: /" + str(args.getOrDefault("pattern", "")) + "/ in "
                    + shortenPath(str(args.getOrDefault("path", "."))) + "]";
            case "find":
                return "[find: " + str(args.getOrDefault("pattern", "")) + " in "
                    + shortenPath(str(args.getOrDefault("path", "."))) + "]";
            case "ls":
                return "[ls: " + shortenPath(str(args.getOrDefault("path", "."))) + "]";
            default: {
                String argsStr = toJson(args);
                return "[" + name + ": " + TreeTextSupport.truncate(argsStr, 40)
                    + (argsStr.length() > 40 ? "..." : "") + "]";
            }
        }
    }

    private static String shortenPath(String p) {
        String home = System.getenv("HOME");
        if (home == null || home.isEmpty()) {
            home = System.getenv("USERPROFILE");
        }
        if (home != null && !home.isEmpty() && p.startsWith(home)) {
            return "~" + p.substring(home.length());
        }
        return p;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String toJson(Map<String, Object> args) {
        try {
            return com.pijava.agent.session.SessionJson.mapper().writeValueAsString(args);
        } catch (Exception e) {
            return String.valueOf(args);
        }
    }

    // ── pi formatLabelTimestamp（:862-885）────────────────────

    /** 当天 HH:MM；今年 M/D HH:MM；跨年 YY/M/D HH:MM（本地时区，注入时钟）。 */
    public String formatLabelTimestamp(Instant timestamp) {
        var zone = clock.getZone();
        var date = LocalDate.ofInstant(timestamp, zone);
        var time = LocalTime.ofInstant(timestamp, zone);
        var now = LocalDate.now(clock);
        String hm = "%02d:%02d".formatted(time.getHour(), time.getMinute());
        if (date.equals(now)) {
            return hm;
        }
        if (date.getYear() == now.getYear()) {
            return date.getMonthValue() + "/" + date.getDayOfMonth() + " " + hm;
        }
        return (date.getYear() % 100) + "/" + date.getMonthValue() + "/"
            + date.getDayOfMonth() + " " + hm;
    }

    // ── 状态行/空态（pi :672-676/:763-768）────────────────────

    /** pi 尾部状态行：{@code "  (i+1/N)<statusLabels>"}。 */
    public String statusLine(TreeListModel m) {
        if (m.filteredNodes().isEmpty()) {
            return "  (0/0)" + m.statusLabels();
        }
        return "  (" + (m.selectedIndex() + 1) + "/" + m.filteredNodes().size() + ")"
            + m.statusLabels();
    }

    /** pi :673 空态行。 */
    public static String emptyLine() {
        return "  No entries found";
    }
}
