package com.pijava.tui.screen;

import com.pijava.agent.harness.SessionSnapshot;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.ChatPanel;
import com.pijava.tui.component.MetaKind;
import com.pijava.tui.component.PendingQueueView;
import com.pijava.tui.component.StatusBar;
import com.pijava.tui.util.TamboUIAdapter;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Column;

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
    private SessionSnapshot snapshot;

    ChatScreenLayout(ChatPanel chatPanel, PendingQueueView pendingQueue,
                     ExtensionInjectionLayer extensionLayer, ChatInputPortal inputPortal,
                     SessionEventRouter sessionEventRouter) {
        this.chatPanel = chatPanel;
        this.pendingQueue = pendingQueue;
        this.extensionLayer = extensionLayer;
        this.inputPortal = inputPortal;
        this.sessionEventRouter = sessionEventRouter;
    }

    /**
     * Render the main chat area: transcript viewport, one blank row, then the
     * input row. There is no standalone draft bubble or separator — the draft
     * lives inside the viewport (Codex-CLI style).
     */
    Column render() {
        var children = new ArrayList<Element>();
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
        return TamboUIAdapter.column(children);
    }

    /**
     * Bottom region for regular (raw-scrollback) mode: editor + status bar.
     * The transcript lives in the terminal scrollback, so only this fixed
     * region is redrawn in place.
     */
    Element renderBottomArea() {
        var children = new ArrayList<Element>();
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
            children.add(TamboUIAdapter.text(" " + active.textAt(System.nanoTime())).length(1));
        } else if (extensionLayer.hasCustomFooter()) {
            children.addAll(extensionLayer.customFooterRows());
        } else {
            children.add(snapshot == null
                ? TamboUIAdapter.row().length(1)
                : new StatusBar().render(snapshot));
        }
        return children.size() == 1 ? children.get(0) : TamboUIAdapter.column(children);
    }

    /** Refresh the status bar from a session snapshot. */
    void updateSnapshot(SessionSnapshot snapshot) {
        this.snapshot = snapshot;
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
