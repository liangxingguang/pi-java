package com.pijava.tui.app;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.util.InlineTuiShell;
import com.pijava.tui.util.TuiEventDispatcher;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regular (raw-scrollback) TUI mode end to end: transcript lines are appended
 * to the terminal's main buffer through the inline shell, and the streaming
 * draft is rewritten in place until TextEnd commits it without duplication.
 */
class PiTuiAppInlineTest {

    @Test
    void regularModePrintsTranscriptToScrollbackAndCommitsDraft() throws Exception {
        var backend = new FakeBackend();
        var shell = InlineTuiShell.createForTest(backend);
        var session = AgentSession.create(ArgsParser.parse(new String[] {}));
        var chatScreen = new ChatScreen();
        var mode = new InteractiveMode(session);
        var dispatcher = new TuiEventDispatcher();
        var app = new PiTuiApp(mode, chatScreen,
            new KeybindingsManager(), dispatcher);
        app.startInline(shell);

        var thread = Thread.startVirtualThread(() -> {
            try {
                shell.run(app::onInlineEvent, app::renderInline);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(200);

        // User message appended on submit → printed into the scrollback.
        dispatcher.dispatch(() -> chatScreen.appendUserText("hi there"));
        Thread.sleep(200);
        assertThat(String.join("", backend.rawWrites())).contains("hi there");

        // Streaming deltas are NOT printed into the scrollback (in-place
        // rewrites corrupt Windows consoles); the text lands at TextEnd.
        dispatcher.dispatch(() -> chatScreen.onStreamEvent(
            new StreamEvent.TextDelta(0, "Hel", AssistantMessage.empty())));
        Thread.sleep(150);
        dispatcher.dispatch(() -> chatScreen.onStreamEvent(
            new StreamEvent.TextDelta(0, "Hello", AssistantMessage.empty())));
        Thread.sleep(150);
        assertThat(String.join("", backend.rawWrites())).doesNotContain("Hello");

        // TextEnd commits the message; it prints exactly once.
        dispatcher.dispatch(() -> chatScreen.onStreamEvent(
            new StreamEvent.TextEnd(0, "Hello", AssistantMessage.empty())));
        Thread.sleep(200);
        assertThat(chatScreen.messageCount()).isEqualTo(3); // banner + user + assistant
        int helloOccurrences = countOccurrences(
            String.join("", backend.rawWrites()), "Hello");
        assertThat(helloOccurrences).isEqualTo(1);

        shell.quit();
        thread.join(3000);
        assertThat(thread.isAlive()).isFalse();
        shell.close();
        session.close();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
