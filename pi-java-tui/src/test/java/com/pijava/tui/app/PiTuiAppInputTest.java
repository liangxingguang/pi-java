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
            // No white border around bubbles; the themed background is enough.
            assertThat(backend.hasLineContaining("╭")).isFalse();

            runner.quit();
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
        } finally {
            runner.close();
        }
    }

    @Test
    void emptyInputLetsChatListScrollHistory() throws Exception {
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
            for (int i = 0; i < 40; i++) {
                int line = i;
                dispatcher.dispatch(
                    () -> chatScreen.appendSystemText("scroll-line-" + line));
            }
            Thread.sleep(300);

            awaitLine(backend, "scroll-line-39",
                "newest message pinned to the bottom before scrolling");

            // PageUp scrolls history: the first message enters the viewport and
            // the newest scrolls out.
            backend.feed("\u001b[5~");
            awaitLine(backend, "scroll-line-0",
                "oldest message visible after PageUp");
            awaitNoLine(backend, "scroll-line-39",
                "newest message scrolled out of view after PageUp");

            // End resumes sticky auto-scroll back to the bottom.
            backend.feed("\u001b[4~");
            awaitLine(backend, "scroll-line-39",
                "End returns to the newest message");

            runner.quit();
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
        } finally {
            runner.close();
        }
    }

    @Test
    void mouseWheelScrollsChatHistory() throws Exception {
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
            for (int i = 0; i < 40; i++) {
                int line = i;
                dispatcher.dispatch(
                    () -> chatScreen.appendSystemText("wheel-line-" + line));
            }
            Thread.sleep(300);

            // SGR mouse wheel-up at the chat area (row 15, 1-based); each
            // notch scrolls three rows, six notches reach the top.
            for (int i = 0; i < 6; i++) {
                backend.feed("\u001b[<64;50;15M");
            }
            var mouseEvents = awaitMouseScrolls(routed);
            awaitLine(backend, "wheel-line-0",
                "oldest message visible after wheel-up");
            awaitNoLine(backend, "wheel-line-39",
                "newest message scrolled out of view after wheel-up");

            // SGR mouse wheel-down returns to the bottom.
            for (int i = 0; i < 6; i++) {
                backend.feed("\u001b[<65;50;15M");
            }
            awaitLine(backend, "wheel-line-39",
                "wheel-down returns to the newest message");

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

    private static void awaitLine(FakeBackend backend, String text,
                                  String description) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (backend.lastDrawLines().stream()
                    .anyMatch(l -> l.contains(text))) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(backend.lastDrawLines().stream()
                .anyMatch(l -> l.contains(text)))
            .as(description).isTrue();
    }

    private static void awaitNoLine(FakeBackend backend, String text,
                                    String description) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (backend.lastDrawLines().stream()
                    .noneMatch(l -> l.contains(text))) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(backend.lastDrawLines().stream()
                .noneMatch(l -> l.contains(text)))
            .as(description).isTrue();
    }

    private static List<dev.tamboui.tui.event.MouseEvent> awaitMouseScrolls(
            List<Event> routed) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            var found = routed.stream()
                .filter(dev.tamboui.tui.event.MouseEvent.class::isInstance)
                .map(dev.tamboui.tui.event.MouseEvent.class::cast)
                .filter(me -> me.kind()
                    == dev.tamboui.tui.event.MouseEventKind.SCROLL_UP)
                .toList();
            if (!found.isEmpty()) {
                return found;
            }
            Thread.sleep(50);
        }
        var classes = routed.stream()
            .map(e -> e.getClass().getName()).toList();
        assertThat(classes)
            .as("mouse wheel-up events reach the router")
            .contains("dev.tamboui.tui.event.MouseEvent");
        return List.of();
    }
}
