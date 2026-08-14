package com.pijava.tui.app;

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
import com.pijava.tui.theme.PiTheme;
import com.pijava.tui.util.TuiEventDispatcher;

import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.Event;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end input path with a fake terminal backend: bytes typed by the user
 * go through the real TamboUI event parser, routing, and our submit logic.
 * Regression test for "typing /help and /settings does nothing".
 */
class PiTuiAppInputTest {

    @Test
    void slashCommandsRespondToCarriageReturnEnter() throws Exception {
        var backend = new FakeBackend();
        var runner = ToolkitRunner.create(
            TuiConfig.builder().backend(backend).build());
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {}))) {
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

            // Type "/help" and press Enter (sent as carriage return).
            backend.feed("/help\r");
            Thread.sleep(400);

            assertThat(routed).isNotEmpty();
            assertThat(backend.drawCount()).isGreaterThan(drawsBefore);
            assertThat(chatScreen.messageCount()).isGreaterThan(0);
            assertThat(chatScreen.lastMessage())
                .isInstanceOf(ChatMessage.System.class);

            // "/settings" opens the settings overlay.
            backend.feed("/settings\r");
            Thread.sleep(400);
            assertThat(app.currentOverlay()).isNotNull();
            assertThat(app.currentOverlay()).isInstanceOf(
                com.pijava.tui.screen.SettingsScreen.class);

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
                ArgsParser.parse(new String[] {}))) {
            var chatScreen = new ChatScreen();
            var mode = new InteractiveMode(session);
            var dispatcher = new TuiEventDispatcher();
            var app = new PiTuiApp(mode, chatScreen,
                new KeybindingsManager(), dispatcher);
            runner.styleEngine(PiTheme.engineFor("dark"));
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
            Thread.sleep(400);
            var overlay = app.currentOverlay();
            assertThat(overlay).isInstanceOf(SettingsScreen.class);

            // Down arrow moves from field 0 to field 1, Up returns to 0.
            backend.feed("\u001b[B");
            Thread.sleep(300);
            assertThat(selectedField(overlay)).isEqualTo(1);

            backend.feed("\u001b[A");
            Thread.sleep(300);
            assertThat(selectedField(overlay)).isZero();

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
                ArgsParser.parse(new String[] {}))) {
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
                ArgsParser.parse(new String[] {}))) {
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
    void submittedUserMessageRendersAsBubble() throws Exception {
        var backend = new FakeBackend();
        var runner = ToolkitRunner.create(
            TuiConfig.builder().backend(backend).build());
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {}))) {
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
            // even though the model run may still be in flight or fail.
            assertThat(chatScreen.messageCount()).isGreaterThan(0);
            assertThat(chatScreen.lastMessage())
                .isInstanceOf(ChatMessage.User.class);
            assertThat(backend.hasLineContaining("你")).isTrue();
            // The theme must actually style the bubble (regression for the
            // white-box look when TCSS selectors didn't match).
            assertThat(backend.hasBackgroundCells()).isTrue();
            // A panel border must separate the bubble from plain text.
            assertThat(backend.hasLineContaining("╭")).isTrue();

            runner.quit();
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
        } finally {
            runner.close();
        }
    }

    @Test
    void pageUpScrollsChatHistoryWithoutCrashing() throws Exception {
        var backend = new FakeBackend();
        var runner = ToolkitRunner.create(
            TuiConfig.builder().backend(backend).build());
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {}))) {
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
            // Seed enough history to make the list scrollable.
            for (int i = 0; i < 20; i++) {
                chatScreen.appendSystemText("message " + i);
            }
            var drawsBefore = backend.drawCount();

            // PageUp must reach the scrollable list, not the editor.
            backend.feed("\u001b[5~");
            Thread.sleep(300);

            assertThat(backend.drawCount()).isGreaterThan(drawsBefore);
            runner.quit();
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
        } finally {
            runner.close();
        }
    }

    private static int selectedField(Object overlay) throws Exception {
        var field = SettingsScreen.class.getDeclaredField("selected");
        field.setAccessible(true);
        return field.getInt(overlay);
    }
}
