package com.pijava.tui.app;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.support.ScriptedChatProvider;
import com.pijava.tui.util.InlineTuiShell;
import com.pijava.tui.util.TuiEventDispatcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 批 12 T2（docs/23 §3/§7）：双击 Esc 行为链对拍（pi
 * interactive-mode.ts:3067-3097）——空闲＋空编辑器＋500ms 内双击 ⇒
 * tree/fork overlay 开；&gt;500ms ⇒ 不开；action=none ⇒ 不开；流式中 ⇒
 * abort 消费不计双击；编辑器非空 ⇒ 不开；默认缺席 ⇒ tree。
 * 时间经 {@code setNowMillis} 注入；不跑 runner（inline 壳直驱）。
 */
class PiTuiAppDoubleEscapeTest {

    private static final dev.tamboui.tui.event.KeyEvent ESC =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE);

    @TempDir
    Path tmp;

    private AgentSession session;
    private InteractiveMode mode;
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
        var home = Files.createTempDirectory("pi-double-esc-home");
        var sessionDir = Files.createTempDirectory("pi-double-esc-session");
        var args = ArgsParser.parse(new String[] {"--provider", "scripted",
            "--model", "hello", "--session-dir", sessionDir.toString()});
        // ⚠️ user.home 保持到 tearDown（settings 懒解析——create 后恢复会让
        // 首次访问与 close flush 落进真实 home，本批已踩过）。
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        session = AgentSession.create(args);
        provider = ScriptedChatProvider.Factory.lastInstance();
        mode = new InteractiveMode(session);
        chatScreen = new ChatScreen();
        dispatcher = new TuiEventDispatcher();
        app = new PiTuiApp(mode, chatScreen, new KeybindingsManager(), dispatcher);
        app.setNowMillis(now::get);
    }

    /** 造一条条目（pi :5563-5566：空树不打开——树选择器需要非空会话树）。 */
    private void seedOneEntry() {
        session.processPrompt("hello");
        awaitTrue(() -> !session.accumulatedEntries().isEmpty(), "条目落地");
        awaitTrue(() -> !session.isRunning(), "run 收尾");
    }

    /** 默认（缺席）⇒ tree：500ms 内双击开树选择器 overlay。 */
    @Test
    void doubleEscapeOpensTreeSelectorByDefault() throws Exception {
        newRig();
        seedOneEntry();

        app.onInlineEvent(ESC);
        assertThat(app.currentOverlay()).isNull();
        now.addAndGet(200);
        app.onInlineEvent(ESC);

        assertThat(app.currentOverlay()).isNotNull();
    }

    /** >500ms ⇒ 不开（pi 时间窗）。 */
    @Test
    void escapeBeyondWindowDoesNotOpen() throws Exception {
        newRig();
        seedOneEntry();

        app.onInlineEvent(ESC);
        now.addAndGet(600);
        app.onInlineEvent(ESC);

        assertThat(app.currentOverlay()).isNull();
        // 第二击已重置时间戳：紧接着第三击 ⇒ 开。
        now.addAndGet(100);
        app.onInlineEvent(ESC);
        assertThat(app.currentOverlay()).isNotNull();
    }

    /** action=fork ⇒ user-message 选择器（先造一条 user 消息）。 */
    @Test
    void doubleEscapeWithForkActionOpensForkSelector() throws Exception {
        newRig();
        session.services().settings().accessors().setDoubleEscapeAction("fork");
        session.processPrompt("hello");
        awaitTrue(() -> !session.accumulatedEntries().isEmpty(), "user 消息落地");
        awaitTrue(() -> !session.isRunning(), "run 收尾");

        app.onInlineEvent(ESC);
        now.addAndGet(200);
        app.onInlineEvent(ESC);

        assertThat(app.currentOverlay())
            .isInstanceOf(com.pijava.tui.screen.UserMessageSelectorScreen.class);
    }

    /** fork 臂无可 fork 消息 ⇒ pi :5503-5506 的提示行。 */
    @Test
    void doubleEscapeForkWithoutMessagesShowsNotice() throws Exception {
        newRig();
        session.services().settings().accessors().setDoubleEscapeAction("fork");

        app.onInlineEvent(ESC);
        now.addAndGet(200);
        app.onInlineEvent(ESC);

        assertThat(app.currentOverlay()).isNull();
        assertThat(chatScreen.messages().stream()
            .map(Object::toString).toList().toString())
            .contains("No messages to fork from");
    }

    /** action=none ⇒ 双击也不开。 */
    @Test
    void doubleEscapeWithNoneActionDoesNothing() throws Exception {
        newRig();
        session.services().settings().accessors().setDoubleEscapeAction("none");

        app.onInlineEvent(ESC);
        now.addAndGet(200);
        app.onInlineEvent(ESC);

        assertThat(app.currentOverlay()).isNull();
    }

    /** 编辑器非空 ⇒ 双击不开（abort 臂，幂等）。 */
    @Test
    void doubleEscapeWithNonEmptyEditorDoesNotOpen() throws Exception {
        newRig();
        chatScreen.insertText("draft");

        app.onInlineEvent(ESC);
        now.addAndGet(200);
        app.onInlineEvent(ESC);

        assertThat(app.currentOverlay()).isNull();
    }

    /** 流式中 Esc ⇒ restore＋abort 消费，不计双击（lastEscapeAt 归零）。 */
    @Test
    void escapeWhileStreamingDoesNotCountTowardDoubleEscape() throws Exception {
        newRig();
        provider.delayNextCall(600);
        session.processPrompt("hello");
        awaitTrue(() -> session.isRunning(), "run 起跑");

        // 流式 Esc（abort 消费）后立刻双击 ⇒ 只算一击，不开。
        app.onInlineEvent(ESC);
        awaitTrue(() -> !session.isRunning(), "Esc 后 run 已 abort");
        now.addAndGet(100);
        app.onInlineEvent(ESC);
        assertThat(app.currentOverlay()).isNull();
        // 再一击（与上一击同窗）⇒ 开。
        now.addAndGet(100);
        app.onInlineEvent(ESC);
        assertThat(app.currentOverlay()).isNotNull();
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
