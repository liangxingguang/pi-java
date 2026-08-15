package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Streaming tool-call accumulation across split chunks (DeepSeek etc. send
 * id, name and arguments in separate deltas). Regression for runs that ended
 * with only the preamble text because the tool call was never started.
 */
class ToolCallAccumulatorTest {

    private final StreamPartialBuilder builder = new StreamPartialBuilder();
    private final List<StreamEvent> emitted = new ArrayList<>();
    private final ToolCallAccumulator accumulator = new ToolCallAccumulator();

    @Test
    void idAndFunctionSplitAcrossChunksStillBuildsTheCall() {
        accumulator.update("call_1", null, null,
            emitted::add, builder); // DeepSeek: id-only first chunk
        accumulator.update(null, "write", "{\"path\":\"hello.c\"}",
            emitted::add, builder);

        accumulator.finish(emitted::add, builder);

        assertThat(accumulator.started()).isTrue();
        assertThat(emitted)
            .filteredOn(StreamEvent.ToolCallStart.class::isInstance).hasSize(1);
        assertThat(emitted)
            .filteredOn(StreamEvent.ToolCallEnd.class::isInstance).hasSize(1);
        var block = (ContentBlock.ToolUseContent) builder.snapshot().content().get(0);
        assertThat(block.id()).isEqualTo("call_1");
        assertThat(block.name()).isEqualTo("write");
        assertThat(block.arguments()).containsEntry("path", "hello.c");
    }

    @Test
    void argumentsArriveInMultipleFragments() {
        accumulator.update("call_1", "write", "{\"path\":\"hel",
            emitted::add, builder);
        accumulator.update(null, null, "lo.c\"}",
            emitted::add, builder);

        accumulator.finish(emitted::add, builder);

        var block = (ContentBlock.ToolUseContent) builder.snapshot().content().get(0);
        assertThat(block.arguments()).containsEntry("path", "hello.c");
    }

    @Test
    void noToolChunksEmitNothing() {
        accumulator.finish(emitted::add, builder);

        assertThat(accumulator.started()).isFalse();
        assertThat(emitted).isEmpty();
    }
}
