package com.pijava.web;

import java.util.List;
import java.util.Map;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Web message and partial projections retain B264 metadata. */
class B264WebMetadataTest {

    @Test
    void domainMessageProjectsResponseIdAndArgumentsError() {
        Message.AssistantMessage message = new Message.AssistantMessage(List.of(
                new ContentBlock.ToolUseContent("call-1", "read", Map.of(),
                        null, "ns", "bad-json")), StopReason.TOOL_USE, null,
                "api", "provider", "model", null, null, null, null,
                null, null, null, null, null, "response-web");

        var node = WebWireJson.messageNode(message);
        assertThat(node.get("responseId").asText()).isEqualTo("response-web");
        assertThat(node.get("content").get(0).get("argumentsError").asText()).isEqualTo("bad-json");
    }

    @Test
    void streamingPartialProjectsResponseIdAndOmitsAbsentMetadata() {
        AssistantMessage base = AssistantMessage.empty();
        AssistantMessage partial = new AssistantMessage(base.id(), base.content(), base.usage(),
                base.stopReason(), base.api(), base.provider(), base.model(), base.responseModel(),
                base.providerThinkingLevel(), base.diagnostics(), base.timestamp(),
                base.errorMessage(), base.rawStopReason(), base.durationMs(), "response-partial");
        assertThat(WebWireJson.assistantNode(partial).get("responseId").asText())
                .isEqualTo("response-partial");
        assertThat(WebWireJson.assistantNode(base).has("responseId")).isFalse();
    }
}
