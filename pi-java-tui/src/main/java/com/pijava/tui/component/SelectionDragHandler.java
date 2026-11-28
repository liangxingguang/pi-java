package com.pijava.tui.component;

import dev.tamboui.toolkit.event.DragHandler;

/**
 * L7 W8（S2）：视口拖拽通道——滚动条拖动与内容选区（按下/移动/释放）。
 * 抽成独立类以守 {@link ChatViewportElement} 的 500 行 Checkstyle 限；
 * 状态仍由视口与 {@link ViewportInteraction} 持有（包私有缝）。
 *
 * <p>SGR 终端拖动走 {@code onDrag}，Windows X10 对每个拖动位置重复 PRESS
 * （走 {@code onDragStart}）；释放走 RELEASE 或按钮无关的 MOVE。</p>
 */
final class SelectionDragHandler implements DragHandler {

    private final ChatViewportElement viewport;

    SelectionDragHandler(ChatViewportElement viewport) {
        this.viewport = viewport;
    }

    @Override
    public void onDragStart(int startX, int startY) {
        if (viewport.scrollbarDragging || viewport.inScrollbar(startX, startY)) {
            viewport.scrollbarDragging = true;
            viewport.scrollbarHovered = true;
            viewport.scrollToMouseY(startY);
            return;
        }
        // L7 W6（S2b）：内容区按下＝选区锚点；Windows X10 对每个拖拽位置
        // 重复 PRESS ⇒ 已按下时按拖动处理并更新自动滚动方向。
        boolean continuing = viewport.interaction.selectionActive();
        if (viewport.lastArea != null && viewport.lastArea.contains(startX, startY)) {
            viewport.interaction.press(startX, startY, viewport.lastArea,
                viewport.scrollState().offset(), viewport.lastContentWidth,
                viewport.selectionClock.getAsLong());
        }
        if (continuing) {
            viewport.interaction.setPointer(startX, startY, viewport.lastArea);
        }
    }

    @Override
    public void onDrag(int currentX, int currentY, int deltaX, int deltaY) {
        if (viewport.scrollbarDragging) {
            viewport.scrollToMouseY(currentY);
            return;
        }
        if (viewport.lastArea != null) {
            viewport.interaction.drag(currentX, currentY, viewport.lastArea,
                viewport.scrollState().offset(), viewport.lastContentWidth);
            viewport.interaction.setPointer(currentX, currentY, viewport.lastArea);
        }
    }

    @Override
    public void onDragEnd(int endX, int endY) {
        if (viewport.scrollbarDragging) {
            viewport.scrollbarDragging = false;
            viewport.scrollbarHovered = viewport.inScrollbar(endX, endY);
            return;
        }
        viewport.releaseSelection(endX, endY);
    }
}
