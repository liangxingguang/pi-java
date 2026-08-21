package com.pijava.tui.util;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.message.ContentBlock;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.MetaKind;

import dev.tamboui.text.Line;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scrollback printer semantics: committed messages append; the streaming
 * draft is intentionally NOT printed (in-place rewrites corrupt Windows
 * ConPTY output), so each response lands exactly once at TextEnd.
 */
class ScrollbackTranscriptTest {

    /** Records println calls (no in-place rewrites are expected). */
    private static final class Sink implements ScrollbackTranscript.Sink {
        final List<Line> printed = new ArrayList<>();

        @Override
        public void println(Line line) {
            printed.add(line);
        }

        @Override
        public boolean replaceLastBlock(int lineCount, List<Line> block) {
            throw new AssertionError("draft rewrites must not be used");
        }

        List<String> text() {
            return printed.stream().map(Line::rawContent).toList();
        }
    }

    private static ChatMessage.Assistant assistant(String text) {
        return new ChatMessage.Assistant(List.of(new ContentBlock.TextContent(text)));
    }

    private static final int WIDTH = 80;

    @Test
    void committedMessagesAppendInOrder() {
        var sink = new Sink();
        var transcript = new ScrollbackTranscript(sink);
        var messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage.User("hello"));
        messages.add(new ChatMessage.System("done", MetaKind.GENERIC));

        transcript.sync(messages, null, WIDTH);

        assertThat(sink.text()).containsExactly("› hello", "done");
        assertThat(transcript.printedMessages()).isEqualTo(2);
    }

    @Test
    void streamingDraftIsNotPrintedAndCommitsOnce() {
        var sink = new Sink();
        var transcript = new ScrollbackTranscript(sink);
        var messages = new ArrayList<ChatMessage>();

        transcript.sync(messages, assistant("Hel"), WIDTH);
        assertThat(sink.text()).isEmpty();

        transcript.sync(messages, assistant("Hello"), WIDTH);
        assertThat(sink.text()).isEmpty();

        // TextEnd commits the message; it prints exactly once.
        messages.add(assistant("Hello"));
        transcript.sync(messages, null, WIDTH);
        assertThat(sink.text()).containsExactly("• Hello");
        assertThat(transcript.printedMessages()).isEqualTo(1);
        assertThat(transcript.draftVisible()).isTrue();
    }

    @Test
    void newRoundCommitsItsOwnResponse() {
        var sink = new Sink();
        var transcript = new ScrollbackTranscript(sink);
        var messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage.User("q1"));
        messages.add(assistant("a1"));

        transcript.sync(messages, null, WIDTH);
        assertThat(sink.text()).containsExactly("› q1", "• a1");

        // Second round streams without printing, then commits once.
        messages.add(new ChatMessage.User("q2"));
        transcript.sync(messages, null, WIDTH);
        assertThat(sink.text()).containsExactly("› q1", "• a1", "› q2");

        transcript.sync(messages, assistant("a2"), WIDTH);
        assertThat(sink.text()).containsExactly("› q1", "• a1", "› q2");

        messages.add(assistant("a2"));
        transcript.sync(messages, null, WIDTH);
        assertThat(sink.text()).containsExactly("› q1", "• a1", "› q2", "• a2");
        assertThat(transcript.printedMessages()).isEqualTo(4);
    }
}
