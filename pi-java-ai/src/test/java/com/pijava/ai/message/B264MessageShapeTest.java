package com.pijava.ai.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ImageResult;
import com.pijava.ai.api.ImageStopReason;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** B264 local error/response metadata must survive every record rebuild. */
class B264MessageShapeTest {

    @Test
    void streamingAssistantWithersPreserveResponseId() {
        AssistantMessage source = streamingMessage("response-1");
        List<AssistantMessage> copies = List.of(
                source.withId("new-id"),
                source.withContent(List.of(new ContentBlock.TextContent("x"))),
                source.withUsage(null),
                source.withStopReason(StopReason.ERROR),
                source.withIdentity("other-api", "other-provider", "other-model", Instant.now()),
                source.withProviderThinkingLevel("high"),
                source.withResponseModel("other-served"),
                source.withDiagnostics(null),
                source.withErrorMessage("other-error"),
                source.withRawStopReason("other-raw"),
                source.withDurationMs(99L));

        assertThat(copies).allSatisfy(copy -> assertThat(copy.responseId()).isEqualTo("response-1"));
        assertThat(AssistantMessage.empty().responseId()).isNull();
    }

    @Test
    void domainProjectionAndWithersPreserveResponseId() {
        Message.AssistantMessage projected = Message.AssistantMessage.fromPartial(streamingMessage("response-2"));
        assertThat(projected.responseId()).isEqualTo("response-2");
        assertThat(projected.withStopReason(StopReason.ABORTED).responseId()).isEqualTo("response-2");
        assertThat(projected.withThinkingLevel("max").responseId()).isEqualTo("response-2");

        Message.AssistantMessage legacy = new Message.AssistantMessage(List.of());
        assertThat(legacy.responseId()).isNull();
    }

    @Test
    void toolUseAndToolCallEndCarryErrorAndNestedNulls() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("value", null);
        List<Object> nested = new ArrayList<>();
        nested.add(null);
        arguments.put("nested", nested);
        ContentBlock.ToolUseContent block = new ContentBlock.ToolUseContent(
                "call-1", "write", arguments, "signature", "namespace", "bad arguments");
        assertThat(block.argumentsError()).isEqualTo("bad arguments");
        assertThat(block.arguments().containsKey("value")).isTrue();
        assertThat(block.arguments().get("value")).isNull();
        assertThat((List<?>) block.arguments().get("nested")).hasSize(1);
        assertThat(((List<?>) block.arguments().get("nested")).getFirst()).isNull();
        arguments.put("value", "changed");
        assertThat(block.arguments().get("value")).isNull();

        AssistantMessage partial = AssistantMessage.empty();
        StreamEvent.ToolCallEnd end = new StreamEvent.ToolCallEnd(
                0, "call-1", "write", block.arguments(), "namespace", "bad arguments", partial);
        AssistantMessage replacement = partial.withId("replacement");
        StreamEvent.ToolCallEnd rebuilt = (StreamEvent.ToolCallEnd) StreamEvent.withPartial(end, replacement);
        assertThat(rebuilt.argumentsError()).isEqualTo("bad arguments");
        assertThat(rebuilt.partial()).isSameAs(replacement);
        assertThat(rebuilt.arguments()).containsKey("value");
    }

    @Test
    void imageResultCarriesOptionalResponseId() {
        ImageResult result = new ImageResult("openrouter-images", "flux", List.of(),
                ImageStopReason.STOP, null, 123L, "image-response-1");
        assertThat(result.responseId()).isEqualTo("image-response-1");
        ImageResult legacy = new ImageResult("openrouter-images", "flux", List.of(),
                ImageStopReason.STOP, null, 123L);
        assertThat(legacy.responseId()).isNull();
    }

    private static AssistantMessage streamingMessage(String responseId) {
        return new AssistantMessage("message-1", List.of(), null, StopReason.STOP,
                "api", "provider", "model", "served-model", "provider-effort",
                List.of(), Instant.EPOCH, "error", "raw-stop", 42L, responseId);
    }
}
