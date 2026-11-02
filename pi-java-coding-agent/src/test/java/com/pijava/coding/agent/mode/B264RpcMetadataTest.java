package com.pijava.coding.agent.mode;

import java.util.List;
import java.util.Map;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSessionEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Generic RPC projections expose new fields while partial remains stripped. */
class B264RpcMetadataTest {

    @Test
    void messageNodeCarriesResponseId() {
        Message.AssistantMessage message = new Message.AssistantMessage(List.of(),
                StopReason.STOP, null, "api", "provider", "model", null, null, null,
                null, null, null, null, null, null, "response-rpc");
        assertThat(JsonEventMapper.messageNode(message).get("responseId").asText())
                .isEqualTo("response-rpc");
        assertThat(JsonEventMapper.messageNode(new Message.AssistantMessage(List.of()))
                .has("responseId")).isFalse();
    }

    @Test
    void toolCallEndCarriesArgumentsErrorWithoutPartial() {
        StreamEvent.ToolCallEnd end = new StreamEvent.ToolCallEnd(0, "call-1", "read",
                Map.of(), "ns", "bad-json", AssistantMessage.empty());
        var node = JsonEventMapper.toWire(new AgentSessionEvent.MessageUpdate(end))
                .get("assistantMessageEvent");
        assertThat(node.get("argumentsError").asText()).isEqualTo("bad-json");
        assertThat(node.has("partial")).isFalse();
    }
}
