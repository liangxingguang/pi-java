package com.pijava.tui.component;

import java.util.List;

import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ChatPanelTest {

    @Test
    void rendersChatViewportElement() {
        var panel = new ChatPanel();
        panel.append(new ChatMessage.User("hi"));
        panel.append(new ChatMessage.Assistant(List.of(
            new ContentBlock.TextContent("hello"))));
        panel.append(new ChatMessage.System("info"));

        assertThat(panel.render()).isInstanceOf(ChatViewportElement.class);
    }

    @Test
    void renderReusesSameScrollableElement() {
        var panel = new ChatPanel();
        var first = panel.render();
        panel.append(new ChatMessage.User("hi"));
        assertThat(panel.render()).isSameAs(first);
    }

    @Test
    void setDraftJoinsViewportAndClearResets() {
        var panel = new ChatPanel();
        panel.append(new ChatMessage.User("hi"));
        panel.setDraft(new ChatMessage.Assistant(List.of(
            new ContentBlock.TextContent("draft"))));
        assertThat(panel.size()).isEqualTo(1);

        panel.clear();
        assertThat(panel.size()).isZero();
        assertThat(panel.last()).isNull();
    }

    @Test
    void lastReturnsMostRecentCommittedMessage() {
        var panel = new ChatPanel();
        panel.append(new ChatMessage.User("first"));
        panel.append(new ChatMessage.User("second"));
        assertThat(panel.last()).isEqualTo(new ChatMessage.User("second"));
    }
}
