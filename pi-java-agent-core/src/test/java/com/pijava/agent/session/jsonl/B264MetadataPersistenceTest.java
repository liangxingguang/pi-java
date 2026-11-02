package com.pijava.agent.session.jsonl;

import java.util.List;
import java.util.Map;

import com.pijava.agent.session.SessionJson;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** B264 metadata persists without changing legacy absent-null behavior. */
class B264MetadataPersistenceTest {

    @Test
    void argumentsErrorRoundTripsAndIsOmittedWhenAbsent() {
        ContentBlock.ToolUseContent original = new ContentBlock.ToolUseContent(
                "call-1", "write", Map.of(), "signature", "ns", "bad-json");
        var node = SessionJson.blockNode(original);
        assertThat(node.get("argumentsError").asText()).isEqualTo("bad-json");
        ContentBlock.ToolUseContent decoded = (ContentBlock.ToolUseContent) MessageJsonCodec.decodeBlock(node);
        assertThat(decoded).isEqualTo(original);

        var legacy = new ContentBlock.ToolUseContent("call-2", "read", Map.of());
        assertThat(SessionJson.blockNode(legacy).has("argumentsError")).isFalse();
        assertThat(((ContentBlock.ToolUseContent) MessageJsonCodec.decodeBlock(
                SessionJson.blockNode(legacy))).argumentsError()).isNull();
    }

    @Test
    void responseIdRoundTripsAndIsOmittedWhenAbsent() {
        Message.AssistantMessage original = new Message.AssistantMessage(
                List.of(new ContentBlock.TextContent("hi")), StopReason.STOP, null,
                "api", "provider", "model", null, null, null, null,
                null, null, null, null, null, "response-1");
        var node = SessionJson.messageNode(original);
        assertThat(node.get("responseId").asText()).isEqualTo("response-1");
        Message.AssistantMessage decoded = (Message.AssistantMessage) MessageJsonCodec.decode(node);
        assertThat(decoded.responseId()).isEqualTo("response-1");

        Message.AssistantMessage legacy = new Message.AssistantMessage(List.of());
        assertThat(SessionJson.messageNode(legacy).has("responseId")).isFalse();
        assertThat(((Message.AssistantMessage) MessageJsonCodec.decode(
                SessionJson.messageNode(legacy))).responseId()).isNull();
    }
}
