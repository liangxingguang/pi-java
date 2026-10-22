package com.pijava.tui.app;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.UserMessageSelectorScreen;
import com.pijava.tui.support.ScriptedChatProvider;
import com.pijava.tui.util.TuiEventDispatcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * docs/21（T3，B254）{@code /fork}＝user-message 选择器的接线对拍（pi
 * {@code showUserMessageSelector}＋onSelect 回调，interactive-mode.ts:5500-5538）：
 * 空表预检／选中 fork＋编辑器回填／Esc 只关／before_fork cancel 静默／异常转
 * showError。不跑 runner：斜杠与选择器键都经 {@code onInlineEvent} 直驱
 * （overlay 循环＝PiTuiApp.onKeyEvent 本体）。
 */
class ForkSelectorFlowTest {

    /** 探针扩展：session_before_fork 恒 cancel（④）。 */
    public static final class CancelForkProbe implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public CancelForkProbe() {}

        @Override public String name() { return "cancel-fork-probe"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.on(ExtensionEventSpec.SESSION_BEFORE_FORK,
                (e, c) -> new ExtensionResults.SessionBeforeForkResult(true, null));
        }
    }

    @TempDir
    Path tmp;

    private AgentSession session;
    private InteractiveMode mode;
    private ChatScreen chatScreen;
    private TuiEventDispatcher dispatcher;
    private PiTuiApp app;
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

    private void newRig(boolean installCancelProbe, boolean drivePrompts) throws Exception {
        // TCCL 注入：provider factory＋（按需）cancel 探针扩展（TreeNavigationFlowTest 先例）。
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.ai.provider.ProviderFactory"),
            ScriptedChatProvider.Factory.class.getName() + "\n");
        if (installCancelProbe) {
            Files.writeString(
                servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
                CancelForkProbe.class.getName() + "\n");
        }
        Path testClasses = Path.of(getClass().getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(
            new java.net.URL[] {tmp.toUri().toURL(), testClasses.toUri().toURL()},
            previousTccl));
        var home = Files.createTempDirectory("pi-fork-home");
        var sessionDir = Files.createTempDirectory("pi-fork-session");
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
        app = new PiTuiApp(mode, chatScreen, new KeybindingsManager(), dispatcher);
        if (drivePrompts) {
            provider.enqueue("r1");
            provider.enqueue("r2");
            session.processPrompt("first question").status();
            session.processPrompt("second question").status();
        }
    }

    private static final dev.tamboui.tui.event.KeyEvent ENTER =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ENTER);
    private static final dev.tamboui.tui.event.KeyEvent ESC =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE);

    /** 输入并提交一行（斜杠结果经 dispatcher 排回 ⇒ 排干后 overlay 就位）。 */
    private void submitLine(String text) {
        chatScreen.insertText(text);
        app.onInlineEvent(ENTER);
        dispatcher.drain();
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

    /** ①：空表 ⇒ "No messages to fork from"，不开选择器（pi :5503-5506）。 */
    @Test
    void emptyForkTableShowsStatusWithoutOverlay() throws Exception {
        newRig(false, false);
        submitLine("/fork");

        assertThat(app.currentOverlay()).as("空表不开选择器").isNull();
        assertThat(messageTexts()).anyMatch(t -> t.equals("No messages to fork from"));
    }

    /** ②：选中 ⇒ fork＋切换＋编辑器回填选中消息文本＋"Forked to new session"
     *  （pi :5514-5525）；初始选中＝最后一条（pi :5508）。 */
    @Test
    void selectingMessageForksAndBackfillsEditor() throws Exception {
        newRig(false, true);
        submitLine("/fork");
        var overlay = app.currentOverlay();
        assertThat(overlay).isInstanceOf(UserMessageSelectorScreen.class);

        app.onInlineEvent(ENTER);   // 初始选中＝最后一条 "second question"
        dispatcher.drain();
        awaitTrue(() -> messageTexts().stream()
            .anyMatch(t -> t.equals("Forked to new session")), "fork 完成");

        assertThat(app.currentOverlay()).as("选择器已关").isNull();
        assertThat(chatScreen.inputText())
            .as("pi :5522——editor.setText(selectedText)").isEqualTo("second question");

        // 会话确已切换：fork-before"second question" ⇒ 新会话 fork 表只剩
        // "first question"（未切换则初始选中仍是 "second question"）。
        chatScreen.clearInput();   // 回填的 "second question" 先清掉，否则 /fork 插在其后
        submitLine("/fork");
        assertThat(app.currentOverlay()).isInstanceOf(UserMessageSelectorScreen.class);
        app.onInlineEvent(ENTER);
        dispatcher.drain();
        awaitTrue(() -> "first question".equals(chatScreen.inputText()),
            "新会话 fork 表只剩 first question");
    }

    /** ③：Esc ⇒ 只关选择器，无后续（pi onCancel :5529）。 */
    @Test
    void escapeClosesSelectorWithoutFork() throws Exception {
        newRig(false, true);
        submitLine("/fork");
        assertThat(app.currentOverlay()).isNotNull();

        app.onInlineEvent(ESC);
        dispatcher.drain();

        assertThat(app.currentOverlay()).isNull();
        assertThat(messageTexts())
            .noneMatch(t -> t.contains("Forked") || t.contains("Error"));
        assertThat(chatScreen.inputText()).isEmpty();
    }

    /** ④：session_before_fork cancel ⇒ 无切换无文案（pi :5517-5520 仅 requestRender）。 */
    @Test
    void beforeForkCancelKeepsSessionAndStaysSilent() throws Exception {
        newRig(true, true);
        submitLine("/fork");
        assertThat(app.currentOverlay()).isNotNull();

        app.onInlineEvent(ENTER);
        dispatcher.drain();

        assertThat(app.currentOverlay()).isNull();
        assertThat(messageTexts())
            .noneMatch(t -> t.contains("Forked") || t.contains("Error"));
        assertThat(chatScreen.inputText()).isEmpty();
        // 会话未切换：fork 表仍是两条。
        submitLine("/fork");
        var overlay = app.currentOverlay();
        assertThat(overlay).isInstanceOf(UserMessageSelectorScreen.class);
        app.onInlineEvent(ESC);   // 收尾关选择器（cancel 探针仍在，别再 ENTER）
        dispatcher.drain();
    }

    /** ⑤：fork 抛错 ⇒ showError（pi :5526-5527）——选择器条目过期（打开后换了
     *  无此 entry 的会话）即自然异常路径。 */
    @Test
    void forkFailureShowsError() throws Exception {
        newRig(false, true);
        submitLine("/fork");
        assertThat(app.currentOverlay()).isNotNull();

        // 打开选择器后换一个不含该 entry 的空会话 ⇒ entriesBefore 抛出
        // （pi 的 "Invalid entry ID for forking" 对位臂，窄差 D51 在册）。
        var fresh = AgentSession.create(ArgsParser.parse(new String[] {
            "--provider", "scripted", "--model", "hello", "--no-session"}));
        app.switchSession(fresh);
        app.onInlineEvent(ENTER);
        dispatcher.drain();

        assertThat(messageTexts())
            .anyMatch(t -> t.startsWith("Error: ") && t.contains("is not on this session's branch"));
        fresh.close();
    }
}
