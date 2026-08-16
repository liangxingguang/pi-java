package com.pijava.tui.screen;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
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
        var countBefore = screen.messageCount();

        var entry = new Entry.Message(
            "e-0", 0, null, null,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent("text"))), null);
        screen.onEntry(entry);

        assertThat(screen.messageCount()).isEqualTo(countBefore);
    }

    @Test
    void transcriptWithExtraBlocksDoesNotDuplicateStream() {
        var screen = new ChatScreen();
        screen.onStreamEvent(new StreamEvent.Start(AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.TextEnd(
            0, "正常回答", AssistantMessage.empty()));
        assertThat(screen.messageCount()).isEqualTo(1);

        // The transcript snapshot may carry extra/reordered blocks; it must
        // not render a second (duplicated) bubble.
        var entry = new Entry.Message(
            "e-1", 0, null, null,
            new Message.AssistantMessage(List.of(
                new ContentBlock.TextContent("乱序片段"),
                new ContentBlock.TextContent("正常回答"))), null);
        screen.onEntry(entry);

        assertThat(screen.messageCount()).isEqualTo(1);
        assertThat(screen.lastMessage()).isInstanceOf(ChatMessage.Assistant.class);
    }

    @Test
    void userEntryIsAppended() {
        var screen = new ChatScreen();
        var entry = new Entry.Message(
            "e-2", 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))), null);

        screen.onEntry(entry);

        assertThat(screen.lastMessage()).isInstanceOf(ChatMessage.User.class);
    }

    @Test
    void nonMatchingAssistantEntryIsSkippedAfterStreamCommit() {
        var screen = new ChatScreen();
        screen.onStreamEvent(new StreamEvent.Start(AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.TextEnd(
            0, "streamed text", AssistantMessage.empty()));
        var countAfterStream = screen.messageCount();

        // The transcript may contain a second/extra assistant snapshot with
        // different (even reordered) text; it was already shown via the
        // streaming path and must not be rendered again.
        screen.onEntry(new Entry.Message(
            "e-3", 0, null, null,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent("reordered text"))), null));

        assertThat(screen.messageCount()).isEqualTo(countAfterStream);
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
            "e-4", 0, null, null,
            new Message.AssistantMessage(List.of(
                new ContentBlock.TextContent("first "),
                new ContentBlock.TextContent("second"))), null));

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
            "e-5", 0, null, null,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent("first"))), null));
        screen.onEntry(new Entry.Message(
            "e-6", 0, null, null,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent("second"))), null));

        assertThat(screen.messageCount()).isEqualTo(countAfterStream);
    }

    @Test
    void streamErrorBecomesErrorBubble() {
        var screen = new ChatScreen();
        screen.onStreamEvent(new StreamEvent.StreamError(
            "error", new IllegalStateException("boom"), AssistantMessage.empty()));

        assertThat(screen.lastMessage()).isInstanceOf(ChatMessage.Error.class);
    }

    @Test
    void toolCallRunAppendsTurnSeparatorOnFinish() {
        var screen = new ChatScreen();
        screen.onStreamEvent(new StreamEvent.ToolCallStart(0, AssistantMessage.empty()));
        screen.onStreamEvent(new StreamEvent.ToolCallEnd(
            0, "id", "read", java.util.Map.of(), AssistantMessage.empty()));
        var count = screen.messageCount();

        screen.finishRun(65_000_000_000L);

        assertThat(screen.messageCount()).isEqualTo(count + 1);
        assertThat(screen.lastMessage()).isInstanceOf(ChatMessage.TurnSeparator.class);
    }

    @Test
    void conversationalRunWithoutToolsAddsNoSeparator() {
        var screen = new ChatScreen();
        screen.onStreamEvent(new StreamEvent.TextEnd(
            0, "hello", AssistantMessage.empty()));
        var count = screen.messageCount();

        screen.finishRun(30_000_000_000L);

        assertThat(screen.messageCount()).isEqualTo(count);
    }

    @Test
    void showWelcomeAppendsSystemBanner() {
        var screen = new ChatScreen();

        screen.showWelcome("pi-java card");

        assertThat(screen.messageCount()).isEqualTo(1);
        assertThat(screen.lastMessage()).isInstanceOf(ChatMessage.System.class);
    }
    @Test
    void slashInputActivatesCompleterAndCompletionReplacesText() {
        var screen = new ChatScreen();
        screen.setSlashCommands(List.of(
            new com.pijava.tui.component.SlashCompleter.CommandItem(
                "model", "", "Switch model")));

        screen.onKeyEvent(dev.tamboui.tui.event.KeyEvent.ofChar('/'));
        screen.onKeyEvent(dev.tamboui.tui.event.KeyEvent.ofChar('m'));

        assertThat(screen.completerActive()).isTrue();
        assertThat(screen.applyCompletion()).isTrue();
        assertThat(screen.inputText()).isEqualTo("/model");
        assertThat(screen.completerActive()).isFalse();
    }
    @Test
    void tabKeyCompletesHighlightedCommand() {
        var screen = new ChatScreen();
        screen.setSlashCommands(List.of(
            new com.pijava.tui.component.SlashCompleter.CommandItem(
                "model", "", "Switch model")));

        screen.onKeyEvent(dev.tamboui.tui.event.KeyEvent.ofChar('/'));
        screen.onKeyEvent(dev.tamboui.tui.event.KeyEvent.ofChar('m'));
        assertThat(screen.completerActive()).isTrue();

        screen.onKeyEvent(dev.tamboui.tui.event.KeyEvent.ofKey(
            dev.tamboui.tui.event.KeyCode.TAB,
            dev.tamboui.tui.bindings.BindingSets.defaults()));

        assertThat(screen.inputText()).isEqualTo("/model");
        assertThat(screen.completerActive()).isFalse();
    }
}
