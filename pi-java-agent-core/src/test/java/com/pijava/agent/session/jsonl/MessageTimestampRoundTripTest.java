package com.pijava.agent.session.jsonl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.session.SessionJson;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;

/**
 * <b>docs/71 G1</b>：消息级 {@code timestamp} 的落线与回读。
 *
 * <p>pi 的四个消息变体 {@code timestamp} 皆**必填**（{@code types.ts:506/512/536/549}）；
 * 本仓此前只有 {@code SystemMessage}/{@code AssistantMessage} 有，user/toolResult
 * 既无字段、也无键、也无回读。本夹具钉住新增的两条链 + **旧数据回退**（无键 ⇒ null）。</p>
 */
class MessageTimestampRoundTripTest {

    private static final Instant T = Instant.parse("2026-10-03T12:00:00Z");

    private static Instant timestampOf(Message message) {
        return message instanceof Message.UserMessage user ? user.timestamp()
            : ((Message.ToolResultMessage) message).timestamp();
    }

    private static List<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ── 写侧 ───────────────────────────────────────────────────────────

    @Test
    void userMessageWritesTimestampKey() {
        var node = SessionJson.messageNode(new Message.UserMessage(
            List.of(new ContentBlock.TextContent("hi")), T));

        assertThat(node.get("timestamp").asLong()).isEqualTo(T.toEpochMilli());
    }

    @Test
    void toolResultMessageWritesTimestampKey() {
        var node = SessionJson.messageNode(new Message.ToolResultMessage(
            "call_1", "bash", List.of(new ContentBlock.TextContent("out")),
            null, null, List.of(), false, T));

        assertThat(node.get("timestamp").asLong()).isEqualTo(T.toEpochMilli());
    }

    /** 便捷构造器（无时间戳）⇒ 键缺席，与 pi 的 {@code undefined} 省略同形。 */
    @Test
    void messagesWithoutTimestampOmitTheKey() {
        var user = SessionJson.messageNode(new Message.UserMessage(
            List.of(new ContentBlock.TextContent("hi"))));
        var tool = SessionJson.messageNode(new Message.ToolResultMessage(
            "call_1", "bash", List.of(new ContentBlock.TextContent("out")), false));

        assertThat(fieldNames(user)).doesNotContain("timestamp");
        assertThat(fieldNames(tool)).doesNotContain("timestamp");
    }

    // ── 读侧 ───────────────────────────────────────────────────────────

    @Test
    void decodeReadsTimestampForUserAndToolResult() throws Exception {
        var mapper = SessionJson.mapper();
        var user = MessageJsonCodec.decode(mapper.readTree(
            "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                + "\"timestamp\":" + T.toEpochMilli() + "}"));
        var tool = MessageJsonCodec.decode(mapper.readTree(
            "{\"role\":\"tool\",\"toolUseId\":\"c1\",\"toolName\":\"bash\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"out\"}],\"isError\":false,"
                + "\"timestamp\":" + T.toEpochMilli() + "}"));

        assertThat(timestampOf(user)).isEqualTo(T);
        assertThat(timestampOf(tool)).isEqualTo(T);
    }

    /** 既有会话文件（R1：本仓旧数据**没有**这个键）⇒ 解码为 null、不抛。 */
    @Test
    void decodeOfLegacyJsonWithoutTimestampYieldsNull() throws Exception {
        var mapper = SessionJson.mapper();
        var user = MessageJsonCodec.decode(mapper.readTree(
            "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}"));

        assertThat(timestampOf(user)).isNull();
    }

    // ── round-trip ─────────────────────────────────────────────────────

    @Test
    void writeThenReadRoundTripsTheTimestamp() {
        var original = new Message.UserMessage(
            List.of(new ContentBlock.TextContent("hi")), T);

        var restored = MessageJsonCodec.decode(SessionJson.messageNode(original));

        assertThat(timestampOf(restored)).isEqualTo(T);
    }
}
