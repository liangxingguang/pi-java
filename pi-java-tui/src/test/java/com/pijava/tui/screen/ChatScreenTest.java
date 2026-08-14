package com.pijava.tui.screen;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.tui.component.ChatMessage;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 §16: stream events → draft → commit, and assistant entry dedup.
 */
class ChatScreenTest {

    @Test
    void textDeltasDraftAndTextEndCommits() {
        var screen = new ChatScreen();

        screen.onStreamEvent(new StreamEvent.TextStart(0, AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.TextDelta(
            0, "hello ", AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.TextDelta(
            0, "world", AssistantMessage.empty()));
        var draft = screen.render();
        assertThat(draft).isNotNull();

        screen.onStreamEvent(new StreamEvent.TextEnd(
            0, "hello world", AssistantMessage.empty()));
        assertThat(screen.lastMessage()).isInstanceOf(ChatMessage.Assistant.class);
    }

    @Test
    void assistantEntryIsDeduplicatedAfterStreamCommit() {
        var screen = new ChatScreen();
        screen.onStreamEvent(new StreamEvent.Start(AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.TextEnd(
            0, "text", AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.StreamDone(
            "stop", null, AssistantMessage.empty()));
        var countBefore = screen.messageCount();

        var entry = new Entry.Message(
            Entry.newHeader(1, ""), "assistant",
            List.of(new ContentBlock.TextContent("text")));
        screen.onEntry(entry);

        assertThat(screen.messageCount()).isEqualTo(countBefore);
    }

    @Test
    void userEntryIsAppended() {
        var screen = new ChatScreen();
        var entry = new Entry.Message(
            Entry.newHeader(0, ""), "user",
            List.of(new ContentBlock.TextContent("hi")));

        screen.onEntry(entry);

        assertThat(screen.lastMessage()).isInstanceOf(ChatMessage.User.class);
    }

    @Test
    void nonMatchingAssistantEntryIsAppendedAfterStreamCommit() {
        var screen = new ChatScreen();
        screen.onStreamEvent(new StreamEvent.Start(AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.TextEnd(
            0, "streamed text", AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.StreamDone(
            "stop", null, AssistantMessage.empty()));
        var countAfterStream = screen.messageCount();

        screen.onEntry(new Entry.Message(
            Entry.newHeader(2, ""), "assistant",
            List.of(new ContentBlock.TextContent("later text"))));

        assertThat(screen.messageCount()).isEqualTo(countAfterStream + 1);
        assertThat(screen.lastMessage()).isInstanceOf(ChatMessage.Assistant.class);
    }

    @Test
    void multiBlockAssistantEntryIsDeduplicated() {
        var screen = new ChatScreen();
        screen.onStreamEvent(new StreamEvent.Start(AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.TextEnd(
            0, "first ", AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.TextEnd(
            1, "second", AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.StreamDone(
            "stop", null, AssistantMessage.empty()));
        var countAfterStream = screen.messageCount();

        screen.onEntry(new Entry.Message(
            Entry.newHeader(1, ""), "assistant",
            List.of(
                new ContentBlock.TextContent("first "),
                new ContentBlock.TextContent("second"))));

        assertThat(screen.messageCount()).isEqualTo(countAfterStream);
    }

    @Test
    void multiTurnAssistantEntriesAreDeduplicatedInOrder() {
        var screen = new ChatScreen();
        screen.onStreamEvent(new StreamEvent.Start(AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.TextEnd(
            0, "first", AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.StreamDone(
            "stop", null, AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.Start(AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.TextEnd(
            0, "second", AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.StreamDone(
            "stop", null, AssistantMessage.empty()));
        var countAfterStream = screen.messageCount();

        screen.onEntry(new Entry.Message(
            Entry.newHeader(1, ""), "assistant",
            List.of(new ContentBlock.TextContent("first"))));
        screen.onEntry(new Entry.Message(
            Entry.newHeader(3, ""), "assistant",
            List.of(new ContentBlock.TextContent("second"))));

        assertThat(screen.messageCount()).isEqualTo(countAfterStream);
    }

    @Test
    void streamErrorBecomesErrorBubble() {
        var screen = new ChatScreen();
        screen.onStreamEvent(new StreamEvent.StreamError(
            "error", new IllegalStateException("boom"), AssistantMessage.empty()));

        assertThat(screen.lastMessage()).isInstanceOf(ChatMessage.Error.class);
    }
}
