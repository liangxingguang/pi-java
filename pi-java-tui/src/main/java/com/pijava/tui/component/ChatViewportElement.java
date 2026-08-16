package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.util.TamboUIAdapter;
import com.pijava.tui.util.TextLayout;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.MarkupParser;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.event.DragHandler;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;

/**
 * Session transcript viewport (Phase 3 alignment design §5.2), replacing the
 * {@code ListElement} chat container. Aligned with Codex TUI2's HistoryCell:
 * messages are stored as width-agnostic {@link LogicalLine}s, wrapped into
 * {@link RenderRow}s at the current width each frame, and the viewport scrolls
 * by physical rows.
 *
 * <p>This eliminates both documented root causes of the old container: slicing
 * by row index renders the correct continuation of a partially visible message
 * (ListWidget startLine defect), and reflow-at-render keeps content byte-identical
 * across resize/scroll (pre-wrapped width cache defect).</p>
 */
public final class ChatViewportElement extends StyledElement<ChatViewportElement> {

    /** Row-level scroll state: offset, follow semantics, and clamping. */
    public static final class ScrollState {
        private int offset;
        private boolean userScrolledAway;

        /** The current first visible row index. */
        public int offset() {
            return offset;
        }

        /** Whether the user scrolled away from the sticky bottom. */
        public boolean userScrolledAway() {
            return userScrolledAway;
        }

        /**
         * Normalized scroll entry: moves the offset by {@code delta} rows and
         * updates the follow flag. Scrolling up marks the user as away;
         * reaching the bottom again resumes follow.
         *
         * @param delta        rows to scroll (negative = up)
         * @param totalRows    current total row count
         * @param visibleRows  current viewport height in rows
         */
        public void scrollByRows(int delta, int totalRows, int visibleRows) {
            if (delta == 0) {
                return;
            }
            if (delta < 0) {
                userScrolledAway = true;
            }
            int max = maxOffset(totalRows, visibleRows);
            offset = Math.max(0, Math.min(offset + delta, max));
            if (offset == max) {
                userScrolledAway = false;
            }
        }

        /** Jumps to the top and pauses follow. */
        public void scrollToTop() {
            offset = 0;
            userScrolledAway = true;
        }

        /** Jumps to the bottom and resumes follow (clamped on next render). */
        public void scrollToBottom() {
            offset = Integer.MAX_VALUE;
            userScrolledAway = false;
        }

        /**
         * Clamps the offset after resize or new content. While following, the
         * viewport pins to the bottom; while scrolled away, the offset is only
         * bounded so the user does not lose their place.
         *
         * @param totalRows    current total row count
         * @param visibleRows  current viewport height in rows
         */
        public void clamp(int totalRows, int visibleRows) {
            int max = maxOffset(totalRows, visibleRows);
            offset = userScrolledAway
                ? Math.max(0, Math.min(offset, max))
                : max;
        }

        private static int maxOffset(int totalRows, int visibleRows) {
            if (totalRows <= 0) {
                return 0;
            }
            return Math.max(0, totalRows - Math.max(1, visibleRows));
        }
    }

    private final ScrollState scrollState = new ScrollState();
    private List<ChatMessage> messages = List.of();
    private ChatMessage draft;
    private boolean scrollbarEnabled = true;
    private int lastRowCount;
    private int lastVisibleRows = 1;
    private Rect lastArea;
    private boolean scrollbarHovered;
    private boolean scrollbarDragging;

    /** Creates a chat viewport with scrollbar-drag (Codex-CLI style) handling. */
    public ChatViewportElement() {
        // Scrollbar drag (Codex-CLI style): pressing the thumb scrolls the
        // transcript proportionally to the pointer row; Windows X10 input
        // repeats PRESS events for every dragged position, so onDragStart is
        // the position channel there, while SGR terminals use onDrag.
        onDrag(new DragHandler() {
            @Override
            public void onDragStart(int startX, int startY) {
                if (scrollbarDragging || inScrollbar(startX, startY)) {
                    scrollbarDragging = true;
                    scrollbarHovered = true;
                    scrollToMouseY(startY);
                }
            }

            @Override
            public void onDrag(int currentX, int currentY, int deltaX, int deltaY) {
                if (scrollbarDragging) {
                    scrollToMouseY(currentY);
                }
            }

            @Override
            public void onDragEnd(int endX, int endY) {
                scrollbarDragging = false;
                scrollbarHovered = inScrollbar(endX, endY);
            }
        });
    }

    /**
     * Sets the committed messages and the in-flight draft (null = none). The
     * draft renders as the last message inside the viewport, so streaming
     * never produces a separate floating bubble.
     *
     * @param messages committed messages
     * @param draft    streaming draft message, or null
     * @return this element for chaining
     */
    public ChatViewportElement messages(List<ChatMessage> messages, ChatMessage draft) {
        this.messages = messages == null ? List.of() : List.copyOf(messages);
        this.draft = draft;
        return this;
    }

    /** Toggles the right-hand scrollbar. */
    public ChatViewportElement scrollbar(boolean enabled) {
        this.scrollbarEnabled = enabled;
        return this;
    }

    /** The row-level scroll state (driver entry point for the app shell). */
    public ScrollState scrollState() {
        return scrollState;
    }

    /** The last rendered viewport height in rows (page-scroll driver). */
    public int visibleRows() {
        return Math.max(1, lastVisibleRows);
    }

    /** The last rendered total row count (clamping driver). */
    public int rowCount() {
        return lastRowCount;
    }

    @Override
    public EventResult handleMouseEvent(MouseEvent event) {
        if (event.kind() == MouseEventKind.MOVE) {
            if (scrollbarDragging) {
                // Windows X10 encodes the release as a buttonless move: the
                // first MOVE after a drag ends it (SGR terminals end the drag
                // through the router's RELEASE path instead).
                scrollbarDragging = false;
                scrollbarHovered = inScrollbar(event.x(), event.y());
            } else {
                scrollbarHovered = inScrollbar(event.x(), event.y());
            }
            return scrollbarHovered ? EventResult.HANDLED : EventResult.UNHANDLED;
        }
        return EventResult.UNHANDLED;
    }

    /** Whether the pointer currently hovers the scrollbar (test hook). */
    public boolean scrollbarHovered() {
        return scrollbarHovered;
    }

    /** Whether a scrollbar drag is in progress (test hook). */
    public boolean scrollbarDragging() {
        return scrollbarDragging;
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight,
                              RenderContext context) {
        // The viewport is a fill container: the parent column decides its area.
        return Size.of(0, 0);
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        lastArea = area;
        int scrollbarWidth = scrollbarEnabled ? currentScrollbarWidth() : 0;
        int contentWidth = Math.max(1, area.width() - scrollbarWidth);
        var rows = flatten(contentWidth);
        lastRowCount = rows.size();
        lastVisibleRows = area.height();
        scrollState.clamp(rows.size(), area.height());

        for (int i = 0; i < area.height() && scrollState.offset() + i < rows.size(); i++) {
            frame.buffer().setLine(area.x(), area.y() + i, toLine(rows.get(scrollState.offset() + i)));
        }

        if (scrollbarWidth > 0) {
            renderScrollbar(frame, area, rows.size(), context, scrollbarWidth);
        }
    }

    private List<RenderRow> flatten(int contentWidth) {
        var rows = new ArrayList<RenderRow>();
        for (var message : messages) {
            if (!rows.isEmpty()) {
                rows.add(new RenderRow("", Style.EMPTY)); // blank row between blocks
            }
            rows.addAll(TextLayout.wrap(MessageBubble.lines(message), contentWidth));
        }
        if (draft != null) {
            rows.addAll(TextLayout.wrap(MessageBubble.lines(draft), contentWidth));
        }
        return rows;
    }

    /** Parses markup into a styled, single-row line (visible rows only). */
    private static Line toLine(RenderRow row) {
        Text parsed = MarkupParser.parse(row.text());
        Line line = parsed.lines().isEmpty() ? Line.empty() : parsed.lines().get(0);
        return row.style().equals(Style.EMPTY) ? line : line.patchStyle(row.style());
    }

    private void renderScrollbar(Frame frame, Rect area, int rowCount,
                                 RenderContext context, int width) {
        boolean active = scrollbarHovered || scrollbarDragging;
        var thumb = resolveEffectiveStyle(context,
            active ? "scrollbar-thumb-hover" : "scrollbar-thumb", null, Style.EMPTY);
        var track = resolveEffectiveStyle(context,
            active ? "scrollbar-track-hover" : "scrollbar-track", null, Style.EMPTY);
        var state = TamboUIAdapter.scrollbarState(rowCount, area.height(), scrollState.offset());
        frame.renderStatefulWidget(TamboUIAdapter.verticalScrollbar(thumb, track),
            new Rect(area.right() - width, area.y(), width, area.height()), state);
    }

    /** The current scrollbar width: 1 cell, or 2 while hovered/dragging. */
    private int currentScrollbarWidth() {
        return scrollbarHovered || scrollbarDragging ? 2 : 1;
    }

    /** Whether the pointer position falls on the scrollbar column(s). */
    private boolean inScrollbar(int x, int y) {
        if (lastArea == null || !scrollbarEnabled) {
            return false;
        }
        int width = currentScrollbarWidth();
        return x >= lastArea.right() - width && x < lastArea.right()
            && y >= lastArea.y() && y < lastArea.bottom();
    }

    /** Maps a pointer row inside the viewport to a scroll offset. */
    private void scrollToMouseY(int y) {
        if (lastArea == null || lastArea.height() <= 0) {
            return;
        }
        int total = Math.max(0, lastRowCount);
        int visible = Math.max(1, lastArea.height());
        int max = Math.max(0, total - visible);
        double fraction = (y - lastArea.y()) / (double) lastArea.height();
        int target = (int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * max);
        scrollState.scrollByRows(target - scrollState.offset(), total, visible);
    }
}
