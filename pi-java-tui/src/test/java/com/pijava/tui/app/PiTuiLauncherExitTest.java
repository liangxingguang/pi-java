package com.pijava.tui.app;

import java.io.ByteArrayOutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.support.ScriptedChatProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 批 12 T10（docs/23 §3/§7）：fullscreen 退出两臂＋恢复提示——默认
 * （transcript）⇒ TranscriptDump 倾倒对话；resume-hint ⇒ 不倾倒；持久化
 * 会话 ⇒ 提示行打印；未持久化 ⇒ 不打（pi :881-887＋:4322-4328，D64 文案）。
 */
class PiTuiLauncherExitTest {

    @TempDir
    Path tmp;

    private AgentSession session;
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

    /** 持久化会话（scripted provider，一条已完成的 user 消息）。 */
    private AgentSession newPersistedSession() throws Exception {
        return newSession(new String[] {"--provider", "scripted", "--model", "hello",
            "--session-dir", Files.createTempDirectory("pi-exit-session").toString()});
    }

    private AgentSession newSession(String[] argv) throws Exception {
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
        var home = Files.createTempDirectory("pi-exit-home");
        // ⚠️ user.home 保持到 tearDown（settings 懒解析——create 后恢复会让
        // 首次访问与 close flush 落进真实 home）。
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        return AgentSession.create(ArgsParser.parse(argv));
    }

    private void sendMessage(String text) {
        session.processPrompt(text);
        awaitTrue(() -> !session.accumulatedEntries().isEmpty(), "条目落地");
        awaitTrue(() -> !session.isRunning(), "run 收尾");
    }

    private static String capture(ByteArrayOutputStream buffer) {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static java.io.PrintStream printInto(ByteArrayOutputStream buffer) {
        return new java.io.PrintStream(buffer, true, StandardCharsets.UTF_8);
    }

    /** 默认（transcript 臂）：对话倾倒＋恢复提示都在。 */
    @Test
    void transcriptArmDumpsConversationAndPrintsResumeHint() throws Exception {
        session = newPersistedSession();
        sendMessage("exit-dump-marker");

        var buffer = new ByteArrayOutputStream();
        PiTuiLauncher.afterFullscreenExit(session, 80, printInto(buffer));
        var out = capture(buffer);

        assertThat(out).contains("exit-dump-marker");
        assertThat(out).contains("To resume this session: pi-java -r");
    }

    /** resume-hint 臂：不倾倒对话，只留提示行（pi :406-408 alt 屏内容丢弃）。 */
    @Test
    void resumeHintArmSkipsTheTranscriptDump() throws Exception {
        session = newPersistedSession();
        sendMessage("should-not-appear");
        session.services().settings().uiPrefs().setFullscreenExitOutput("resume-hint");

        var buffer = new ByteArrayOutputStream();
        PiTuiLauncher.afterFullscreenExit(session, 80, printInto(buffer));
        var out = capture(buffer);

        assertThat(out).doesNotContain("should-not-appear");
        assertThat(out).contains("To resume this session: pi-java -r");
    }

    /** 未持久化会话（--no-session）：两臂都不打提示行（pi 仅已持久化）。 */
    @Test
    void unpersistedSessionPrintsNoResumeHint() throws Exception {
        session = newSession(new String[] {"--provider", "scripted", "--model", "hello",
            "--no-session"});
        sendMessage("transient-marker");

        var buffer = new ByteArrayOutputStream();
        PiTuiLauncher.afterFullscreenExit(session, 80, printInto(buffer));
        var out = capture(buffer);

        assertThat(out).doesNotContain("To resume this session");
    }

    private void awaitTrue(BooleanSupplier cond, String what) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
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
        fail("超时: " + what);
    }
}
