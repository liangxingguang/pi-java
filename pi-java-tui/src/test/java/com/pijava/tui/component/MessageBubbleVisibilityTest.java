package com.pijava.tui.component;

import java.util.List;

import com.pijava.ai.message.ContentBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageBubbleVisibilityTest {

    @AfterEach
    void restoreVisibility() {
        MessageBubbleVisibility.install(() -> false);
    }

    @Test
    void hiddenThinkingIsSkippedWithoutDroppingAssistantText() {
        MessageBubbleVisibility.install(() -> true);
        var message = new ChatMessage.Assistant(List.of(
            new ContentBlock.ThinkingContent("secret reasoning"),
            new ContentBlock.TextContent("visible answer")));

        var lines = MessageBubble.lines(message);

        assertThat(lines).extracting(LogicalLine::markup)
            .noneMatch(text -> text.contains("secret reasoning"))
            .anyMatch(text -> text.contains("visible answer"));
    }
}
