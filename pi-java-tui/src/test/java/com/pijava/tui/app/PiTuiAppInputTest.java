package com.pijava.tui.app;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.component.EditorComponent;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.SettingsScreen;
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
        var focusReset = new FocusReset();
        var runner = ToolkitRunner.create(
            TuiConfig.builder().backend(backend)
                .postRenderProcessor(focusReset).build());
        focusReset.bind(runner.focusManager());
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
        var focusReset = new FocusReset();
        var runner = ToolkitRunner.create(
            TuiConfig.builder().backend(backend)
                .postRenderProcessor(focusReset).build());
        focusReset.bind(runner.focusManager());
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

    private static int selectedField(Object overlay) throws Exception {
        var field = SettingsScreen.class.getDeclaredField("selected");
        field.setAccessible(true);
        return field.getInt(overlay);
    }
}
