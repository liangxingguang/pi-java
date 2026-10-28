package com.pijava.tui.app;

import java.io.IOException;

import com.pijava.coding.agent.core.AnthropicExtraUsageWarning;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.util.TamboUIAdapter;

/**
 * 批 29（E1 结构债拆分，PiTuiApp 簇⑨⑩⑫纯移动——行为零变化）：扩展 UI 宿主
 * 接线（overlay 开合回调＋扩展编辑对话框的外部编辑器往返）、主题控制器
 * holder、Anthropic 订阅凭证警告、/bug 建议。会话经 {@code app.session} 现读。
 */
final class ExtensionHostWiring {

    private final PiTuiApp app;
    private final ChatScreen chatScreen;

    /** 扩展 UI 宿主（bindExtensionUi 回填；null ⇒ 无扩展 UI 装配）。 */
    private TuiExtensionUiHost extensionUiHost;

    /** 批 21（T9/T11）：主题控制器（设置页 Theme 子菜单＋/reload 主题臂消费）。 */
    private com.pijava.coding.agent.theme.ThemeController themeController;

    // 批 12 T13：Anthropic 订阅凭证警告（每会话一次——随 switchSession 重建）。
    private AnthropicExtraUsageWarning subscriptionWarning;
    /** 凭证解析闭包（生产＝{@code Credentials.resolveCredential}；测试注入）。 */
    private java.util.function.Supplier<
            java.util.Optional<com.pijava.ai.auth.RecordedCredential>> credentialProbe =
        () -> com.pijava.ai.auth.Credentials.resolveCredential("anthropic");
    // 批 15（T2d，pi bugReportHintShown）：/bug 建议每进程最多一次。
    private volatile boolean bugReportHintShown;

    /** pi 逐字：{@code /\b(?:abort(?:ed)?|cancel(?:l?ed)?)\b/i}。 */
    private static final java.util.regex.Pattern ABORT_CANCEL_WORD =
        java.util.regex.Pattern.compile("\\b(?:abort(?:ed)?|cancel(?:l?ed)?)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    ExtensionHostWiring(PiTuiApp app, ChatScreen chatScreen) {
        this.app = app;
        this.chatScreen = chatScreen;
        // 原 PiTuiApp 构造体的 subscriptionWarning = newSubscriptionWarning() 移此。
        subscriptionWarning = newSubscriptionWarning();
    }

    /** 扩展 UI 宿主读面（start/startInline 的渲染循环起步钩用；可空＝未装配）。 */
    TuiExtensionUiHost extensionUiHost() {
        return extensionUiHost;
    }

    /** 批 21（T9）：回填主题控制器（launcher 在会话装配后注入）。 */
    void setThemeController(com.pijava.coding.agent.theme.ThemeController controller) {
        this.themeController = controller;
    }

    /** 批 21（T11）：主题控制器读面（设置页 Theme 子菜单用；可空＝未装配）。 */
    com.pijava.coding.agent.theme.ThemeController themeController() {
        return themeController;
    }

    /**
     * 批 17（T7，docs/28 §3-T7.1）：回填扩展 UI 宿主——overlay 开合回调、
     * 外部编辑器往返、终端几何（host 先于 {@code AgentSession.create} 存在，
     * app 构造后回填＝晚绑定 holder）。
     */
    void bindExtensionUi(TuiExtensionUiHost host) {
        this.extensionUiHost = host;
        host.bindApp(app::openOverlay, app::dismissOverlay, this::externalEditorRoundtrip,
            app::terminalCols, app::terminalRows);
        chatScreen.setTerminalWidthSource(app::terminalCols);
    }

    /**
     * 批 17（T4）：扩展编辑对话框的 ctrl+g 外部编辑器往返（pi
     * {@code handleOpenExternalEditor} :129-139 对位——tui.stop/start ⇒
     * java 的 alt 屏退出/重进；inline 臂摘/挂 overlay 同旨）。
     */
    private java.util.Optional<String> externalEditorRoundtrip(String command, String content) {
        boolean altScreen = app.runner != null;
        boolean inlineOverlay = !altScreen && app.inlineShell != null && app.inlineShell.overlayActive();
        if (altScreen) {
            TamboUIAdapter.leaveAlternateScreen();
        } else if (inlineOverlay) {
            try {
                app.inlineShell.endOverlay();
            } catch (IOException ignored) {
                // best-effort；下同
            }
        }
        try {
            var result =
                com.pijava.coding.agent.core.ExternalEditorSupport.edit(command, content);
            if (result instanceof com.pijava.coding.agent.core.ExternalEditorSupport
                    .Result.Complete(var text)) {
                return java.util.Optional.of(text);
            }
            return java.util.Optional.empty();
        } catch (Exception e) {
            chatScreen.showError("External editor failed: " + e.getMessage());
            return java.util.Optional.empty();
        } finally {
            if (altScreen) {
                TamboUIAdapter.enterAlternateScreen();
            } else if (inlineOverlay) {
                try {
                    app.inlineShell.beginOverlay();
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
    }

    // ── 批 12 T13（docs/23 §3）：Anthropic 订阅凭证警告接线 ──────

    /** 警告 guard（现读 app.session 的 warnings——switchSession 后自然跟随）。 */
    private AnthropicExtraUsageWarning newSubscriptionWarning() {
        return new AnthropicExtraUsageWarning(
            () -> app.session.services().settings().uiPrefs().getWarnings());
    }

    /** 批 12 T13：警告 guard 随会话重建（pi anthropicSubscriptionWarningShown 每会话一次）。 */
    void renewSubscriptionWarning() {
        subscriptionWarning = newSubscriptionWarning();
    }

    /**
     * pi 四触发时机的统一入口（:1228 init／:4550 模型选择／:6120 切换／
     * :5352 登录）：命中则聊天区一条警告（每会话一次，guard 内部去重）。
     */
    void maybeWarnSubscriptionAuth() {
        if (subscriptionWarning == null) {
            return;
        }
        subscriptionWarning.maybeWarn(app.session.harness().getModel(), credentialProbe)
            .ifPresent(chatScreen::appendSystemText);
    }

    /** 凭证探针注入（T13 测试面——不碰真实 env/凭证库）。 */
    void setCredentialProbe(java.util.function.Supplier<
            java.util.Optional<com.pijava.ai.auth.RecordedCredential>> probe) {
        credentialProbe = probe == null
            ? () -> com.pijava.ai.auth.Credentials.resolveCredential("anthropic") : probe;
    }

    // ── 批 15（T2d，pi :2168-2190＋:3586 对位）：/bug 建议 ──────────

    /**
     * pi {@code maybeSuggestBugReport}：stopReason 非 error ⇒ 不提示；可重试错误
     * ⇒ 不提示；errorMessage 含 abort/cancel 词 ⇒ 不提示；每进程最多一次。
     * R29 安装变更警告门恒 false（批 15 裁决④）。裁决⑪：dim 系统文本（D83）。
     */
    void maybeSuggestBugReport(com.pijava.ai.message.Message.AssistantMessage message) {
        if (bugReportHintShown) {
            return;
        }
        if (message.stopReason() != com.pijava.ai.message.StopReason.ERROR) {
            return;
        }
        if (com.pijava.ai.utils.RetryableError.isRetryableAssistantError(message)) {
            return;
        }
        var errorMessage = message.errorMessage() == null ? "" : message.errorMessage();
        if (ABORT_CANCEL_WORD.matcher(errorMessage).find()) {
            return;
        }
        bugReportHintShown = true;
        chatScreen.appendSystemText(
            "If this looks like a pi-java bug, /bug sends a report to the developers.");
    }
}
