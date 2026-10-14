package com.pijava.agent.session.jsonl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.pijava.agent.session.SessionJson;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;

import org.junit.jupiter.api.Test;

/**
 * B110（docs/13 §3.5）：会话记录上的 {@code providerThinkingLevel} 落线、
 * 回读与旧盘兼容。键位置在 {@code model} 之后（pi {@code types.ts} 声明序），
 * 缺省 null 经 SessionJson 的 NON_NULL 不出键。
 */
class ProviderThinkingLevelRoundTripTest {

    private static Message.AssistantMessage assistant(String providerThinkingLevel) {
        return new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("hi")),
            StopReason.STOP, null,
            "anthropic-messages", "anthropic", "claude-fable-5-1",
            providerThinkingLevel,
            null, null, null, null);
    }

    @Test
    void writesTheKeyWhenStamped() {
        var node = SessionJson.messageNode(assistant("high"));

        assertThat(node.get("providerThinkingLevel").asText()).isEqualTo("high");
        var names = new java.util.ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        // 键序紧跟 model（pi types.ts 的声明位置）。
        assertThat(names.indexOf("model") + 1).isEqualTo(names.indexOf("providerThinkingLevel"));
    }

    @Test
    void omitsTheKeyWhenNull() {
        var node = SessionJson.messageNode(assistant(null));

        assertThat(node.has("providerThinkingLevel")).isFalse();
    }

    @Test
    void decodesTheKey() throws Exception {
        var mapper = SessionJson.mapper();
        var decoded = (Message.AssistantMessage) MessageJsonCodec.decode(mapper.readTree(
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                + "\"api\":\"anthropic-messages\",\"provider\":\"anthropic\","
                + "\"model\":\"claude-fable-5-1\",\"providerThinkingLevel\":\"xhigh\"}"));

        assertThat(decoded.providerThinkingLevel()).isEqualTo("xhigh");
    }

    /** 批 4 之前的盘没有这个键 ⇒ null（不抛、键省略），历史会话仍可重放。 */
    @Test
    void legacySessionWithoutTheKeyDecodesToNull() throws Exception {
        var mapper = SessionJson.mapper();
        var decoded = (Message.AssistantMessage) MessageJsonCodec.decode(mapper.readTree(
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                + "\"api\":\"anthropic-messages\",\"provider\":\"anthropic\","
                + "\"model\":\"claude-opus-4-8\"}"));

        assertThat(decoded.providerThinkingLevel()).isNull();
    }

    @Test
    void writeThenReadRoundTrips() {
        var restored = (Message.AssistantMessage) MessageJsonCodec.decode(
            SessionJson.messageNode(assistant("max")));

        assertThat(restored.providerThinkingLevel()).isEqualTo("max");
    }
}
