package com.pijava.tui.component.session;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.pijava.tui.component.session.SessionSelectorSearch.NameFilter;
import com.pijava.tui.component.session.SessionTreeBuilder.FlatSessionNode;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TextLayout;

import dev.tamboui.style.Style;

/**
 * pi {@code SessionList.render}（session-selector.ts:422-528）的逐字行形 port
 * （批 14，docs/25 T10）：可视窗居中公式、树前缀、光标 2 列、颜色优先级
 * （确认删除 error &gt; 当前会话 accent &gt; 有名 warning &gt; 默认）、右侧三段
 * （count age、cwd、path 前缀）、滚动指示、空表四文案。
 *
 * <p>搜索输入行＋其下空行由 {@link SessionSelectorComponent} 拼（pi :426-427 同位）。
 * pi 对选中行整行刷 {@code selectedBg} 底色——java 沿批 10 例只加粗选中行文本。</p>
 */
public final class SessionListRenderer {

    /** 一行渲染文本的一个样式段（测试断言面；批 9 TreeListRenderer.Span 手法）。 */
    public record Span(String text, Style style) {}

    /** pi 显示文本的控制字符清洗（:470）：{@code [\x00-\x1f\x7f]} ⇒ 空格。 */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1f\\x7f]");

    private final SessionListModel model;
    private final String toggleNamedKeyText;

    /**
     * @param model               列表状态机
     * @param toggleNamedKeyText  pi {@code keyText("app.session.toggleNamedFilter")}
     *                            的展开串（空表文案用）
     */
    public SessionListRenderer(SessionListModel model, String toggleNamedKeyText) {
        this.model = model;
        this.toggleNamedKeyText = toggleNamedKeyText;
    }

    /** pi {@code render(width)}（:422-528）逐字行形（span 模型＝测试断言面）。 */
    public List<List<Span>> layout(int width) {
        var rows = new ArrayList<List<Span>>();
        var filtered = model.filtered();

        if (filtered.isEmpty()) {
            // pi :429-447：named×showCwd 两维四分文案。
            rows.add(List.of(new Span(
                SessionText.truncateToWidth(emptyMessage(), width, "…"), SemanticColors.muted())));
            return rows;
        }

        // pi :450-454 可视窗公式逐字。
        int startIndex = Math.max(0, Math.min(
            model.selectedIndex() - SessionListModel.MAX_VISIBLE / 2,
            filtered.size() - SessionListModel.MAX_VISIBLE));
        int endIndex = Math.min(startIndex + SessionListModel.MAX_VISIBLE, filtered.size());

        for (int i = startIndex; i < endIndex; i++) {
            rows.add(row(filtered.get(i), i == model.selectedIndex(), width));
        }

        // pi :521-525 滚动指示出现条件逐字。
        if (startIndex > 0 || endIndex < filtered.size()) {
            rows.add(List.of(new Span(
                SessionText.truncateToWidth(
                    "  (" + (model.selectedIndex() + 1) + "/" + filtered.size() + ")", width, ""),
                SemanticColors.muted())));
        }
        return rows;
    }

    /** pi :457-518 单行渲染逐字公式。 */
    private List<Span> row(FlatSessionNode node, boolean isSelected, int width) {
        var session = node.session();
        boolean isConfirmingDelete = session.path().equals(model.confirmingDeletePath());
        boolean isCurrent = model.isCurrentSessionPath(session.path());

        // 树前缀（pi :530-538）。
        var prefix = treePrefix(node);

        // 显示文本（pi :468-470）：name ?? firstMessage，控制字符 ⇒ 空格，trim。
        boolean hasName = session.name() != null;
        var displayText = session.name() != null ? session.name() : session.firstMessage();
        var normalizedMessage = CONTROL_CHARS.matcher(displayText).replaceAll(" ").strip();

        // 右侧（pi :473-481）：{msgCount} {age}；showCwd 前缀 cwd；showPath 前缀 path。
        var rightPart = session.messageCount() + " "
            + SessionTreeBuilder.formatSessionDate(session.modifiedMs());
        if (model.showCwd() && session.cwd() != null && !session.cwd().isEmpty()) {
            rightPart = SessionTreeBuilder.shortenPath(session.cwd()) + " " + rightPart;
        }
        if (model.showPath()) {
            rightPart = SessionTreeBuilder.shortenPath(session.path()) + " " + rightPart;
        }

        // 光标 2 列（pi :484）。
        var cursor = isSelected ? "› " : "  ";

        // 消息可用宽（pi :487-491）。
        int prefixWidth = TextLayout.displayWidth(prefix);
        int rightWidth = TextLayout.displayWidth(rightPart) + 2; // +2 for spacing
        int availableForMsg = width - 2 - prefixWidth - rightWidth; // -2 for cursor
        var truncatedMsg = SessionText.truncateToWidth(normalizedMessage,
            Math.max(10, availableForMsg), "…");

        // 颜色优先级（pi :494-501）：确认删除 error > 当前会话 accent > 有名 warning > 默认。
        Style messageStyle;
        if (isConfirmingDelete) {
            messageStyle = SemanticColors.error();
        } else if (isCurrent) {
            messageStyle = SemanticColors.accent();
        } else if (hasName) {
            messageStyle = SemanticColors.warning();
        } else {
            messageStyle = SemanticColors.plain();
        }
        if (isSelected) {
            // pi :503-505 选中加粗（整行 selectedBg 底色不移植——批 10 同例）。
            messageStyle = messageStyle.bold();
        }

        var spans = new ArrayList<Span>();
        spans.add(new Span(cursor, isSelected ? SemanticColors.accent() : SemanticColors.plain()));
        if (!prefix.isEmpty()) {
            spans.add(new Span(prefix, SemanticColors.dim()));
        }
        spans.add(new Span(truncatedMsg, messageStyle));
        // pi :509-511：间距 ≥1。
        int leftWidth = 2 + prefixWidth + TextLayout.displayWidth(truncatedMsg);
        int spacing = Math.max(1, width - leftWidth - TextLayout.displayWidth(rightPart));
        spans.add(new Span(" ".repeat(spacing), SemanticColors.plain()));
        spans.add(new Span(rightPart,
            isConfirmingDelete ? SemanticColors.error() : SemanticColors.dim()));
        // pi :517 行级截断安全网。
        return SessionText.truncateSpans(spans, width);
    }

    /** pi {@code buildTreePrefix}（:530-538）逐字。 */
    private static String treePrefix(FlatSessionNode node) {
        if (node.depth() == 0) {
            return "";
        }
        var sb = new StringBuilder();
        for (var continues : node.ancestorContinues()) {
            sb.append(continues ? "│  " : "   ");
        }
        sb.append(node.isLast() ? "└─ " : "├─ ");
        return sb.toString();
    }

    /** pi :429-447 空表四文案逐字。 */
    private String emptyMessage() {
        if (model.nameFilter() == NameFilter.NAMED) {
            if (model.showCwd()) {
                return "  No named sessions found. Press " + toggleNamedKeyText + " to show all.";
            }
            return "  No named sessions in current folder. Press " + toggleNamedKeyText
                + " to show all, or Tab to view all.";
        }
        if (model.showCwd()) {
            return "  No sessions found";
        }
        return "  No sessions in current folder. Press Tab to view all.";
    }
}
