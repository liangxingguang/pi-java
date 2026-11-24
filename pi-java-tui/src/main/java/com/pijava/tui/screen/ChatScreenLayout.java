package com.pijava.tui.screen;

import com.pijava.agent.harness.SessionSnapshot;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.ChatPanel;
import com.pijava.tui.component.MetaKind;
import com.pijava.tui.component.PendingQueueView;
import com.pijava.tui.component.StatusBar;
import com.pijava.tui.component.StatusIndicator;
import com.pijava.tui.component.TranscriptSearchController;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.ClipboardSupport;
import com.pijava.tui.util.TamboUIAdapter;
import com.pijava.tui.util.TextColumns;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.tui.event.KeyEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 批 29（E2 结构债拆分——render()/renderBottomArea() 槽位拼装下沉件，行为
 * 零变化）：渲染装配（fullscreen 主区／inline 底部位／状态栏）＋ chatPanel
 * 直面 API（滚动、转录读面、系统/markdown 追加、测试钩子）。快照自持有
 * （{@code updateSnapshot} 现写、statusBar 现读，同原 ChatScreen 字段语义）。
 */
final class ChatScreenLayout {

    private final ChatPanel chatPanel;
    private final PendingQueueView pendingQueue;
    private final ExtensionInjectionLayer extensionLayer;
    private final ChatInputPortal inputPortal;
    private final SessionEventRouter sessionEventRouter;
    private final TranscriptSearchController search;
    private final FlashNotice flashNotice = new FlashNotice();
    private SessionSnapshot snapshot;

    ChatScreenLayout(ChatPanel chatPanel, PendingQueueView pendingQueue,
                     ExtensionInjectionLayer extensionLayer, ChatInputPortal inputPortal,
                     SessionEventRouter sessionEventRouter) {
        this.chatPanel = chatPanel;
        this.pendingQueue = pendingQueue;
        this.extensionLayer = extensionLayer;
        this.inputPortal = inputPortal;
        this.sessionEventRouter = sessionEventRouter;
        this.search = new TranscriptSearchController(
            chatPanel::rowsSnapshot, chatPanel::scrollOffset, chatPanel::scrollToRow);
        chatPanel.setSelectionSink(this::copySelection);
    }

    /**
     * Render the main chat area: transcript viewport, one blank row, then the
     * input row. There is no standalone draft bubble or separator — the draft
     * lives inside the viewport (Codex-CLI style).
     */
    Column render() {
        var children = new ArrayList<Element>();
        // L7 W6（S2a）：搜索条在聊天区顶部（pi 浮动 overlay 的顶条承载，窄差 N1）。
        if (search.active()) {
            children.add(search.bar());
        }
        // 批 17（T6）：header 槽在聊天区上方（pi headerContainer 槽位序对位）。
        children.addAll(extensionLayer.headerRows());
        children.add(chatPanel.render().fill());
        Element pending = pendingQueue.render();
        if (pending != null) {
            children.add(pending);
        }
        Element popup = inputPortal.completerElement();
        if (popup != null) {
            children.add(popup);
        }
        // 批 17（T6）：widget 上区＝编辑器上（pi 槽位序逐字；空区保留 Spacer(1)
        // —— 替位批 17 前这里的手写 spacer(1)，视觉不变）。
        children.addAll(extensionLayer.widgetRowsAbove(true, true));
        children.add(inputPortal.editorElement());
        children.addAll(extensionLayer.widgetRowsBelow(false, false));
        addFlash(children);
        return TamboUIAdapter.column(children);
    }

    /**
     * Bottom region for regular (raw-scrollback) mode: editor + status bar.
     * The transcript lives in the terminal scrollback, so only this fixed
     * region is redrawn in place.
     */
    Element renderBottomArea() {
        var children = new ArrayList<Element>();
        // L7 W6（S2a）：inline 模式同样在顶部渲染搜索条。
        if (search.active()) {
            children.add(search.bar());
        }
        // 批 17（T6，窄差 D101）：inline 的 header 槽渲染在底部位顶部
        //（pi 槽位序为聊天上方；java inline 无聊天容器）。
        children.addAll(extensionLayer.headerRows());
        Element popup = inputPortal.completerElement();
        if (popup != null) {
            children.add(popup);
        }
        children.addAll(extensionLayer.widgetRowsAbove(true, true));
        children.add(inputPortal.editorElement());
        children.addAll(extensionLayer.widgetRowsBelow(false, false));
        children.add(statusBar());
        addFlash(children);
        return TamboUIAdapter.column(children);
    }

    /**
     * Bottom status bar (indicator first, then the snapshot, then an empty row).
     *
     * <p>指示器优先于快照：pi 把重试/压缩的进度放在同一个状态槽里
     * （{@code showStatusIndicator}），状态栏的形状承载不了它（{@code SessionSnapshot}
     * 没有重试状态位，原 docs/31 §8.38.2-(2)）。</p>
     */
    Element statusBar() {
        // 批 17（T6）：扩展状态段在状态行上方（pi footer.ts:289-298 的段序对位）；
        // 自定义 footer 替换内置 StatusBar（pi setExtensionFooter 对位）；
        // 指示器仍最优先（既有语义不动）。
        var children = new ArrayList<Element>();
        var statusLine = extensionLayer.extensionStatusLine();
        if (statusLine != null) {
            children.add(statusLine);
        }
        var active = sessionEventRouter.indicator();
        if (active != null) {
            // L7 W1（docs/22 §4.4，C5/C6）：spinner + 文案 span 组——pi 四 kind 全是
            // Loader ⇒ 全带 spinner；配色照 status-indicator.ts（working/compaction/
            // branchSummary spinner accent、retry spinner warning，message 一律 muted）。
            long now = System.nanoTime();
            var frame = active.spinnerFrameAt(now);
            var spans = new ArrayList<dev.tamboui.text.Span>();
            if (!frame.isEmpty()) {
                spans.add(new dev.tamboui.text.Span(" " + frame + " ", spinnerStyle(active.kind())));
            }
            spans.add(new dev.tamboui.text.Span(active.textAt(now), SemanticColors.muted()));
            children.add(TamboUIAdapter.richText(
                dev.tamboui.text.Text.from(dev.tamboui.text.Line.from(spans))));
        } else if (extensionLayer.hasCustomFooter()) {
            children.addAll(extensionLayer.customFooterRows());
        } else {
            children.add(snapshot == null
                ? TamboUIAdapter.row().length(1)
                : new StatusBar().render(snapshot));
        }
        return children.size() == 1 ? children.get(0) : TamboUIAdapter.column(children);
    }

    /** 指示器 spinner 配色（pi status-indicator.ts：retry ⇒ warning，余 ⇒ accent）。 */
    private static dev.tamboui.style.Style spinnerStyle(StatusIndicator.Kind kind) {
        return kind == StatusIndicator.Kind.RETRY
            ? SemanticColors.warning() : SemanticColors.accent();
    }

    /** Refresh the status bar from a session snapshot. */
    void updateSnapshot(SessionSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    // ── L7 W6：搜索 / 选择 / flash（S2a/S2b/S2c） ─────────────────

    /** 开关转录搜索（打开/关闭均同步高亮）。 */
    void toggleTranscriptSearch() {
        search.toggle();
        chatPanel.setHighlights(search.highlights());
    }

    /** 搜索激活时先吃键；返回是否消费。 */
    boolean handleTranscriptSearchKey(KeyEvent event, KeybindingsManager keys) {
        if (!search.active()) {
            return false;
        }
        boolean handled = search.handleKey(event, keys);
        chatPanel.setHighlights(search.highlights());
        return handled;
    }

    /** L7 W6（C15/C20）：编辑器边框色（thinking 等级）与硬件光标源注入。 */
    void configureEditor(java.util.function.Supplier<Style> border,
                         java.util.function.BooleanSupplier hardwareCursor) {
        inputPortal.setEditorBorderStyleSource(border);
        inputPortal.setShowHardwareCursorSource(hardwareCursor);
    }

    /** 转录搜索控制器（测试观察点）。 */
    TranscriptSearchController search() {
        return search;
    }

    /** 复制当前选区（{@code ctrl+c}）；有选区 ⇒ true。 */
    boolean copyActiveSelection() {
        return chatPanel.copyActiveSelection();
    }

    /** 是否有非空选区（测试观察点）。 */
    boolean hasActiveSelection() {
        return chatPanel.hasActiveSelection();
    }

    /** 显示 flash（默认 1000 ms）。 */
    void flash(String message) {
        flashNotice.show(message);
    }

    /** 显示 flash（定制时长）。 */
    void flash(String message, long durationMs) {
        flashNotice.show(message, durationMs);
    }

    /** flash 文本（测试观察点；过期 ⇒ null）。 */
    String flashMessageAt(long nowNanos) {
        return flashNotice.messageAt(nowNanos);
    }

    private void copySelection(String text) {
        try {
            ClipboardSupport.copy(text);
            flashNotice.show("Copied!");
        } catch (IOException e) {
            var detail = e.getMessage() == null ? "Copy failed"
                : "Copy failed: " + e.getMessage();
            flashNotice.show(detail, 5000);
        }
    }

    private void addFlash(List<Element> children) {
        String message = flashNotice.messageAt(System.nanoTime());
        if (message != null) {
            children.add(new FlashLine(message));
        }
    }

    /** 反白单行 flash（pi {@code alt-screen-flash} 的 {@code \x1b[7m} 对位）。 */
    private static final class FlashLine extends StyledElement<FlashLine> {
        private final String message;

        FlashLine(String message) {
            this.message = message;
        }

        @Override
        public Size preferredSize(int availableWidth, int availableHeight,
                                  RenderContext context) {
            return Size.of(availableWidth, 1);
        }

        @Override
        protected void renderContent(Frame frame, Rect area, RenderContext context) {
            if (area.width() <= 0) {
                return;
            }
            String text = TextColumns.truncateToWidth(" " + message + " ", area.width());
            int width = TextColumns.width(text);
            String padded = text + " ".repeat(Math.max(0, area.width() - width));
            frame.buffer().setString(area.left(), area.top(), padded, Style.EMPTY.reversed());
        }
    }

    /** The current snapshot (for tree selectors). */
    SessionSnapshot snapshot() {
        return snapshot;
    }

    /** Committed transcript snapshot for the scrollback printer. */
    List<ChatMessage> transcriptMessages() {
        return chatPanel.messages();
    }

    /** The in-flight streaming draft for the scrollback printer. */
    ChatMessage transcriptDraft() {
        return chatPanel.draft();
    }

    // ── Viewport navigation (driven by PiTuiApp) ─────────────

    /** Scrolls the transcript by {@code delta} rows (negative = up). */
    void scrollByRows(int delta) {
        chatPanel.scrollByRows(delta);
    }

    /** Jumps to the top of the transcript. */
    void scrollToTop() {
        chatPanel.scrollToTop();
    }

    /** Jumps to the bottom of the transcript and resumes follow. */
    void scrollToBottom() {
        chatPanel.scrollToBottom();
    }

    /** The current first visible row (test hook). */
    int scrollOffset() {
        return chatPanel.scrollOffset();
    }

    /** The current viewport height in rows (page-scroll driver). */
    int visibleRows() {
        return chatPanel.visibleRows();
    }

    // ── 聊天区追加 API（系统/markdown 气泡）──

    /** Append a system/info bubble (slash command results). */
    void appendSystemText(String text) {
        chatPanel.append(new ChatMessage.System(text, MetaKind.GENERIC));
    }

    /**
     * 批 25（docs/36 T8）：markdown 源气泡（pi 的 {@code Markdown} 组件对位，
     * /hotkeys interactive-mode.ts:6921）——消费面＝/help /hotkeys 等
     * markdown 源命令结果。
     */
    void appendMarkdownText(String text) {
        chatPanel.append(new ChatMessage.Markdown(text));
    }

    /** The last committed message (test hook). */
    ChatMessage lastMessage() {
        return chatPanel.last();
    }

    /** All committed messages (test hook). */
    java.util.List<ChatMessage> messages() {
        return chatPanel.messages();
    }

    /** The committed message count (test hook). */
    int messageCount() {
        return chatPanel.size();
    }
}
