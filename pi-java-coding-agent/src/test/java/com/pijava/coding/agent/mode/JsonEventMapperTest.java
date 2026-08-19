package com.pijava.coding.agent.mode;

import java.util.List;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSessionEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-5b: JsonEventMapper — {@code message_update} 序列化结果不含 {@code partial}。
 */
class JsonEventMapperTest {

    @Test
    void messageUpdateStripsPartialSnapshot() {
        var partial = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("Hello world")));
        var event = new AgentSessionEvent.MessageUpdate(
            new StreamEvent.TextDelta(0, "world", partial));

        var node = JsonEventMapper.toWire(event);
        assertThat(node.get("type").asText()).isEqualTo("message_update");
        assertThat(node.get("assistantMessageEvent").get("type").asText())
            .isEqualTo("text_delta");
        assertThat(node.get("assistantMessageEvent").has("partial")).isFalse();
        assertThat(node.get("assistantMessageEvent").get("contentIndex").asInt())
            .isZero();
        assertThat(node.get("assistantMessageEvent").get("delta").asText())
            .isEqualTo("world");
    }

    @Test
    void agentEndCarriesMessagesAndWillRetry() {
        var event = new AgentSessionEvent.AgentEnd(List.of(), true);
        var node = JsonEventMapper.toWire(event);
        assertThat(node.get("type").asText()).isEqualTo("agent_end");
        assertThat(node.get("willRetry").asBoolean()).isTrue();
        assertThat(node.get("messages").isArray()).isTrue();
    }

    @Test
    void agentSettledHasNoExtraFields() {
        var node = JsonEventMapper.toWire(new AgentSessionEvent.AgentSettled());
        assertThat(node.get("type").asText()).isEqualTo("agent_settled");
        assertThat(node.size()).isEqualTo(1);
    }
}
