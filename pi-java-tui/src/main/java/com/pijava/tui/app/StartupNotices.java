package com.pijava.tui.app;

import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.Version;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.WelcomeOverlay;
import com.pijava.tui.util.TuiEventDispatcher;

/**
 * 批 29（E1 结构债拆分，PiTuiApp 簇②纯移动——行为零变化）：启动通知面——
 * welcome 卡（一次性）＋包更新后台检查＋扩展绑定诊断＋启动崩溃提示＋启动
 * changelog＋初始 CLI 消息提交。会话经 {@code app.session} 现读（原直读
 * this.session 同义，switchSession 后自然跟随）。
 */
final class StartupNotices {

    private final PiTuiApp app;
    private final ChatScreen chatScreen;
    private final TuiEventDispatcher dispatcher;
    private final SlashFlowRouter slashFlow;
    private boolean welcomeShown;
    // 批 12 T7：启动 changelog（runInteractive 装配线程写入，渲染线程
    // showWelcomeOnce 读取 ⇒ volatile 跨线程可见性，同 lastTerminalRows 先例）。
    private volatile String startupChangelog;
    private volatile boolean startupChangelogCollapse;
    // 批 15（T2a，pi :1220-1226）：启动崩溃提示（同 startupChangelog 装配形）。
    private volatile String startupCrashNotice;

    StartupNotices(PiTuiApp app, ChatScreen chatScreen, TuiEventDispatcher dispatcher,
                   SlashFlowRouter slashFlow) {
        this.app = app;
        this.chatScreen = chatScreen;
        this.dispatcher = dispatcher;
        this.slashFlow = slashFlow;
    }

    /** Emit the startup banner exactly once (fullscreen or inline launch). */
    void showWelcomeOnce() {
        if (welcomeShown) {
            return;
        }
        welcomeShown = true;
        chatScreen.showWelcome(new WelcomeOverlay(app.session).text());
        showStartupChangelog();
        showStartupCrashNotice();
        showStartupDiagnostics();
        schedulePackageUpdateCheck();
    }

    /**
     * 批 19（T7，pi :1166-1178 启动 fire-and-forget 对位）：后台查包更新，非空 ⇒
     * 聊天区插通知块（文案 pi :4667-4684 逐字；dim 系统文本，窄差 D83 同族）。
     */
    private void schedulePackageUpdateCheck() {
        var settings = app.session.services().settings();
        var cwd = java.nio.file.Path.of(System.getProperty("user.dir"));
        var agentDir = com.pijava.coding.agent.core.FileSettingsStorage.defaultAgentDir();
        com.pijava.coding.agent.core.PackageUpdateNotifier.checkAsync(
            () -> com.pijava.coding.agent.core.PackageManagers.create(cwd, agentDir, settings)
                .checkForAvailableUpdates(),
            updates -> dispatcher.dispatch(() -> chatScreen.appendSystemText(
                com.pijava.coding.agent.core.PackageUpdateNotifier.notificationText(updates))));
    }

    /** 批 16（T1d 裁决⑧）：扩展 flag 绑定诊断随启动提示展示（dim 系统文本）。 */
    private void showStartupDiagnostics() {
        for (var diagnostic : app.session.startupDiagnostics()) {
            chatScreen.appendSystemText(diagnostic.type() + ": " + diagnostic.message());
        }
    }

    /**
     * 批 15（T2a，pi :1220-1226 对位）：启动崩溃提示装配（changelog 之后）。
     * 裁决⑪：走 dim 系统文本（pi 是 warning 色 ThemedText，窄差 D83）。
     */
    void setStartupCrashNotice(String text) {
        this.startupCrashNotice = text;
    }

    private void showStartupCrashNotice() {
        if (startupCrashNotice == null) {
            return;
        }
        var text = startupCrashNotice;
        startupCrashNotice = null;
        chatScreen.appendSystemText(text);
    }

    /**
     * 批 12 T7（docs/23 §3）：启动 changelog 装配（runInteractive 在 runner
     * 启动前调用；welcome 之后、dispatcher 首帧前展示一次）。
     */
    void setStartupChangelog(java.util.Optional<String> markdown, boolean collapse) {
        this.startupChangelog = markdown.orElse(null);
        this.startupChangelogCollapse = collapse;
    }

    /** pi {@code showStartupNoticesIfNeeded}（:843-872）两臂对位。 */
    private void showStartupChangelog() {
        if (startupChangelog == null) {
            return;
        }
        var markdown = startupChangelog;
        startupChangelog = null;
        if (startupChangelogCollapse) {
            // pi 折叠臂一行（文案逐字【模型可见】）。
            var v = com.pijava.coding.agent.core.ChangelogSupport
                .latestVersionOf(markdown).orElse(Version.VERSION);
            chatScreen.appendSystemText(
                "Updated to v" + v + ". Use /changelog to view full changelog.");
        } else {
            // pi 非折叠臂："What's New" 标题＋正文（D63：纯文本渲染，Markdown
            // 组件随 R1 批）。
            chatScreen.appendSystemText("What's New\n\n" + markdown.trim());
        }
    }

    /** Submit initial CLI messages when the TUI starts. */
    void submitInitial(Args args) {
        if (args.messages() != null && !args.messages().isEmpty()) {
            slashFlow.submitPrompt(String.join(" ", args.messages()));
        }
    }
}
