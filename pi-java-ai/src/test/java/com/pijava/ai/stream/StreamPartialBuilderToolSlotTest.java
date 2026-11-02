package com.pijava.ai.stream;

import java.util.List;
import java.util.Map;

import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Provider-indexed tool slots must isolate raw text, identity, namespace and block index. */
class StreamPartialBuilderToolSlotTest {

    @Test
    void interleavedSlotsFinishWithTheirOwnArgumentsAndIdentity() {
        StreamPartialBuilder builder = new StreamPartialBuilder();
        ToolCallSlotKey a = ToolCallSlotKey.indexed(10);
        ToolCallSlotKey b = ToolCallSlotKey.indexed(11);
        var aStart = builder.emitToolCallStart(a, "id-a", "tool-a", "ns-a");
        var bStart = builder.emitToolCallStart(b, "id-b", "tool-b", "ns-b");
        builder.emitToolCallDelta(a, "id-a", "{\"a\":1}");
        builder.emitToolCallDelta(b, "id-b", "{\"b\":2}");

        StreamEvent.ToolCallEnd bEnd = builder.emitToolCallEnd(b, "id-b", "tool-b", null, "ns-b-final");
        StreamEvent.ToolCallEnd aEnd = builder.emitToolCallEnd(a, "id-a", "tool-a", null, null);

        assertThat(aStart.contentIndex()).isZero();
        assertThat(bStart.contentIndex()).isEqualTo(1);
        assertThat(aEnd.contentIndex()).isZero();
        assertThat(aEnd.arguments()).isEqualTo(Map.of("a", 1));
        assertThat(aEnd.namespace()).isEqualTo("ns-a");
        assertThat(bEnd.contentIndex()).isEqualTo(1);
        assertThat(bEnd.arguments()).isEqualTo(Map.of("b", 2));
        assertThat(bEnd.namespace()).isEqualTo("ns-b-final");
        assertThat(builder.snapshot().content()).containsExactly(
                new ContentBlock.ToolUseContent("id-a", "tool-a", Map.of("a", 1),
                        null, "ns-a", null),
                new ContentBlock.ToolUseContent("id-b", "tool-b", Map.of("b", 2),
                        null, "ns-b-final", null));
    }

    @Test
    void seedAndReplacePublishOnlyTheMissingTailOnce() {
        StreamPartialBuilder builder = new StreamPartialBuilder();
        ToolCallSlotKey key = ToolCallSlotKey.indexed(3);
        builder.emitToolCallStart(key, "id", "tool", null);
        builder.seedToolArguments(key, "{\"a\":\"x");
        assertThat(builder.snapshot().content().getFirst())
                .isEqualTo(new ContentBlock.ToolUseContent("id", "tool", Map.of("a", "x")));

        var tail = builder.replaceToolArguments(key, "{\"a\":\"xyz\"}");
        assertThat(tail).isPresent();
        assertThat(tail.orElseThrow().jsonDelta()).isEqualTo("yz\"}");
        assertThat(builder.replaceToolArguments(key, "{\"a\":\"xyz\"}")).isEmpty();
        assertThat(builder.replaceToolArguments(key, "{\"b\":2}")).isEmpty();
        StreamEvent.ToolCallEnd end = builder.emitToolCallEnd(key, "id", "tool");
        assertThat(end.arguments()).isEqualTo(Map.of("b", 2));
        assertThat(end.argumentsError()).isNull();
    }

    @Test
    void openSlotsFinishInStartOrderWithEmptyObjectArguments() {
        StreamPartialBuilder builder = new StreamPartialBuilder();
        ToolCallSlotKey a = ToolCallSlotKey.indexed(1);
        ToolCallSlotKey b = ToolCallSlotKey.indexed(2);
        builder.emitToolCallStart(a, "id-a", "tool-a", null);
        builder.emitToolCallStart(b, "id-b", "tool-b", null);

        List<StreamEvent.ToolCallEnd> ends = builder.emitOpenToolCallEnds();

        assertThat(ends).extracting(StreamEvent.ToolCallEnd::id).containsExactly("id-a", "id-b");
        assertThat(ends).allSatisfy(end -> {
            assertThat(end.arguments()).isEqualTo(Map.of());
            assertThat(end.argumentsError()).isNull();
        });
        assertThat(builder.emitOpenToolCallEnds()).isEmpty();
    }

    @Test
    void malformedFinalArgumentsBecomeErrorNotRawFallback() {
        StreamPartialBuilder builder = new StreamPartialBuilder();
        ToolCallSlotKey key = ToolCallSlotKey.indexed(0);
        builder.emitToolCallStart(key, "id", "tool", null);
        builder.emitToolCallDelta(key, "id", "{\"valid\":true} broken");
        StreamEvent.ToolCallEnd end = builder.emitToolCallEnd(key, "id", "tool");

        assertThat(end.arguments()).isEqualTo(Map.of());
        assertThat(end.argumentsError()).contains("Received arguments:\n{\"valid\":true} broken");
        assertThat(end.arguments()).doesNotContainKey("_raw");
        assertThat(end.partial().content().getFirst())
                .isInstanceOfSatisfying(ContentBlock.ToolUseContent.class,
                        block -> assertThat(block.argumentsError()).isEqualTo(end.argumentsError()));
    }
}
