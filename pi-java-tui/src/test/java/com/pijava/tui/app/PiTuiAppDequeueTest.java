package com.pijava.tui.app;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.KeybindingHints;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.support.ScriptedChatProvider;
import com.pijava.tui.util.TuiEventDispatcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * docs/21（T4/T5，S20＋D41 重裁）dequeue 链与 Esc 流式臂的接线对拍：
 * pi {@code handleDequeue}（:4507-4513 文案逐字）、合并式（:4752-4756）、
 * Esc 首臂 restore＋abort（:3071-3073）、排队区 hint 行（:4736-4738）。
 * 不跑 runner：键经 {@code onInlineEvent} 直驱（全局 resolve→handleAction 本体）。
 */
class PiTuiAppDequeueTest {

    @TempDir
    Path tmp;

    private AgentSession session;
    private InteractiveMode mode;
    private ChatScreen chatScreen;
    private TuiEventDispatcher dispatcher;
    private PiTuiApp app;
    private KeybindingsManager keys;
    private ScriptedChatProvider provider;
    private ClassLoader previousTccl;

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
        var home = Files.createTempDirectory("pi-dequeue-home");
        var sessionDir = Files.createTempDirectory("pi-dequeue-session");
        var args = ArgsParser.parse(new String[] {"--provider", "scripted",
            "--model", "hello", "--session-dir", sessionDir.toString()});
        String savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            session = AgentSession.create(args);
        } finally {
            System.setProperty("user.home", savedHome);
        }
        session.services().settings().accessors().setCompactionEnabled(false);
        provider = ScriptedChatProvider.Factory.lastInstance();
        mode = new InteractiveMode(session);
        chatScreen = new ChatScreen();
        dispatcher = new TuiEventDispatcher();
        keys = new KeybindingsManager();
        app = new PiTuiApp(mode, chatScreen, keys, dispatcher);
    }

    /** 起跑一个 600ms 的在飞 run（Steer 排队窗口）。 */
    private void startHangingRun() {
        provider.delayNextCall(600);
        session.processPrompt("hello");
        awaitTrue(() -> session.isRunning(), "run 起跑");
    }

    /** 平台表的 dequeue 键 ⇒ 真实 KeyEvent（Windows/WSL=alt+q，其余=alt+up）。 */
    private dev.tamboui.tui.event.KeyEvent dequeueKey() {
        var stroke = keys.strokeFor(KeybindingsManager.DEQUEUE);
        if ("up".equals(stroke.key())) {
            return new dev.tamboui.tui.event.KeyEvent(
                dev.tamboui.tui.event.KeyCode.UP,
                dev.tamboui.tui.event.KeyModifiers.ALT, 0);
        }
        return dev.tamboui.tui.event.KeyEvent.ofChar(
            stroke.key().charAt(0), dev.tamboui.tui.event.KeyModifiers.ALT);
    }

    private List<String> messageTexts() {
        return chatScreen.messages().stream().map(m -> switch (m) {
            case ChatMessage.User u -> u.text();
            case ChatMessage.Assistant a -> ChatMessage.joinText(a.blocks());
            case ChatMessage.System s -> s.text();
            case ChatMessage.Error e -> e.message();
            case ChatMessage.ToolCall t -> t.name();
            case ChatMessage.ToolResult tr -> "";
            case ChatMessage.TurnSeparator ts -> ts.label();
            case ChatMessage.Padded p -> "padded:" + p.pad();
        }).toList();
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
        fail("超时: " + what + "；当前消息=" + messageTexts());
    }

    /** ①：无排队 ⇒ "No queued messages to restore"（pi :4509-4510 逐字）。 */
    @Test
    void dequeueWithEmptyQueuesShowsStatus() throws Exception {
        newRig();
        app.onInlineEvent(dequeueKey());
        dispatcher.drain();

        assertThat(messageTexts()).anyMatch(t -> t.equals("No queued messages to restore"));
        assertThat(chatScreen.inputText()).isEmpty();
    }

    /** ②：排队在前草稿在后、空白段丢弃（pi :4752-4756）＋复数文案（pi :4512）。 */
    @Test
    void dequeueRestoresQueuedMessagesAheadOfDraft() throws Exception {
        newRig();
        startHangingRun();
        mode.submit("queued one");     // 运行中 ⇒ steer 排队
        mode.submit("queued two");
        chatScreen.insertText("draft");

        app.onInlineEvent(dequeueKey());
        dispatcher.drain();

        assertThat(chatScreen.inputText())
            .isEqualTo("queued one\n\nqueued two\n\ndraft");
        assertThat(messageTexts())
            .anyMatch(t -> t.equals("Restored 2 queued messages to editor"));
        // 队列已清：再按 ⇒ 空臂文案（单数形对照在 ①③）。
        app.onInlineEvent(dequeueKey());
        dispatcher.drain();
        assertThat(messageTexts()).anyMatch(t -> t.equals("No queued messages to restore"));
        // abort 非阻塞：等 run 落地再收尾，否则 close 与 run 收尾并发双写持久面。
        session.abort();
        session.harness().waitForIdle(session.laneName());
    }

    /** ③（T5）：流式中 Esc ⇒ restore 先行（编辑器得排队文本）再 abort（pi :3071-3073）。 */
    @Test
    void escWhileStreamingRestoresQueuedThenAborts() throws Exception {
        newRig();
        startHangingRun();
        mode.submit("queued under esc");

        app.onInlineEvent(dev.tamboui.tui.event.KeyEvent.ofKey(
            dev.tamboui.tui.event.KeyCode.ESCAPE));
        dispatcher.drain();

        assertThat(chatScreen.inputText()).isEqualTo("queued under esc");
        awaitTrue(() -> !session.isRunning(), "Esc 后 run 已 abort");
    }

    /** ④：排队区 hint 行（pi :4736-4738）——键名随平台表（装配注入的大写形）。 */
    @Test
    void pendingQueueHintShowsThePlatformDequeueKey() throws Exception {
        newRig();
        chatScreen.onSessionEvent(new AgentSessionEvent.QueueUpdate(
            List.of("turn here"), List.of()));

        var rows = renderText(chatScreen.render(), 100, 40);
        var expectedKey = KeybindingHints.displayText(keys.strokeFor(KeybindingsManager.DEQUEUE));
        assertThat(rows).anyMatch(r -> r.contains(
            "↳ " + expectedKey + " to edit all queued messages"));

        chatScreen.onSessionEvent(new AgentSessionEvent.QueueUpdate(List.of(), List.of()));
        rows = renderText(chatScreen.render(), 100, 40);
        assertThat(rows).noneMatch(r -> r.contains("to edit all queued messages"));
    }

    // ── 渲染 helper（ChatScreenSessionEventTest 同款 buffer 手法）─────

    private static List<String> renderText(dev.tamboui.toolkit.element.Element element,
                                           int width, int height) throws Exception {
        var buffer = dev.tamboui.buffer.Buffer.empty(new dev.tamboui.layout.Rect(0, 0, width, height));
        var frame = dev.tamboui.terminal.Frame.forTesting(buffer);
        var focus = new dev.tamboui.toolkit.focus.FocusManager();
        markRenderThread(true);
        try {
            element.render(frame, new dev.tamboui.layout.Rect(0, 0, width, height),
                new dev.tamboui.toolkit.element.DefaultRenderContext(focus,
                    new dev.tamboui.toolkit.event.EventRouter(focus,
                        new dev.tamboui.toolkit.element.ElementRegistry())));
        } finally {
            markRenderThread(false);
        }
        var rows = new ArrayList<String>();
        for (int y = 0; y < height; y++) {
            var row = new StringBuilder();
            for (int x = 0; x < width; x++) {
                var cell = buffer.get(x, y);
                if (!cell.isContinuation()) {
                    row.append(cell.symbol());
                }
            }
            rows.add(row.toString().stripTrailing());
        }
        return rows;
    }

    /** TamboUI asserts element registration happens on the render thread. */
    private static void markRenderThread(boolean on) throws Exception {
        var method = dev.tamboui.tui.RenderThread.class.getDeclaredMethod(
            on ? "markAsRenderThread" : "clearRenderThread");
        method.setAccessible(true);
        method.invoke(null);
    }
}
