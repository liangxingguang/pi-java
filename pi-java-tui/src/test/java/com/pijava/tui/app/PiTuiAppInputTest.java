package com.pijava.tui.app;

import java.nio.file.Path;
import java.time.Duration;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.component.EditorComponent;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.SettingsScreen;
import com.pijava.tui.theme.ThemeApplicator;
import com.pijava.tui.util.TuiEventDispatcher;

import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.Event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end input path with a fake terminal backend: bytes typed by the user
 * go through the real TamboUI event parser, routing, and our submit logic.
 * Regression test for "typing /help and /settings does nothing".
 */
class PiTuiAppInputTest {

    /**
     * 夹具会话的落盘目录（原 docs/39 裁决 B）。
     *
     * <p>空 args ⇒ 既无 {@code --session-dir} 也无 {@code --no-session} ⇒ 走持久
     * 路径，在开发者**真实 home** 的 {@code --<模块目录>--} 下每次跑测试留一个会话
     * 文件。这些残渣只会被别的项目（web 的 {@code ready}）扫到 —— A12 的自放大回路。</p>
     */
    @TempDir
    Path sessionDir;

    @Test
    void slashCommandsRespondToSendKey() throws Exception {
        var backend = new FakeBackend();
        var runner = ToolkitRunner.create(
            TuiConfig.builder().backend(backend).build());
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}))) {
            var chatScreen = new ChatScreen();
            var mode = new InteractiveMode(session);
            var dispatcher = new TuiEventDispatcher();
            var app = new PiTuiApp(mode, chatScreen,
                new KeybindingsManager(), dispatcher);
            var routed = new CopyOnWriteArrayList<Event>();
            runner.eventRouter().addGlobalHandler(event -> {
                routed.add(event);
                return EventResult.UNHANDLED;
            });
            mode.setObservers(
                entry -> dispatcher.dispatch(() -> chatScreen.onEntry(entry)),
                event -> dispatcher.dispatch(() -> chatScreen.onStreamEvent(event)));
            app.start(runner);

            var thread = Thread.startVirtualThread(() -> {
                try {
                    runner.run(app::root);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Thread.sleep(300);
            int drawsBefore = backend.drawCount();

            // Type "/help" and press Enter (CR — the send key).
            backend.feed("/help\r");
            Thread.sleep(400);

            assertThat(routed).isNotEmpty();
            assertThat(backend.drawCount()).isGreaterThan(drawsBefore);
            assertThat(chatScreen.messageCount()).isGreaterThan(0);
            // 批 25（docs/36 T8）：/help 结果走 Markdown 渲染（pi Markdown 组件对位）。
            assertThat(chatScreen.lastMessage())
                .isInstanceOf(ChatMessage.Markdown.class);

            // "/settings" opens the settings overlay.
            backend.feed("/settings\r");
            awaitSettingsOverlay(app);

            runner.quit();
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
        } finally {
            runner.close();
        }
    }

    @Test
    void settingsOverlayNavigatesWithArrowKeys() throws Exception {
        var backend = new FakeBackend();
        var runner = ToolkitRunner.create(
            TuiConfig.builder().backend(backend).build());
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}))) {
            var chatScreen = new ChatScreen();
            var mode = new InteractiveMode(session);
            var dispatcher = new TuiEventDispatcher();
            var app = new PiTuiApp(mode, chatScreen,
                new KeybindingsManager(), dispatcher);
            runner.styleEngine(ThemeApplicator.engineForCurrentTheme());
            mode.setObservers(
                entry -> dispatcher.dispatch(() -> chatScreen.onEntry(entry)),
                event -> dispatcher.dispatch(() -> chatScreen.onStreamEvent(event)));
            app.start(runner);

            var thread = Thread.startVirtualThread(() -> {
                try {
                    runner.run(app::root);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Thread.sleep(300);

            backend.feed("/settings\r");
            var overlay = awaitSettingsOverlay(app);

            // Down arrow moves from field 0 to field 1, Up returns to 0.
            backend.feed("\u001b[B");
            awaitSelectedField(overlay, 1);

            backend.feed("\u001b[A");
            awaitSelectedField(overlay, 0);

            runner.quit();
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
        } finally {
            runner.close();
        }
    }

    @Test
    void streamedTextIsRenderedByTickAndCommitted() throws Exception {
        var backend = new FakeBackend();
        var runner = ToolkitRunner.create(TuiConfig.builder()
            .backend(backend)
            .tickRate(Duration.ofMillis(50))
            .build());
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}))) {
            var chatScreen = new ChatScreen();
            var mode = new InteractiveMode(session);
            var dispatcher = new TuiEventDispatcher();
            var app = new PiTuiApp(mode, chatScreen,
                new KeybindingsManager(), dispatcher);
            mode.setObservers(
                entry -> dispatcher.dispatch(() -> chatScreen.onEntry(entry)),
                event -> dispatcher.dispatch(() -> chatScreen.onStreamEvent(event)));
            app.start(runner);

            var thread = Thread.startVirtualThread(() -> {
                try {
                    runner.run(app::root);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Thread.sleep(200);
            var drawsBefore = backend.drawCount();

            // Simulate the model streaming from the virtual thread: the 50ms
            // tick must wake the render loop so the draft bubble updates.
            Thread.startVirtualThread(() -> {
                for (int i = 0; i < 20; i++) {
                    dispatcher.dispatch(() -> chatScreen.onStreamEvent(
                        new StreamEvent.TextDelta(0, "x", AssistantMessage.empty())));
                    try {
                        Thread.sleep(15);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                dispatcher.dispatch(() -> chatScreen.onStreamEvent(
                    new StreamEvent.TextEnd(0, "xxxxxxxxxxxxxxxxxxxx",
                        AssistantMessage.empty())));
            });

            Thread.sleep(700);

            assertThat(backend.drawCount()).isGreaterThan(drawsBefore);
            assertThat(chatScreen.lastMessage())
                .isInstanceOf(ChatMessage.Assistant.class);

            runner.quit();
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
        } finally {
            runner.close();
        }
    }

    @Test
    void inputRowShowsPromptAndCursorInRealLayout() throws Exception {
        var backend = new FakeBackend();
        var runner = ToolkitRunner.create(
            TuiConfig.builder().backend(backend).build());
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}))) {
            var chatScreen = new ChatScreen();
            var mode = new InteractiveMode(session);
            var dispatcher = new TuiEventDispatcher();
            var app = new PiTuiApp(mode, chatScreen,
                new KeybindingsManager(), dispatcher);
            mode.setObservers(
                entry -> dispatcher.dispatch(() -> chatScreen.onEntry(entry)),
                event -> dispatcher.dispatch(() -> chatScreen.onStreamEvent(event)));
            app.start(runner);

            var thread = Thread.startVirtualThread(() -> {
                try {
                    runner.run(app::root);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Thread.sleep(300);
            backend.feed("你好");
            Thread.sleep(300);

            // The input row must show a "> " prompt and a reversed cursor
            // block in the real column layout (regression for "no prompt,
            // no cursor, editor not at the bottom").
            assertThat(backend.hasLineContaining("> ")).isTrue();
            assertThat(backend.hasCursorCell()).isTrue();

            runner.quit();
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
        } finally {
            runner.close();
        }
    }

    @Test
    void enterSubmitsAndLfInsertsNewline() throws Exception {
        var backend = new FakeBackend();
        var runner = ToolkitRunner.create(
            TuiConfig.builder().backend(backend).build());
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}))) {
            var chatScreen = new ChatScreen();
            var mode = new InteractiveMode(session);
            var dispatcher = new TuiEventDispatcher();
            var app = new PiTuiApp(mode, chatScreen,
                new KeybindingsManager(), dispatcher);
            mode.setObservers(
                entry -> dispatcher.dispatch(() -> chatScreen.onEntry(entry)),
                event -> dispatcher.dispatch(() -> chatScreen.onStreamEvent(event)));
            app.start(runner);

            var thread = Thread.startVirtualThread(() -> {
                try {
                    runner.run(app::root);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Thread.sleep(300);
            backend.feed("hello");
            backend.feed("\r"); // plain Enter (CR): submit
            awaitUserMessage(chatScreen, "hello");

            assertThat(chatScreen.inputText()).isEmpty();

            backend.feed("hi");
            backend.feed("\n"); // LF: Shift+Enter/Ctrl+J fallback → newline
            Thread.sleep(200);

            assertThat(chatScreen.inputText()).isEqualTo("hi\n");

            backend.feed("\r"); // Enter submits the multi-line draft
            awaitUserMessage(chatScreen, "hi\n");
            assertThat(chatScreen.inputText()).isEmpty();

            runner.quit();
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
        } finally {
            runner.close();
        }
    }

    @Test
    void submittedUserMessageRendersAsBubble() throws Exception {
        var backend = new FakeBackend();
        var runner = ToolkitRunner.create(
            TuiConfig.builder().backend(backend).build());
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}))) {
            var chatScreen = new ChatScreen();
            var mode = new InteractiveMode(session);
            var dispatcher = new TuiEventDispatcher();
            var app = new PiTuiApp(mode, chatScreen,
                new KeybindingsManager(), dispatcher);
            mode.setObservers(
                entry -> dispatcher.dispatch(() -> chatScreen.onEntry(entry)),
                event -> dispatcher.dispatch(() -> chatScreen.onStreamEvent(event)));
            app.start(runner);

            var thread = Thread.startVirtualThread(() -> {
                try {
                    runner.run(app::root);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Thread.sleep(300);
            backend.feed("你好\r");
            Thread.sleep(400);

            // The optimistic user bubble must appear in the rendered frame,
            // even though the model run may still be in flight or fail
            // (a failure appends an Error message after it).
            assertThat(chatScreen.messageCount()).isGreaterThan(0);
            assertThat(chatScreen.messages())
                .anyMatch(message -> message instanceof ChatMessage.User);
            assertThat(backend.hasLineContaining("你")).isTrue();
            // The theme must actually style the bubble (regression for the
            // white-box look when TCSS selectors didn't match).
            assertThat(backend.hasBackgroundCells()).isTrue();
            // No white border around bubbles; the themed background is enough.
            // The startup card legitimately uses a rounded box, so scope the
            // check to the user-message row.
            assertThat(backend.lastDrawLines().stream()
                .filter(line -> line.contains("你好"))
                .noneMatch(line -> line.contains("╭"))).isTrue();

            runner.quit();
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
        } finally {
            runner.close();
        }
    }

    private static void awaitUserMessage(ChatScreen chatScreen, String text)
            throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            var found = chatScreen.messages().stream()
                .filter(ChatMessage.User.class::isInstance)
                .map(ChatMessage.User.class::cast)
                .anyMatch(user -> text.equals(user.text()));
            if (found) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(chatScreen.messages())
            .as("user message submitted: " + text)
            .anyMatch(m -> m instanceof ChatMessage.User user
                && text.equals(user.text()));
    }

    private static int selectedField(Object overlay) {
        var id = ((SettingsScreen) overlay).selectedId();
        return switch (id) {
            case "autocompact" -> 0;
            case "auto-resize-images" -> 1;
            default -> -1;
        };
    }

    private static void awaitSelectedField(Object overlay, int expected)
            throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (selectedField(overlay) == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(selectedField(overlay)).isEqualTo(expected);
    }

    private static SettingsScreen awaitSettingsOverlay(PiTuiApp app)
            throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            var overlay = app.currentOverlay();
            if (overlay instanceof SettingsScreen screen) {
                return screen;
            }
            Thread.sleep(50);
        }
        assertThat(app.currentOverlay()).isInstanceOf(SettingsScreen.class);
        return null;
    }

}
