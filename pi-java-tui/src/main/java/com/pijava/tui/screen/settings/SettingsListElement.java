package com.pijava.tui.screen.settings;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TextColumns;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;

/**
 * L7 W6（C3，pi {@code components/settings-list.ts} 对位）：宽度感知设置列表。
 *
 * <p>搜索行＋空行；可见窗口居中；标签对齐（≤36 列）＋两空格＋值截断；
 * 滚动指标 {@code (i/n)}；选中项描述按 {@code width-4} 折行；hint 两态逐字；
 * 滚轮移动选中、左键选中/点击激活。键盘路径仍由 {@code SettingsScreen} 驱动。</p>
 */
final class SettingsListElement extends StyledElement<SettingsListElement> {

    private static final int MAX_LABEL = 36;
    private final SettingsListModel model;
    private final Runnable activator;
    private Rect lastArea;
    private int firstRowY;
    private int visibleRows;

    SettingsListElement(SettingsListModel model, Runnable activator) {
        this.model = model;
        this.activator = activator;
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight,
                              RenderContext context) {
        int width = Math.max(10, availableWidth);
        int rows = model.filteredItems().isEmpty() ? 1 : model.visibleItems().size();
        int height = 2 + rows + 1 + 1;
        if (!model.scrollPosition().isEmpty()) {
            height++;
        }
        var selected = model.selected();
        if (selected != null && !selected.description().isEmpty()) {
            height += 1 + wrap(selected.description(), Math.max(1, width - 4)).size();
        }
        return Size.of(width, Math.min(availableHeight, height));
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        lastArea = area;
        var buffer = frame.buffer();
        var base = context.currentStyle();
        int y = area.top();
        buffer.setString(area.left(), y, "> " + model.query(), base);
        int cursorX = area.left() + 2 + TextColumns.width(model.query());
        if (cursorX < area.right()) {
            buffer.setString(cursorX, y, " ", base.reversed());
        }
        y++;
        if (y < area.bottom()) {
            buffer.setString(area.left(), y, " ".repeat(area.width()), base);
            y++;
        }

        firstRowY = y;
        visibleRows = 0;
        if (model.filteredItems().isEmpty()) {
            String message = model.query().isEmpty()
                ? "  No settings available" : "  No matching settings";
            buffer.setString(area.left(), y, message, SemanticColors.dim());
            y++;
            visibleRows = 1;
        } else {
            int maxLabel = maxLabelWidth();
            for (var item : model.visibleItems()) {
                if (y >= area.bottom()) {
                    break;
                }
                renderItem(buffer, area, y, item, maxLabel, base);
                y++;
                visibleRows++;
            }
            if (!model.scrollPosition().isEmpty() && y < area.bottom()) {
                buffer.setString(area.left(), y, model.scrollPosition(), SemanticColors.dim());
                y++;
            }
        }
        if (y < area.bottom()) {
            y++;
        }
        var selected = model.selected();
        if (!model.filteredItems().isEmpty() && selected != null
                && !selected.description().isEmpty()) {
            for (String line : wrap(selected.description(), Math.max(1, area.width() - 4))) {
                if (y >= area.bottom()) {
                    break;
                }
                buffer.setString(area.left(), y,
                    TextColumns.truncateToWidth("  " + line, area.width()),
                    SemanticColors.dim());
                y++;
            }
            y++;
        }
        if (y < area.bottom()) {
            String hint = model.query().isEmpty()
                ? "  Enter/Space to change · Esc to cancel"
                : "  Type to search · Enter/Space to change · Esc to cancel";
            buffer.setString(area.left(), y,
                TextColumns.truncateToWidth(hint, area.width()), SemanticColors.dim());
        }
    }

    private void renderItem(Buffer buffer, Rect area, int y, SettingsItem item,
                            int maxLabel, Style base) {
        boolean selected = item == model.selected();
        var style = selected ? base.patch(SemanticColors.accent()) : base;
        String label = item.label();
        int labelWidth = TextColumns.width(label);
        String padded = label + " ".repeat(Math.max(0, maxLabel - labelWidth));
        String value = item.currentValue() == null ? "" : item.currentValue();
        int prefixWidth = 2;
        int used = prefixWidth + maxLabel + 2;
        int available = Math.max(0, area.width() - used);
        String truncated = TextColumns.truncateToWidth(value, available);
        buffer.setString(area.left(), y,
            (selected ? "→ " : "  ") + padded + "  " + truncated, style);
    }

    private int maxLabelWidth() {
        int max = 0;
        for (var item : model.visibleItems()) {
            max = Math.max(max, TextColumns.width(item.label()));
        }
        return Math.min(MAX_LABEL, max);
    }

    private static List<String> wrap(String text, int width) {
        var out = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        var line = new StringBuilder();
        int used = 0;
        for (var word : text.split(" ")) {
            int w = TextColumns.width(word);
            if (used > 0 && used + 1 + w > width) {
                out.add(line.toString());
                line.setLength(0);
                used = 0;
            }
            if (used > 0) {
                line.append(' ');
                used++;
            }
            if (w > width) {
                String rest = word;
                while (TextColumns.width(rest) > width) {
                    String head = TextColumns.truncateToWidth(rest, width);
                    out.add(head);
                    rest = rest.substring(Math.min(rest.length(), head.length()));
                }
                line.append(rest);
                used = TextColumns.width(rest);
            } else {
                line.append(word);
                used += w;
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    @Override
    public EventResult handleMouseEvent(MouseEvent event) {
        if (event.kind() == MouseEventKind.SCROLL_UP) {
            model.move(-1);
            return EventResult.HANDLED;
        }
        if (event.kind() == MouseEventKind.SCROLL_DOWN) {
            model.move(1);
            return EventResult.HANDLED;
        }
        if (event.kind() != MouseEventKind.PRESS || lastArea == null || !event.isLeftButton()) {
            return EventResult.UNHANDLED;
        }
        int row = event.y() - firstRowY;
        if (row < 0 || row >= visibleRows || model.filteredItems().isEmpty()) {
            return EventResult.UNHANDLED;
        }
        int index = Math.max(0, Math.min(model.filteredItems().size() - 1,
            model.visibleStartIndex() + row));
        if (index == model.selectedIndex() && activator != null) {
            activator.run();
        } else {
            model.selectIndex(index);
        }
        return EventResult.HANDLED;
    }
}
