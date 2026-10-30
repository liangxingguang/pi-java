package com.pijava.tui.app;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.util.TuiEventDispatcher;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Scrolling, alternate enter encodings, and completion through the real input stack. */
class PiTuiAppInputInteractionTest {

    @TempDir
    Path sessionDir;

    @Test
    void emptyInputLetsChatListScrollHistory() throws Exception {
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
            for (int i = 0; i < 40; i++) {
                int line = i;
                dispatcher.dispatch(
                    () -> chatScreen.appendSystemText("scroll-line-" + line));
            }
            Thread.sleep(300);

            awaitLine(backend, "scroll-line-39",
                "newest message pinned to the bottom before scrolling");

            // PageUp scrolls history: the first message enters the viewport and
            // the newest scrolls out (several pages; block separators add rows).
            for (int i = 0; i < 6; i++) {
                backend.feed("\u001b[5~");
            }
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
            for (int i = 0; i < 40; i++) {
                int line = i;
                dispatcher.dispatch(
                    () -> chatScreen.appendSystemText("wheel-line-" + line));
            }
            Thread.sleep(300);

            // SGR mouse wheel-up at the chat area (row 15, 1-based). Send enough
            // raw events to reach the top even with the block-separator rows.
            for (int i = 0; i < 100; i++) {
                backend.feed("\u001b[<64;50;15M");
            }
            var mouseEvents = awaitMouseScrolls(routed);
            awaitLine(backend, "wheel-line-0",
                "oldest message visible after wheel-up");
            awaitNoLine(backend, "wheel-line-39",
                "newest message scrolled out of view after wheel-up");

            // SGR mouse wheel-down returns to the bottom.
            for (int i = 0; i < 100; i++) {
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

    @Test
    void csiUShiftEnterInsertsNewlineAndDoesNotSubmit() throws Exception {
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
            backend.feed("hi");
            // CSI-u Shift+Enter (ESC[13;2u) carries the shift modifier and
            // must insert a newline instead of submitting.
            backend.feed("\u001b[13;2u");
            Thread.sleep(200);

            assertThat(chatScreen.inputText()).isEqualTo("hi\n");

            backend.feed("\r"); // plain Enter still submits
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
    void slashCompleterFiltersCompletesAndCloses() throws Exception {
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
            var routed = new java.util.concurrent.CopyOnWriteArrayList<Event>();
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
            backend.feed("/mo");
            Thread.sleep(200);
            assertThat(chatScreen.completerActive()).isTrue();

            backend.feed("\t"); // Tab completes the highlighted command
            Thread.sleep(200);
            assertThat(routed.stream()
                .filter(dev.tamboui.tui.event.KeyEvent.class::isInstance)
                .map(dev.tamboui.tui.event.KeyEvent.class::cast)
                .anyMatch(ke -> ke.isChar('\t')))
                .as("Tab event reaches the global handler")
                .isTrue();
            assertThat(chatScreen.completerActive()).isFalse();
            assertThat(chatScreen.inputText()).startsWith("/model");

            backend.feed("\u001b"); // Esc no longer opens a panel
            Thread.sleep(200);
            assertThat(chatScreen.completerActive()).isFalse();

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
