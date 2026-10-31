package com.pijava.tui.app;

import com.pijava.ai.model.ModelCapability;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.slash.builtin.ThinkingCommands;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.ModelSelectorScreen;
import com.pijava.tui.util.ScrollInputNormalizer;
import com.pijava.tui.util.TamboUIAdapter;
import com.pijava.tui.util.TuiEventDispatcher;

import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.tui.event.ResizeEvent;
import dev.tamboui.tui.event.TickEvent;

/**
 * 批 29（E1 结构债拆分，PiTuiApp 簇④⑦纯移动——行为零变化）：全局键/鼠标
 * 分派（tick/resize/paste/滚轮、扩展快捷键最优先、Enter 两态、键位表动作、
 * Tab 补全、空编辑器导航滚动）＋键位动作本体（handleAction）＋Ctrl+G 外部
 * 编辑器。分派序逐臂与原 PiTuiApp.onKeyEvent 一致；可变态（session／
 * extensionShortcuts／runner／lastTerminal*）经 {@code app} 包私有缝现读。
 */
final class GlobalKeyRouter {

    private final PiTuiApp app;
    private final ChatScreen chatScreen;
    private final KeybindingsManager keys;
    private final InteractiveMode mode;
    private final TuiEventDispatcher dispatcher;
    private final ScrollInputNormalizer normalizer;
    private final OverlayManager overlayManager;
    private final SlashFlowRouter slashFlow;
    // 批 12 T2（docs/23 §3）：双击 Esc 的 500ms 时间窗（pi lastEscapeTime 对位）。
    private long lastEscapeAt;
    private java.util.function.LongSupplier nowMillis = System::currentTimeMillis;

    GlobalKeyRouter(PiTuiApp app, ChatScreen chatScreen, KeybindingsManager keys,
                    InteractiveMode mode, TuiEventDispatcher dispatcher,
                    ScrollInputNormalizer normalizer, OverlayManager overlayManager,
                    SlashFlowRouter slashFlow) {
        this.app = app;
        this.chatScreen = chatScreen;
        this.keys = keys;
        this.mode = mode;
        this.dispatcher = dispatcher;
        this.normalizer = normalizer;
        this.overlayManager = overlayManager;
        this.slashFlow = slashFlow;
    }

    EventResult onEvent(Event event) {
        if (event instanceof TickEvent) {
            // Redraw on every tick so streamed text (dispatched from the
            // virtual-thread observer) reaches the draft inside the viewport.
            return EventResult.HANDLED;
        }
        if (event instanceof ResizeEvent resizeEvent) {
            app.lastTerminalCols = resizeEvent.width();
            app.lastTerminalRows = resizeEvent.height();
            return EventResult.HANDLED;
        }
        if (event instanceof KeyEvent keyEvent) {
            return onKeyEvent(keyEvent);
        }
        if (event instanceof PasteEvent pasteEvent) {
            chatScreen.insertText(pasteEvent.text());
            return EventResult.HANDLED;
        }
        if (event instanceof MouseEvent mouseEvent) {
            return onMouseEvent(mouseEvent);
        }
        return EventResult.UNHANDLED;
    }

    /**
     * Global mouse handler: scroll events feed the normalizer first; once the
     * global handler consumes them, routed elements never see them.
     */
    private EventResult onMouseEvent(MouseEvent event) {
        if (event.kind() == MouseEventKind.SCROLL_UP
                || event.kind() == MouseEventKind.SCROLL_DOWN) {
            int direction = event.kind() == MouseEventKind.SCROLL_UP ? -1 : 1;
            int lines = normalizer.onEvent(direction, System.currentTimeMillis());
            if (lines != 0) {
                chatScreen.scrollByRows(lines);
            }
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    private EventResult onKeyEvent(KeyEvent event) {
        var activeOverlay = overlayManager.currentOverlay();
        if (activeOverlay != null) {
            activeOverlay.onKeyEvent(event);
            overlayManager.closeOverlayIfDone();
            return EventResult.HANDLED;
        }
        // 批 26（docs/37 T9）：扩展快捷键最先查（pi custom-editor.ts:88-92
        // 「Check extension-registered shortcuts first」对位——先于内置键与 Enter）。
        if (!app.extensionShortcuts.isEmpty()) {
            var stroke = TamboUIAdapter.toStroke(event);
            for (var shortcut : app.extensionShortcuts) {
                if (shortcut.stroke().equals(stroke)) {
                    Thread.startVirtualThread(() -> {
                        try {
                            shortcut.handler().run(app.session.extensionShortcutContext());
                        } catch (Exception e) {
                            // pi interactive-mode.ts:2289-2291 逐字前缀
                            dispatcher.dispatch(() -> chatScreen.appendSystemText(
                                "Shortcut handler error: " + e.getMessage()));
                        }
                    });
                    return EventResult.HANDLED;
                }
            }
        }
        if (TamboUIAdapter.isSendEnter(event)) {
            chatScreen.applyCompletion(); // Enter picks the highlighted command
            slashFlow.submit();
            return EventResult.HANDLED;
        }
        if (TamboUIAdapter.isNewlineEnter(event)) {
            chatScreen.insertNewline();
            return EventResult.HANDLED;
        }
        var keyId = keys.resolve(TamboUIAdapter.toStroke(event));
        if (keyId != null) {
            if (KeybindingsManager.INTERRUPT.equals(keyId)
                    && chatScreen.completerActive()) {
                // Esc closes the completion popup before it can interrupt.
                chatScreen.onKeyEvent(event);
                return EventResult.HANDLED;
            }
            handleAction(keyId);
            return EventResult.HANDLED;
        }
        // Tab is surfaced as CHAR('\t') (see EventParser); use it for slash
        // completion when the panel is open, otherwise ignore it so the editor
        // never receives a stray tab character.
        if (event.isChar('\t') && !event.hasShift()) {
            if (chatScreen.completerActive()) {
                chatScreen.onKeyEvent(event);
            }
            return EventResult.HANDLED;
        }
        // 批 34（K20；pi tui-alt-screen.ts:750-792 对位）：全屏 PgUp/PgDn 恒滚视口、
        // Ctrl+Home/End 顶/底；内联保留空编辑器的 PgUp/PgDn（D195）。裸 ↑↓/Home/End
        // 归还编辑器（K27 历史／行首行尾——B238-①）。
        if (app.runner != null && isAltScreenScroll(event)) {
            scrollViewport(event);
            return EventResult.HANDLED;
        }
        if (chatScreen.isInputEmpty() && isInlinePageScroll(event)) {
            scrollViewport(event);
            return EventResult.HANDLED;
        }
        chatScreen.onKeyEvent(event);
        return EventResult.HANDLED;
    }

    /** pi alt-screen 滚动键：PgUp/PgDn 无修饰；Home/End 仅 Ctrl（恒消费）。 */
    private static boolean isAltScreenScroll(KeyEvent event) {
        boolean bare = !event.hasCtrl() && !event.hasAlt() && !event.hasShift();
        if (bare && (event.code() == KeyCode.PAGE_UP || event.code() == KeyCode.PAGE_DOWN)) {
            return true;
        }
        return event.hasCtrl() && !event.hasAlt() && !event.hasShift()
            && (event.code() == KeyCode.HOME || event.code() == KeyCode.END);
    }

    /** 内联（regular）独有：空编辑器裸 PgUp/PgDn 滚转录（D195；裸 Home/End 归编辑器）。 */
    private static boolean isInlinePageScroll(KeyEvent event) {
        return !event.hasCtrl() && !event.hasAlt() && !event.hasShift()
            && (event.code() == KeyCode.PAGE_UP || event.code() == KeyCode.PAGE_DOWN);
    }

    private void scrollViewport(KeyEvent event) {
        // pi PAGE_SCROLL_OVERLAP = 4（tui-alt-screen.ts:76）：页步长留重叠。
        int page = Math.max(1, chatScreen.visibleRows() - 4);
        switch (event.code()) {
            case KeyCode.PAGE_UP -> chatScreen.scrollByRows(-page);
            case KeyCode.PAGE_DOWN -> chatScreen.scrollByRows(page);
            case KeyCode.HOME -> chatScreen.scrollToTop();      // 仅 ctrl+home 到此
            case KeyCode.END -> chatScreen.scrollToBottom();    // 仅 ctrl+end 到此
            default -> { /* not a scroll key */ }
        }
    }

    private void handleAction(String keyId) {
        switch (keyId) {
            case KeybindingsManager.INTERRUPT -> {
                // docs/21（T5，D41 重裁）：pi onEscape 首臂（:3071-3073）——流式中
                // Esc 先把排队消息回填编辑器再 abort（pi 的 {abort:true} 在 restore 内
                // 触发；java 拆开等价：restore 同步清队列，abort 紧随其后无交错窗口）。
                if (app.session.isRunning()) {
                    var restored = mode.restoreQueuedMessages();
                    if (restored != null) {
                        chatScreen.restoreToEditor(restored.combinedText());
                    }
                    mode.abort();
                    // 批 12 T2：流式中断消费本次 Esc，不计双击（pi :3071-3073 短路）。
                    lastEscapeAt = 0L;
                } else if (chatScreen.isInputEmpty()) {
                    // 批 12 T2（docs/23 §3；pi :3067-3097 对位）：空编辑器双击 Esc
                    // （500ms 窗）⇒ doubleEscapeAction（默认 "tree"）。
                    var action =
                        app.session.services().settings().accessors().getDoubleEscapeAction();
                    if (!"none".equals(action)) {
                        long now = nowMillis.getAsLong();
                        if (now - lastEscapeAt < 500) {
                            if ("tree".equals(action)) {
                                slashFlow.openTreeSelector();
                            } else {
                                slashFlow.openForkSelector();
                            }
                            lastEscapeAt = 0L;
                        } else {
                            lastEscapeAt = now;
                        }
                    }
                } else {
                    mode.abort();   // 既有：非空编辑器/空闲 abort（幂等）
                }
            }
            case KeybindingsManager.FOLLOW_UP -> {
                if (chatScreen.isInputEmpty()) {
                    break;
                }
                var text = chatScreen.inputText();
                // A5：pi Alt+Enter —— 运行中才排 followUp；空闲等同普通 Enter
                // （interactive-mode.ts:4418-4429）。
                // B175（原 docs/26）：压缩中 ⇒ 缓冲为 followUp。
                if (app.session.isCompacting() || app.session.isRunning()) {
                    mode.followUp(text);
                } else {
                    slashFlow.submitPrompt(text);
                }
                chatScreen.clearInput();
            }
            case KeybindingsManager.CLEAR -> chatScreen.clearInput();
            case KeybindingsManager.EXIT -> {
                if (chatScreen.isInputEmpty()) {
                    app.exit();
                }
            }
            case KeybindingsManager.MODEL_SELECT ->
                app.openOverlay(new ModelSelectorScreen(app.session));
            // B137：Ctrl+P 在 --models/settings scope 内正向轮转（空 scope 走全目录）。
            // TUI 无模型常驻标签，切换静默——与 Ctrl+L 选择器现状一致。
            case KeybindingsManager.MODEL_CYCLE -> {
                app.session.cycleModelForward();
                app.maybeWarnSubscriptionAuth();   // 批 12 T13（pi :6120/:6126 切换时机对位）
            }
            // 批 12 T3（docs/23 §3；pi :4596-4611 对位）：Ctrl+G 外部编辑器往返。
            case KeybindingsManager.EXTERNAL_EDITOR -> openExternalEditor();
            case KeybindingsManager.DEQUEUE -> {
                // docs/21（T4，S20）：pi handleDequeue（:4507-4513）——全部排队
                // （引擎两队列＋压缩缓冲）合并取回编辑器，文案逐字【模型可见】。
                var restored = mode.restoreQueuedMessages();
                if (restored == null) {
                    chatScreen.appendSystemText("No queued messages to restore");
                } else {
                    chatScreen.restoreToEditor(restored.combinedText());
                    chatScreen.appendSystemText("Restored " + restored.count()
                        + " queued message" + (restored.count() > 1 ? "s" : "") + " to editor");
                }
            }
            // 批 34（K7；pi interactive-mode.ts:4527-4536 对位）：Shift+Tab 循环思考等级。
            case KeybindingsManager.THINKING_CYCLE -> cycleThinkingLevel();
            // 批 34（K9；pi :4589-4594 对位）：Ctrl+T 显隐 thinking 块。
            case KeybindingsManager.THINKING_TOGGLE -> toggleThinkingVisibility();
            // 批 34（K8；pi :4557/:4561 对位）：Ctrl+O 展开/折叠工具输出。
            case KeybindingsManager.TOOLS_EXPAND -> app.toggleToolsExpanded();
            default -> { /* 其余动作未接线 */ }
        }
    }

    /**
     * 批 34（K7；pi {@code cycleThinkingLevel} :4527-4536 对位）：按可用集轮转；
     * 模型不支持 thinking ⇒ 状态行提示不轮转（pi 返回 undefined 臂）。
     */
    private void cycleThinkingLevel() {
        var model = app.session.harness().getModel();
        boolean supports = model != null && app.session.modelCatalog().find(model)
            .map(info -> info.capabilities().contains(ModelCapability.THINKING))
            .orElse(true);                  // 目录外模型：能力未知 ⇒ 支持（D200）
        if (!supports) {
            chatScreen.appendSystemText("Current model does not support thinking");
            return;
        }
        var levels = ThinkingCommands.availableLevels(app.session);
        var current = app.session.harness().getThinkingLevel();
        int index = levels.indexOf(current);        // 不在集合 ⇒ -1 ⇒ 取首项（pi 同）
        var next = levels.get((index + 1) % levels.size());
        app.session.setThinkingLevel(next);
        chatScreen.appendSystemText("Thinking level: " + next.label());
    }

    /** 批 34（K9；pi :4589-4594 对位）：反转持久设置项，渲染面现读下一帧生效。 */
    private void toggleThinkingVisibility() {
        var accessors = app.session.services().settings().accessors();
        boolean hidden = !Boolean.TRUE.equals(accessors.getHideThinkingBlock());
        accessors.setHideThinkingBlock(hidden);
        chatScreen.appendSystemText("Thinking blocks: " + (hidden ? "hidden" : "visible"));
    }

    /**
     * 批 12 T3（docs/23 §3；pi {@code handleOpenExternalEditor} :4596-4611 对位）：
     * 编辑器内容写临时文件 ⇒ 外部编辑器（inheritIO）⇒ 退出码 0 读回。
     * alt 屏模式先退后进（pi ui.stop()/ui.start()）；渲染线程阻塞在等待上，
     * 子进程输出与 TUI 帧由 alt 屏序列隔离。
     */
    private void openExternalEditor() {
        var command = app.session.services().settings().accessors().getExternalEditorCommand();
        var content = chatScreen.inputText();
        boolean altScreen = app.runner != null;   // regular 内联模式无 alt 屏 ⇒ 空操作
        if (altScreen) {
            TamboUIAdapter.leaveAlternateScreen();
        }
        try {
            var result =
                com.pijava.coding.agent.core.ExternalEditorSupport.edit(command, content);
            if (result instanceof com.pijava.coding.agent.core.ExternalEditorSupport
                    .Result.Complete(var text)) {
                chatScreen.setEditorText(text);
            }
        } catch (Exception e) {
            chatScreen.showError("External editor failed: " + e.getMessage());
        } finally {
            if (altScreen) {
                TamboUIAdapter.enterAlternateScreen();
            }
        }
    }

    /** 双击时间窗的时钟注入（测试面；生产 System::currentTimeMillis）。 */
    void setNowMillis(java.util.function.LongSupplier supplier) {
        nowMillis = supplier == null ? System::currentTimeMillis : supplier;
    }
}
