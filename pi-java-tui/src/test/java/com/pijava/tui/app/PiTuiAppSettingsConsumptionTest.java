package com.pijava.tui.app;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import com.pijava.ai.api.AuthKind;
import com.pijava.ai.auth.RecordedCredential;
import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.AnthropicExtraUsageWarning;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.support.ScriptedChatProvider;
import com.pijava.tui.util.InlineTuiShell;
import com.pijava.tui.util.TuiEventDispatcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 12（docs/23 §3/§7）PiTuiApp 接线对拍：
 * <ul>
 *   <li>T13：订阅凭证警告四时机之一的 init 时机——anthropic 模型＋stored
 *       OAuth ⇒ 聊天区一条逐字警告；warnings=false ⇒ 静默（凭证探针注入，
 *       不碰真实 env/凭证库）。</li>
 *   <li>T7：启动 changelog 两臂——非折叠（"What's New"＋正文）／折叠
 *       （一行逐字文案），welcome 之后展示一次。</li>
 * </ul>
 */
class PiTuiAppSettingsConsumptionTest {

    @TempDir
    Path tmp;

    private AgentSession session;
    private ChatScreen chatScreen;
    private PiTuiApp app;
    private ClassLoader previousTccl;
    private String savedHome;

    @AfterEach
    void tearDown() {
        if (previousTccl != null) {
            Thread.currentThread().setContextClassLoader(previousTccl);
            previousTccl = null;
        }
        if (session != null) {
            session.close();    // close 的 flush 必须落在临时 home（settings 懒解析）
            session = null;
        }
        if (savedHome != null) {
            System.setProperty("user.home", savedHome);
            savedHome = null;
        }
    }

    private void newRig() throws Exception {
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.ai.provider.ProviderFactory"),
            ScriptedChatProvider.Factory.class.getName() + "\n");
        Path testClasses = Path.of(getClass().getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(
            new java.net.URL[] {tmp.toUri().toURL(), testClasses.toUri().toURL()},
            previousTccl));
        var home = Files.createTempDirectory("pi-consume-home");
        var sessionDir = Files.createTempDirectory("pi-consume-session");
        var args = ArgsParser.parse(new String[] {"--provider", "scripted",
            "--model", "hello", "--session-dir", sessionDir.toString()});
        // ⚠️ user.home 保持到 tearDown（settings 懒解析——create 后恢复会让
        // 首次访问与 close flush 落进真实 home）。
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        session = AgentSession.create(args);
        var mode = new InteractiveMode(session);
        chatScreen = new ChatScreen();
        app = new PiTuiApp(mode, chatScreen,
            new KeybindingsManager(), new TuiEventDispatcher());
    }

    private void startInline() throws Exception {
        app.startInline(InlineTuiShell.createForTest(new FakeBackend()));
    }

    private java.util.List<String> systemTexts() {
        return chatScreen.messages().stream()
            .filter(m -> m instanceof ChatMessage.System
                || m instanceof ChatMessage.Padded)
            .map(m -> switch (m) {
                case ChatMessage.System s -> s.text();
                case ChatMessage.Padded(var inner, var pad) ->
                    inner instanceof ChatMessage.System s ? s.text() : "";
                default -> "";
            })
            .toList();
    }

    // ── T13：订阅凭证警告接线 ────────────────────────────────────

    @Test
    void subscriptionAuthWarnsOnceOnStartup() throws Exception {
        newRig();
        session.setModel(ModelId.of("anthropic", "claude-fable-5"));
        app.setCredentialProbe(() -> Optional.of(new RecordedCredential(
            AuthKind.API_KEY, "x", "stored-oauth:anthropic", Map.of())));

        startInline();

        var hits = systemTexts().stream()
            .filter(t -> t.equals(AnthropicExtraUsageWarning.TEXT)).toList();
        assertThat(hits).hasSize(1);
    }

    @Test
    void disabledWarningStaysSilentOnStartup() throws Exception {
        newRig();
        session.setModel(ModelId.of("anthropic", "claude-fable-5"));
        session.services().settings().uiPrefs()
            .setWarnings(new Settings.Warnings(false));
        app.setCredentialProbe(() -> Optional.of(new RecordedCredential(
            AuthKind.API_KEY, "sk-ant-oat-x", "stored:anthropic", Map.of())));

        startInline();

        assertThat(systemTexts())
            .noneMatch(t -> t.contains("Anthropic subscription auth"));
    }

    @Test
    void nonAnthropicModelStaysSilentOnStartup() throws Exception {
        newRig();   // scripted/hello ⇒ provider "scripted"
        app.setCredentialProbe(() -> Optional.of(new RecordedCredential(
            AuthKind.API_KEY, "sk-ant-oat-x", "stored-oauth:anthropic", Map.of())));

        startInline();

        assertThat(systemTexts())
            .noneMatch(t -> t.contains("Anthropic subscription auth"));
    }

    // ── T7：启动 changelog 两臂 ─────────────────────────────────

    @Test
    void startupChangelogShowsWhatsNewAfterWelcome() throws Exception {
        newRig();
        app.setStartupChangelog(
            Optional.of("- shiny new thing\n- second thing"), false);

        startInline();

        assertThat(systemTexts())
            .anyMatch(t -> t.equals("What's New\n\n- shiny new thing\n- second thing"));
    }

    @Test
    void collapsedChangelogShowsOneLineNotice() throws Exception {
        newRig();
        app.setStartupChangelog(
            Optional.of("release 0.2.0 notes\n- shiny"), true);

        startInline();

        // pi :843-872 折叠臂逐字【模型可见】。
        assertThat(systemTexts()).anyMatch(t -> t.equals(
            "Updated to v0.2.0. Use /changelog to view full changelog."));
        assertThat(systemTexts()).noneMatch(t -> t.contains("shiny"));
    }

    @Test
    void noChangelogShowsNothingExtra() throws Exception {
        newRig();
        app.setStartupChangelog(Optional.empty(), false);

        startInline();

        assertThat(systemTexts()).noneMatch(t -> t.contains("What's New"));
    }
}
