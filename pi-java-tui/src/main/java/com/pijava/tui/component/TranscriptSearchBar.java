package com.pijava.tui.component;

import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TextColumns;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;

/**
 * L7 W6（S2a）：转录搜索条（三行框线＋输入＋结果计数＋键位提示）。
 *
 * <p>pi 的浮动 overlay 在 TamboUI Column 里无合成面（设计稿 §5 N1）——
 * 以聊天区顶部的同形三行条承载：{@code ┌─┐ / │ input  n/m │ / └─ ↑ prev · ↓ next · Esc close ─┘}。</p>
 */
final class TranscriptSearchBar extends StyledElement<TranscriptSearchBar> {

    private final TranscriptSearchController controller;
    private final KeybindingsManager keybindings;

    TranscriptSearchBar(TranscriptSearchController controller, KeybindingsManager keybindings) {
        this.controller = controller;
        this.keybindings = keybindings;
    }

    String hintText() {
        return "↑ " + KeybindingHints.displayText(
                keybindings.strokeFor(KeybindingsManager.ALT_SEARCH_PREVIOUS))
            + " · ↓ " + KeybindingHints.displayText(
                keybindings.strokeFor(KeybindingsManager.ALT_SEARCH_NEXT))
            + " · Esc close";
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight,
                              RenderContext context) {
        return Size.of(Math.min(availableWidth, 60), 3);
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        int width = area.width();
        if (width < 4) {
            return;
        }
        var buffer = frame.buffer();
        var border = SemanticColors.dim();
        int y = area.top();
        buffer.setString(area.left(), y, "┌" + "─".repeat(width - 2) + "┐", border);
        buffer.setString(area.left(), y + 1, "│", border);
        buffer.setString(area.left() + width - 1, y + 1, "│", border);

        String result = controller.resultText();
        int resultWidth = TextColumns.width(result);
        int inputWidth = Math.max(1, width - 4 - resultWidth - (resultWidth > 0 ? 1 : 0));
        SingleLineInputElement.renderRow(buffer, area.left() + 2, y + 1, inputWidth,
            controller.input(), true, context.currentStyle());
        if (resultWidth > 0) {
            buffer.setString(area.left() + width - 2 - resultWidth, y + 1, result,
                SemanticColors.muted());
        }

        String hint = TextColumns.truncateToWidth(hintText(), Math.max(0, width - 6));
        String tail = "─".repeat(Math.max(0, width - 4 - TextColumns.width(hint)));
        buffer.setString(area.left(), y + 2, "└─ " + hint + " " + tail + "┘", border);
    }
}
