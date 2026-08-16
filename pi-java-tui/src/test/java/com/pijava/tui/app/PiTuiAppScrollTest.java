package com.pijava.tui.app;

import java.time.Duration;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.util.ScrollConfig;
import com.pijava.tui.util.TuiEventDispatcher;

import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.tui.TuiConfig;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 alignment design §7.2: real-terminal input path with a fake
 * backend — SGR mouse scroll sequences and empty-input keyboard navigation
 * drive the row-level chat viewport through the ScrollInputNormalizer.
 */
class PiTuiAppScrollTest {

    private static final String SCROLL_UP = "\u001b[<64;5;5M";
    private static final String SCROLL_DOWN = "\u001b[<65;5;5M";

    private static ScrollConfig wheelConfig() {
        return new ScrollConfig("wheel", 3, 3, 1, 30, 3, false, 12, 200, 100);
    }

    private static String lines(int count) {
        var out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append("line ").append(i);
        }
        return out.toString();
    }

    private static final class Harness implements AutoCloseable {
        final FakeBackend backend;
        final ChatScreen chatScreen;
        final TuiEventDispatcher dispatcher;
        final java.util.List<dev.tamboui.tui.event.Event> routed;
        private final ToolkitRunner runner;
        private final AgentSession session;
        private final Thread thread;

        Harness(FakeBackend backend, ChatScreen chatScreen,
                TuiEventDispatcher dispatcher, ToolkitRunner runner,
                AgentSession session, Thread thread,
                java.util.List<dev.tamboui.tui.event.Event> routed) {
            this.backend = backend;
            this.chatScreen = chatScreen;
            this.dispatcher = dispatcher;
            this.runner = runner;
            this.session = session;
            this.thread = thread;
            this.routed = routed;
        }

        @Override
        public void close() throws Exception {
            runner.quit();
            thread.join(5000);
            runner.close();
            session.close();
        }
    }

    private static Harness start(ScrollConfig config, java.util.List<dev.tamboui.tui.event.Event> harnessRouted) throws Exception {
        var backend = new FakeBackend();
        var routed = harnessRouted != null ? harnessRouted
            : new java.util.concurrent.CopyOnWriteArrayList<dev.tamboui.tui.event.Event>();
        var runner = ToolkitRunner.create(TuiConfig.builder()
            .backend(backend)
            .tickRate(Duration.ofMillis(50))
            .mouseCapture(true)
            .build());
        var session = AgentSession.create(ArgsParser.parse(new String[] {}));
        var chatScreen = new ChatScreen();
        var mode = new InteractiveMode(session);
        var dispatcher = new TuiEventDispatcher();
        var app = new PiTuiApp(mode, chatScreen,
            new KeybindingsManager(), dispatcher, config);
        mode.setObservers(
            entry -> dispatcher.dispatch(() -> chatScreen.onEntry(entry)),
            event -> dispatcher.dispatch(() -> chatScreen.onStreamEvent(event)));
        runner.eventRouter().addGlobalHandler(event -> {
            routed.add(event);
            return dev.tamboui.toolkit.event.EventResult.UNHANDLED;
        });
        app.start(runner);

        var thread = Thread.startVirtualThread(() -> {
            try {
                runner.run(app::root);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(300);
        return new Harness(backend, chatScreen, dispatcher, runner, session, thread, routed);
    }

    @Test
    void wheelScrollEventsDriveViewportAndDraftStaysInside() throws Exception {
        try (var harness = start(wheelConfig(), null)) {
            harness.chatScreen.appendSystemText(lines(40));
            Thread.sleep(300);
            int bottom = harness.chatScreen.scrollOffset();
            assertThat(bottom).isGreaterThan(0);

            // Stream a draft while pinned to the bottom: it renders inside the
            // viewport and the offset follows to the new bottom (sticky).
            for (int i = 0; i < 12; i++) {
                harness.dispatcher.dispatch(() -> harness.chatScreen.onStreamEvent(
                    new StreamEvent.TextDelta(0, "x", AssistantMessage.empty())));
            }
            Thread.sleep(250);
            assertThat(harness.backend.hasLineContaining("xxx")).isTrue();
            int bottomWithDraft = harness.chatScreen.scrollOffset();
            assertThat(bottomWithDraft).isGreaterThanOrEqualTo(bottom);

            // Three raw SGR scroll-up events = one normalized wheel notch (3 rows).
            harness.backend.feed(SCROLL_UP.repeat(3));
            awaitOffset(harness, bottomWithDraft - 3);

            // Scrolling back down returns to the bottom and resumes follow.
            harness.backend.feed(SCROLL_DOWN.repeat(3));
            awaitOffset(harness, bottomWithDraft);
        }
    }

    @Test
    void emptyInputNavigationKeysScrollViewport() throws Exception {
        try (var harness = start(wheelConfig(), null)) {
            harness.chatScreen.appendSystemText(lines(40));
            Thread.sleep(300);
            int bottom = harness.chatScreen.scrollOffset();

            harness.backend.feed("\u001b[A"); // Up
            awaitOffset(harness, bottom - 1);

            harness.backend.feed("\u001b[B"); // Down
            awaitOffset(harness, bottom);

            harness.backend.feed("\u001b[H"); // Home
            awaitOffset(harness, 0);

            harness.backend.feed("\u001b[F"); // End
            awaitOffset(harness, bottom);

            harness.backend.feed("\u001b[5~"); // PageUp
            int visible = harness.chatScreen.visibleRows();
            awaitOffset(harness, Math.max(0, bottom - visible));

            harness.backend.feed("\u001b[6~"); // PageDown
            awaitOffset(harness, bottom);
        }
    }

    private static void awaitOffset(Harness harness, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (harness.chatScreen.scrollOffset() == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(harness.chatScreen.scrollOffset())
            .as("routed=" + harness.routed + " expected=" + expected)
            .isEqualTo(expected);
    }

    @Test
    void newContentFollowsWhilePinnedToBottom() throws Exception {
        try (var harness = start(wheelConfig(), null)) {
            harness.chatScreen.appendSystemText(lines(40));
            Thread.sleep(300);
            int bottom = harness.chatScreen.scrollOffset();

            harness.chatScreen.appendSystemText("new content");
            Thread.sleep(200);
            assertThat(harness.chatScreen.scrollOffset()).isGreaterThan(bottom);
        }
    }
}
