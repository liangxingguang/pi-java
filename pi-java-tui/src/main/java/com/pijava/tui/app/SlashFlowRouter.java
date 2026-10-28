package com.pijava.tui.app;

import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.PromptTemplateItems;
import com.pijava.coding.agent.core.slash.SkillCommandItems;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.component.SlashCompleter;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.ModelSelectorScreen;
import com.pijava.tui.screen.SessionListScreen;
import com.pijava.tui.screen.SettingsScreen;
import com.pijava.tui.screen.UserMessageSelectorScreen;
import com.pijava.tui.util.TuiEventDispatcher;

/**
 * 批 29（E1 结构债拆分——PiTuiApp 簇⑤，为守 FileLength≤500 闸门自 PiTuiApp
 * 追加移出，行为零变化）：prompt 提交（Enter 两态殊途同归的 submit 面）＋
 * slash 命令结果的 UI 路由（{@code UI_*} marker 分派＋fatal 退出臂＋markdown
 * 结果臂）＋/tree /fork 选择器入口与 fork 回填流＋补全目录装配。会话经
 * {@code app.session} 现读。
 */
final class SlashFlowRouter {

    private final PiTuiApp app;
    private final ChatScreen chatScreen;
    private final InteractiveMode mode;
    private final TuiEventDispatcher dispatcher;
    private final KeybindingsManager keys;

    SlashFlowRouter(PiTuiApp app, ChatScreen chatScreen, InteractiveMode mode,
                    TuiEventDispatcher dispatcher, KeybindingsManager keys) {
        this.app = app;
        this.chatScreen = chatScreen;
        this.mode = mode;
        this.dispatcher = dispatcher;
        this.keys = keys;
    }

    /**
     * Submit a prompt through the interactive mode and queue the inter-turn
     * separator (Codex-CLI style) once the run status completes, so the
     * divider lands after the committed transcript entries.
     */
    void submitPrompt(String text) {
        chatScreen.resetRunTracking();
        long startNanos = System.nanoTime();
        var result = mode.submit(text);
        // A5（原 docs/25）：运行中提交仅排队，没有 run 收尾 ⇒ 不画 run 分隔线。
        if ("queued".equals(result.disposition())) {
            return;
        }
        result.statusFuture().thenAccept(status ->
            dispatcher.dispatch(() ->
                chatScreen.finishRun(System.nanoTime() - startNanos)));
    }

    void submit() {
        var text = chatScreen.inputText();
        if (text.isBlank()) {
            return;
        }
        var slashContext = new SlashContext(
            app.session, keys, app::exit, app::switchSession);
        var command = mode.dispatch(text, slashContext);
        if (command != null) {
            chatScreen.clearInput();
            boolean loginCommand = text.startsWith("/login");
            // 批 15（T9，pi :5207 直设臂的 auth 警告时机对位——guard 内部去重，
            // 未直设（开选择器）也调用无害）。
            boolean modelCommand = text.startsWith("/model ") || text.equals("/model");
            command.thenAccept(result ->
                dispatcher.dispatch(() -> {
                    handleCommandResult(result, text);
                    // 批 12 T13（pi :5207/:5352 登录后时机对位）。
                    if (loginCommand || modelCommand) {
                        app.maybeWarnSubscriptionAuth();
                    }
                }));
        } else {
            chatScreen.appendUserText(text);
            chatScreen.submitInput();
        }
    }

    /** Build the completion catalog from the slash-command registry. */
    List<SlashCompleter.CommandItem> loadSlashItems() {
        var registry = app.session.services().slashCommands();
        var items = new ArrayList<>(registry.names().stream()
            .map(name -> {
                var command = registry.get(name);
                return new SlashCompleter.CommandItem(
                    name, command.argumentHint(), command.description());
            })
            .toList());
        // B247（pi interactive-mode.ts:787-792）：模板臂——无条件（pi 无门，
        // 对照 skill 臂的开关），序在内置之后、skill 之前（pi :820-823
        // 内置→模板→扩展→skill；java 无扩展臂，B221）。
        for (var entry :
                PromptTemplateItems.list(app.session.services().promptTemplates().all())) {
            items.add(new SlashCompleter.CommandItem(
                entry.name(), entry.argumentHint(), entry.description()));
        }
        // B244（pi interactive-mode.ts:803-815）：开关开 ⇒ 每个技能一个
        // skill:<name> 补全条目，排在模板之后。
        var enabled = app.session.services().settings().accessors().getEnableSkillCommands();
        for (var entry :
                SkillCommandItems.list(app.session.harness().skillManager().all(), enabled)) {
            items.add(new SlashCompleter.CommandItem(entry.name(), null, entry.description()));
        }
        return List.copyOf(items);
    }

    void handleCommandResult(String result) {
        handleCommandResult(result, null);
    }

    /**
     * @param input 原始输入行（裁决⑦：marker 不携载荷，{@code /bug} 的 hint
     *              由此切——pi :3242-3245 对位）
     */
    void handleCommandResult(String result, String input) {
        if (result == null) {
            return;
        }
        // 批 15（T2c，pi handleFatalRuntimeError :2113-2126 对位）：致命错误——
        // showError ⇒ 崩溃指引（crash 已记 ⇒ 恒打）⇒ 停 TUI ⇒ 退出码 1。
        if (result.startsWith(CommandRegistry.UI_FATAL_EXIT_PREFIX)) {
            chatScreen.showError(
                result.substring(CommandRegistry.UI_FATAL_EXIT_PREFIX.length()));
            chatScreen.appendSystemText(
                com.pijava.coding.agent.core.crash.CrashIntegration
                    .crashReportInstructions(app.session.sessionFile()));
            app.fatalExit = true;
            app.exit();
            return;
        }
        switch (result) {
            case CommandRegistry.UI_MODEL_SELECTOR ->
                app.openOverlay(new ModelSelectorScreen(app.session));
            case CommandRegistry.UI_SESSION_SELECTOR ->
                app.openOverlay(new SessionListScreen(app.session.listSessions()));
            case CommandRegistry.UI_TREE_SELECTOR -> openTreeSelector();
            case CommandRegistry.UI_FORK_SELECTOR -> openForkSelector();
            case CommandRegistry.UI_SETTINGS ->
                // 批 21（T11）：设置屏带主题控制器（Theme 子菜单预览/应用）。
                app.openOverlay(new SettingsScreen(app.session, app.themeController()));
            case CommandRegistry.UI_SCOPED_MODELS ->
                chatScreen.appendSystemText(
                    "Use /scoped-models +<model> or /scoped-models -<model>");
            // 批 15（T8）：/thinking（空参）的选择器（裁决⑥简版）。
            case CommandRegistry.UI_THINKING_SELECTOR ->
                app.openOverlay(new com.pijava.tui.screen.ThinkingLevelScreen(
                    app.session, chatScreen::appendSystemText));
            // 批 15（T7）：/bug 故障上报流——hint 从原始输入行切（pi :3242-3245）。
            case CommandRegistry.UI_BUG_REPORT -> {
                var hint = input != null && input.length() > 4
                    ? input.substring(4).trim() : "";
                new BugReportFlow(app.session, chatScreen, dispatcher, app::openOverlay)
                    .begin(hint.isEmpty() ? null : hint);
            }
            default -> {
                // 批 25（docs/36 T8）：/help /hotkeys 的结果文本是 markdown 源
                //（MiscCommands/CommandRegistry 产），走 Markdown 渲染（pi 的
                // Markdown 组件对位）；判定走原始输入行，命令协议不携 marker。
                var command = input == null ? "" : input.strip().split(" ")[0];
                if ("/help".equals(command) || "/hotkeys".equals(command)) {
                    chatScreen.appendMarkdownText(result);
                } else {
                    chatScreen.appendSystemText(result);
                }
            }
        }
    }

    /**
     * /tree 入口（批 12 T2 抽出，双击 Esc 与 slash 两路共用）：
     * docs/18（§3.8/§3.9）＋ docs/19（§3.9）——原地导航流（entry 树选择器 →
     * 选 entry 后三选摘要）。
     */
    void openTreeSelector() {
        new TreeNavigationFlow(app.session, mode, chatScreen, dispatcher,
            app::openOverlay, app::terminalRows, app::terminalCols, keys)
            .begin();
    }

    /**
     * /fork 入口（批 12 T2 抽出；docs/21 T3，B254）：pi
     * {@code showUserMessageSelector}（interactive-mode.ts:5500-5538 对位）——
     * user-message 选择器。
     */
    void openForkSelector() {
        var forkMessages = app.session.getUserMessagesForForking();
        if (forkMessages.isEmpty()) {                       // pi :5503-5506
            chatScreen.appendSystemText("No messages to fork from");
            return;
        }
        var initialSelectedId = forkMessages.get(forkMessages.size() - 1).entryId();
        app.openOverlay(new UserMessageSelectorScreen(forkMessages, initialSelectedId,
            app::terminalCols,
            entryId -> forkFromMessage(entryId, forkMessages),
            () -> { }));                                // Esc：pi 的 onCancel 只关选择器
    }

    /**
     * pi {@code /fork} 选择器的 onSelect 回调（interactive-mode.ts:5514-5528 逐字序，
     * docs/21 §3.3）：fork ⇒ 切换会话 ⇒ 选中消息文本回填编辑器 ⇒ 状态文案。
     */
    private void forkFromMessage(String entryId, List<AgentSession.ForkMessage> messages) {
        try {
            var forked = app.session.forkFromEntry(entryId);    // position "before"（批 5/6）
            if (forked.isEmpty()) {
                // session_before_fork cancel ⇒ pi {cancelled}：仅 requestRender（:5517-5520）。
                return;
            }
            app.switchSession(forked.get());
            // pi :5522：editor.setText(selectedText ?? "")——selectedText＝选中消息 text
            // （与 fork 消息表同一条目，裁决②）。
            var selectedText = messages.stream()
                .filter(m -> m.entryId().equals(entryId))
                .map(AgentSession.ForkMessage::text).findFirst().orElse("");
            chatScreen.setEditorText(selectedText);
            chatScreen.appendSystemText("Forked to new session");
        } catch (Exception e) {                             // pi :5526-5527
            chatScreen.showError(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        }
    }
}
