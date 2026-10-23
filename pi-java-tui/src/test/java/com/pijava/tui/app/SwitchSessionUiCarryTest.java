package com.pijava.tui.app;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.extension.ExtensionUI;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.util.TuiEventDispatcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 17 评审回补（Important #2，TUI 臂）：{@code PiTuiApp.switchSession} 把模式层
 * 扩展 UI/mode 携带到换入会话（pi finishSessionReplacement→rebindSession 对位，
 * interactive-mode.ts:617-620）——/new、/fork、/resume 全会话换都过这一缝。
 */
class SwitchSessionUiCarryTest {

    /** 计数 UI（装配面断言）。 */
    private static final class CountingUI implements ExtensionUI {
        final AtomicInteger invalidations = new AtomicInteger();

        @Override
        public void onSessionInvalidated() {
            invalidations.incrementAndGet();
        }
    }

    @TempDir
    Path tmp;

    private ClassLoader previousTccl;
    private String savedHome;
    private AgentSession session;

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
        if (savedHome != null) {
            System.setProperty("user.home", savedHome);
        }
        if (previousTccl != null) {
            Thread.currentThread().setContextClassLoader(previousTccl);
        }
    }

    private com.pijava.coding.agent.cli.Args installRig() throws Exception {
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.ai.provider.ProviderFactory"),
            com.pijava.tui.support.ScriptedChatProvider.Factory.class.getName() + "\n");
        Path testClasses = Path.of(getClass().getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(
            new java.net.URL[] {tmp.toUri().toURL(), testClasses.toUri().toURL()},
            previousTccl));
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home",
            Files.createTempDirectory("pi-switch-ui-home").toString());
        return ArgsParser.parse(new String[] {"--provider", "scripted",
            "--model", "hello", "--session-dir",
            Files.createTempDirectory("pi-switch-ui-sessions").toString()});
    }

    @Test
    void switchSessionCarriesExtensionUiAndMode() throws Exception {
        var args = installRig();
        session = AgentSession.create(args);
        var ui = new CountingUI();
        session.extensionUI(ui, "tui");
        var app = new PiTuiApp(new InteractiveMode(session), new ChatScreen(),
            new KeybindingsManager(), new TuiEventDispatcher());

        // /new 同形：替换式新建（noop 烘入）⇒ 切换缝携带模式层 UI。
        var replacing = AgentSession.createReplacing(args, session.sessionId());
        app.switchSession(replacing);
        try {
            assertThat(replacing.extensionUI()).isSameAs(ui);
            assertThat(replacing.extensionMode()).isEqualTo("tui");
        } finally {
            replacing.close();
        }
    }
}
