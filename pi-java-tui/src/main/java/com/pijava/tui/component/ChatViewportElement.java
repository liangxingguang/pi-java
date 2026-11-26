package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.util.TamboUIAdapter;
import com.pijava.tui.util.TextLayout;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.event.DragHandler;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;

/**
 * Session transcript viewport (Phase 3 alignment design §5.2): width-agnostic
 * {@link LogicalLine}s are wrapped into {@link RenderRow}s each frame and the
 * viewport scrolls by physical rows (fixes ListWidget slicing and pre-wrapped
 * width-cache defects). Also hosts inline images via the raw-output channel.
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
         * L7 W6（S2a）：把目标行滚入视口（尽量居中），并暂停跟随底部。
         *
         * @param row         目标行号
         * @param totalRows   当前总行数
         * @param visibleRows 视口高度
         */
        public void scrollToRow(int row, int totalRows, int visibleRows) {
            int max = maxOffset(totalRows, visibleRows);
            int target = Math.max(0, row - Math.max(0, visibleRows / 2));
            offset = Math.max(0, Math.min(target, max));
            userScrolledAway = offset < max;
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

    /** 批 12 T11：pi {@code fullscreenScrollbar} 三态（AUTO/ALWAYS 常驻；HIDDEN
     *  永不渲染且不让列；窄差 D61＝无 transient 计时面）。 */
    public enum ScrollbarMode { AUTO, ALWAYS, HIDDEN }

    private final ScrollState scrollState = new ScrollState();
    private List<ChatMessage> messages = List.of();
    private ChatMessage draft;
    private boolean scrollbarEnabled = true;
    /** 渲染期现读 ⇒ 改值下一帧生效（天然热更，docs/23 §3 T11）。 */
    private java.util.function.Supplier<ScrollbarMode> scrollbarMode =
        () -> ScrollbarMode.ALWAYS;
    private int lastRowCount;
    private int lastVisibleRows = 1;
    private Rect lastArea;
    private boolean scrollbarHovered;
    private boolean scrollbarDragging;
    /** 本帧 committed 消息的行区间（flatten 与 lastRowCount 同帧更新）。 */
    private List<int[]> messageRowRanges = List.of();
    /** L7 W2（K37/C14）：卡类消息 per-card 展开态（null ≙ 全局 toolsExpanded 回落）。 */
    private final java.util.Map<Integer, Boolean> cardExpanded = new java.util.HashMap<>();
    /** 点击切换后的回调（重绘/test 观察点）。 */
    private java.util.function.IntConsumer cardToggler;
    /** L7 W3（R31）：Markdown 顶层块缓存（视口独占，帧界裁剪）。 */
    private final MarkdownRenderCache markdownCache = new MarkdownRenderCache();
    /** L7 W7（R14）：本帧图片锚点（绝对行 + 载荷）。 */
    private List<ImageRenderSupport.Anchor> imageRows = List.of();
    /** L7 W6（S2a）：搜索/选区高亮（显示列区间；本帧渲染时应用）。 */
    public record Highlight(int row, int startCol, int endCol, boolean current) {}
    private final ViewportInteraction interaction = new ViewportInteraction();
    private int lastContentWidth = 1;

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
                    return;
                }
                // L7 W6（S2b）：内容区按下＝选区锚点；Windows X10 对每个拖拽位置
                // 重复 PRESS ⇒ 已按下时按拖动处理。
                if (lastArea != null && lastArea.contains(startX, startY)) {
                    interaction.press(startX, startY, lastArea,
                        scrollState.offset(), lastContentWidth);
                }
            }

            @Override
            public void onDrag(int currentX, int currentY, int deltaX, int deltaY) {
                if (scrollbarDragging) {
                    scrollToMouseY(currentY);
                    return;
                }
                if (lastArea != null) {
                    interaction.drag(currentX, currentY, lastArea,
                        scrollState.offset(), lastContentWidth);
                }
            }

            @Override
            public void onDragEnd(int endX, int endY) {
                if (scrollbarDragging) {
                    scrollbarDragging = false;
                    scrollbarHovered = inScrollbar(endX, endY);
                    return;
                }
                // L7 W6（S2b）＋E2-54：拖出非空选区 ⇒ 按 fullscreenCopyOnSelect 决定是否复制。
                if (interaction.finishAndCopy()) {
                    return;
                }
                // L7 W2（K37/C14）：PRESS 被 toolkit 的 startDrag 吞掉（routeMouseEvent
                // 对 draggable 元素只走 drag 通道），RELEASE 经 onDragEnd 送达——非滚动条
                // 臂即内容点击 ⇒ 命中卡类消息行块则切换展开态（C14 窄差 N：MouseRegion
                // 组件不移植，拖拽通道上的 press→release 承担同可观察行为）。
                if (endX >= 0 && !inScrollbar(endX, endY)) {
                    int index = cardMessageAt(endY);
                    if (index >= 0) {
                        toggleCard(index);
                        if (cardToggler != null) {
                            cardToggler.accept(index);
                        }
                    }
                }
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

    /** 批 12 T11：注入滚动条三态现读源（{@code uiPrefs().getFullscreenScrollbar()} 对位）。 */
    public ChatViewportElement scrollbarMode(java.util.function.Supplier<ScrollbarMode> source) {
        this.scrollbarMode = source == null ? () -> ScrollbarMode.ALWAYS : source;
        return this;
    }

    /** L7 W2（K37）：注入点击切换回调（重绘钩子；null ⇒ 仅改状态）。 */
    public ChatViewportElement cardToggler(java.util.function.IntConsumer toggler) {
        this.cardToggler = toggler;
        return this;
    }

    /** L7 W2（K37）：per-card 展开态读取（{@code MessageBubble.lines(msg, i, …)} 消费）。 */
    Boolean cardExpandedAt(int index) {
        return cardExpanded.get(index);
    }

    /** 清空 per-card 展开态（{@code ChatPanel.clear()} 对位）。 */
    public void clearCardState() {
        cardExpanded.clear();
    }

    // ── L7 W6：搜索高亮 / 选区（S2a/S2b） ─────────────────────────

    /** 注入搜索高亮（本帧渲染应用；空列表清除）。 */
    public void setHighlights(List<Highlight> value) {
        interaction.setHighlights(value);
    }

    /** 当前搜索高亮（测试观察点）。 */
    public List<Highlight> highlights() {
        return interaction.highlights();
    }

    /** 本帧渲染行快照（搜索语料/选区提取读面）。 */
    public List<RenderRow> rowsSnapshot() {
        return interaction.rows();
    }

    /** 把目标行滚入视口（搜索命中导航）。 */
    public void scrollToRow(int row) {
        scrollState.scrollToRow(row, lastRowCount, Math.max(1, lastVisibleRows));
    }

    /** 注入复制回调（copy-on-select；由 ChatScreenLayout 接到剪贴板＋flash）。 */
    public void setSelectionSink(java.util.function.Consumer<String> sink) {
        interaction.setSink(sink);
    }
    void setCopyOnSelectSource(java.util.function.BooleanSupplier source) {
        interaction.setCopyOnSelectSource(source);
    }

    /** 是否有非空选区。 */
    public boolean hasActiveSelection() {
        return interaction.hasActiveSelection();
    }

    /** 复制当前选区；有选区 ⇒ true（{@code ctrl+c} 用）。 */
    public boolean copyActiveSelection() {
        return interaction.copyActiveSelection();
    }

    /** 清空选区（{@code /clear}/整表重建）。 */
    public void clearSelection() {
        interaction.clearSelection();
    }

    /** 滚动条本帧是否占位（HIDDEN ⇒ 不渲染、内容区不让列）。 */
    private boolean scrollbarVisible() {
        return scrollbarEnabled && scrollbarMode.get() != ScrollbarMode.HIDDEN;
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
        // K37/C14 的点击臂在 DragHandler.onDragEnd（见构造器注释：PRESS 被
        // startDrag 吞掉，本方法收不到 PRESS/拖拽 RELEASE）。
        return EventResult.UNHANDLED;
    }

    /** 点击行 → 卡类消息的 committed 索引；非卡/越界 ⇒ -1。 */
    private int cardMessageAt(int y) {
        if (lastArea == null || y < lastArea.y()) {
            return -1;
        }
        int row = y - lastArea.y() + scrollState.offset();
        var ranges = messageRowRanges;
        for (int i = 0; i < ranges.size() && i < messages.size(); i++) {
            var range = ranges.get(i);
            if (row >= range[0] && row < range[1] && isCardMessage(messages.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** K37 的三卡＋bash 卡（Padded 烘焙包装解包后判型）。 */
    private static boolean isCardMessage(ChatMessage message) {
        var inner = message instanceof ChatMessage.Padded padded ? padded.inner() : message;
        return inner instanceof ChatMessage.CompactionCard
            || inner instanceof ChatMessage.BranchCard
            || inner instanceof ChatMessage.SkillCard
            || inner instanceof ChatMessage.BashCard;
    }

    /** 切换 per-card 展开态：现值（无 ⇒ 全局展开态）取反（pi setExpanded(!expanded)）。 */
    private void toggleCard(int index) {
        boolean current = cardExpanded.containsKey(index)
            ? cardExpanded.get(index)
            : MessageBubbleVisibility.toolsExpanded();
        cardExpanded.put(index, !current);
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
        int scrollbarWidth = scrollbarVisible() ? currentScrollbarWidth() : 0;
        int contentWidth = Math.max(1, area.width() - scrollbarWidth);
        var rows = flatten(contentWidth);
        interaction.setRows(rows);
        lastContentWidth = contentWidth;
        lastRowCount = rows.size();
        lastVisibleRows = area.height();
        scrollState.clamp(rows.size(), area.height());

        for (int i = 0; i < area.height() && scrollState.offset() + i < rows.size(); i++) {
            int rowIndex = scrollState.offset() + i;
            frame.buffer().setLine(area.x(), area.y() + i, toLine(rows.get(rowIndex), rowIndex));
        }

        renderImages(frame, area, contentWidth);

        if (scrollbarWidth > 0) {
            renderScrollbar(frame, area, rows.size(), context, scrollbarWidth);
        }
    }

    /** L7 W7（R14/S2d）：把落在视口内的图片锚点交给原始输出通道绘制。 */
    private void renderImages(Frame frame, Rect area, int contentWidth) {
        ImageRenderSupport.render(frame, area, contentWidth, scrollState.offset(), imageRows);
    }

    private List<RenderRow> flatten(int contentWidth) {
        markdownCache.beginFrame();
        try {
            var rows = new ArrayList<RenderRow>();
            var ranges = new ArrayList<int[]>(messages.size());
            for (int mi = 0; mi < messages.size(); mi++) {
                var message = messages.get(mi);
                if (!rows.isEmpty()) {
                    rows.add(new RenderRow("", Style.EMPTY)); // blank row between blocks
                }
                // 批 12 T6：outputPad 烘焙消息的右 pad＝内容宽 −2×pad（左 pad 由
                // MessageBubble 的 indent 承担）；块间空行不动（pi 的 pad 是水平向）。
                int pad = ChatMessage.padOf(message);
                int start = rows.size();
                rows.addAll(TextLayout.wrap(
                    MessageBubble.lines(message, mi, cardExpanded::get, markdownCache),
                    Math.max(1, contentWidth - 2 * pad)));
                ranges.add(new int[] {start, rows.size()});
            }
            messageRowRanges = ranges;
            imageRows = ImageRenderSupport.collect(rows);
            if (draft != null) {
                rows.addAll(TextLayout.wrap(
                    MessageBubble.lines(draft, -1, index -> null, markdownCache), contentWidth));
            }
            return rows;
        } finally {
            markdownCache.endFrame();   // R31：只保留本帧用到的源与块
        }
    }

    /** L7 W3（R31）测试观察点：视口的 Markdown 块缓存。 */
    MarkdownRenderCache markdownCache() {
        return markdownCache;
    }

    /** Parses markup into a styled, single-row line; applies search/selection ranges (L7 W6). */
    private Line toLine(RenderRow row, int rowIndex) {
        // 批 25（docs/36 T2 补）：共享口——行 style 作底、span 优先。
        var ranges = interaction.rangesForRow(rowIndex);
        return ranges.isEmpty()
            ? TextLayout.toLine(row)
            : com.pijava.tui.util.LineHighlighter.apply(row, ranges);
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
        if (lastArea == null || !scrollbarVisible()) {
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
