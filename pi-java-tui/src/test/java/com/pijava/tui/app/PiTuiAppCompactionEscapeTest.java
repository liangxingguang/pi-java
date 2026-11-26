package com.pijava.tui.app;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.support.ScriptedChatProvider;
import com.pijava.tui.util.TuiEventDispatcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * B39（pi interactive-mode.ts:3866-3893）：压缩窗口内 Esc 的唯一语义是
 * {@code abortCompaction}——不得走双击 Esc 的 tree/fork 臂，也不得回填排队消息。
 */
class PiTuiAppCompactionEscapeTest {

    private static final dev.tamboui.tui.event.KeyEvent ESC =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE);

    @TempDir
    Path tmp;

    private AgentSession session;
    private ChatScreen chatScreen;
    private TuiEventDispatcher dispatcher;
    private PiTuiApp app;
    private ScriptedChatProvider provider;
    private ClassLoader previousTccl;
    private String savedHome;
    private final AtomicLong now = new AtomicLong(1_000_000);

    @AfterEach
    void tearDown() {
        if (previousTccl != null) {
            Thread.currentThread().setContextClassLoader(previousTccl);
            previousTccl = null;
        }
        if (session != null) {
            session.close();
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
        var home = Files.createTempDirectory("pi-compaction-esc-home");
        var sessionDir = Files.createTempDirectory("pi-compaction-esc-session");
        var args = ArgsParser.parse(new String[] {"--provider", "scripted",
            "--model", "hello", "--session-dir", sessionDir.toString()});
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        session = AgentSession.create(args);
        provider = ScriptedChatProvider.Factory.lastInstance();
        chatScreen = new ChatScreen();
        dispatcher = new TuiEventDispatcher();
        app = new PiTuiApp(new InteractiveMode(session), chatScreen,
            new KeybindingsManager(), dispatcher);
        app.setNowMillis(now::get);
    }

    private void seedOneTurn() {
        session.processPrompt("hello");
        awaitTrue(() -> !session.isRunning(), "run 收尾");
    }

    @Test
    void escapeDuringCompactionAbortsItInsteadOfOpeningTheTreeSelector() throws Exception {
        newRig();
        seedOneTurn();
        // 小保留预算 ⇒ 存在可摘要的切点；摘要闩把压缩窗口定住。
        provider.armSummaryGate();
        var outcome = new java.util.concurrent.atomic.AtomicReference<Object>();
        var compacting = new Thread(() -> {
            try {
                outcome.set(session.compact(
                    new com.pijava.agent.compaction.CompactionSettings(true, 16_384, 0)));
            } catch (RuntimeException expected) {
                outcome.set(expected);
            }
        });
        compacting.start();
        awaitTrue(session::isCompacting, "压缩窗口打开");

        app.onInlineEvent(ESC);
        provider.releaseSummary();

        awaitTrue(() -> !session.isCompacting(), "Esc 后压缩已中止");
        compacting.join(5_000);
        assertThat(outcome.get()).isInstanceOf(IllegalStateException.class);
        assertThat((IllegalStateException) outcome.get())
            .hasMessage("Compaction cancelled");
        assertThat(app.currentOverlay()).isNull();

        // 中止臂把 lastEscapeAt 归零：紧接着的下一击只记账，不组成双击。
        now.addAndGet(100);
        app.onInlineEvent(ESC);
        assertThat(app.currentOverlay()).isNull();
    }

    private void awaitTrue(BooleanSupplier cond, String what) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            dispatcher.drain();
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        dispatcher.drain();
        fail("超时: " + what);
    }
}
