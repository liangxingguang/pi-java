package com.pijava.ai.stream;

import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StreamPartialBuilderTest {

    @Test
    void interleavedTextAndToolBlocksDoNotOverwrite() {
        var builder = new StreamPartialBuilder();
        builder.emitStart();

        builder.emitTextStart();
        builder.emitTextDelta("告诉");
        builder.emitToolCallStart();
        builder.emitToolCallDelta("id1", "{\"path\":\"hello.py\"");
        builder.emitTextDelta("我你想实现的功能");
        builder.emitToolCallDelta("id1", ",\"content\":\"print(\\\"hello\\\")\"}");
        builder.emitTextEnd();
        builder.emitToolCallEnd("id1", "write");

        var blocks = builder.snapshot().content();
        assertThat(blocks).hasSize(2);

        assertThat(blocks.get(0))
            .isInstanceOf(ContentBlock.TextContent.class);
        assertThat(((ContentBlock.TextContent) blocks.get(0)).text())
            .isEqualTo("告诉我你想实现的功能");

        assertThat(blocks.get(1))
            .isInstanceOf(ContentBlock.ToolUseContent.class);
        assertThat(((ContentBlock.ToolUseContent) blocks.get(1)).arguments())
            .containsEntry("path", "hello.py")
            .containsEntry("content", "print(\"hello\")");
    }
}
