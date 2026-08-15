package com.pijava.tui.component;

import java.util.List;

import com.pijava.ai.message.ContentBlock;
import dev.tamboui.toolkit.elements.ListElement;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ChatPanelTest {

    @Test
    void rendersMessageList() {
        var panel = new ChatPanel();
        panel.append(new ChatMessage.User("hi"));
        panel.append(new ChatMessage.Assistant(List.of(
            new ContentBlock.TextContent("hello"))));
        panel.append(new ChatMessage.System("info"));

        assertThat(panel.render()).isInstanceOf(ListElement.class);
    }

    @Test
    void renderReusesSameScrollableElement() {
        var panel = new ChatPanel();
        var first = panel.render();
        panel.append(new ChatMessage.User("hi"));
        assertThat(panel.render()).isSameAs(first);
    }
}
