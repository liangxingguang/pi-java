package com.pijava.agent.session.jsonl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.pijava.agent.session.SessionJson;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.NestedToolCallRecord;
import com.pijava.ai.message.NestedToolCalls;

import org.junit.jupiter.api.Test;

/**
 * 批 27（B162；pi {@code types.ts:598-637}）：tool result 的 {@code nestedCalls}
 * 落线与回读。pi 注释逐字 "Kept for the session record; not sent to the model"——
 * 写侧走 {@code SessionJson.messageNode}（NON_NULL 全局），读侧走
 * {@link MessageJsonCodec}；旧盘无键 ⇒ null，向前兼容。
 */
class NestedCallsRoundTripTest {

    private static final Instant T = Instant.parse("2026-10-28T12:00:00Z");

    private static Message.ToolResultMessage result(NestedToolCalls calls) {
        return new Message.ToolResultMessage("call_1", "run_tools",
            List.of(new ContentBlock.TextContent("done")), null, null, List.of(),
            false, T, null, calls);
    }

    @Test
    void writesAndReadsBackNestedCalls() throws Exception {
        var record = new NestedToolCallRecord("call_1/1", "read",
            SessionJson.mapper().readTree("{\"path\":\"a.txt\"}"), null,
            NestedToolCallRecord.Status.OK, 42L, null);
        var node = SessionJson.messageNode(result(new NestedToolCalls(List.of(record), true)));

        assertThat(node.get("nestedCalls").get("complete").asBoolean()).isTrue();
        var first = node.get("nestedCalls").get("calls").get(0);
        assertThat(first.get("id").asText()).isEqualTo("call_1/1");
        assertThat(first.get("status").asText()).isEqualTo("ok");
        assertThat(first.get("arguments").get("path").asText()).isEqualTo("a.txt");
        // argumentsBytes/durationMs 之外的缺席键不写
        assertThat(first.has("argumentsBytes")).isFalse();
        assertThat(first.has("error")).isFalse();

        var back = (Message.ToolResultMessage) MessageJsonCodec.decode(node);
        assertThat(back.nestedCalls().complete()).isTrue();
        assertThat(back.nestedCalls().calls()).containsExactly(record);
    }

    @Test
    void omittedArgumentsRoundTripAsBytes() {
        var record = new NestedToolCallRecord("call_1/2", "write", null, 9000L,
            NestedToolCallRecord.Status.ERROR, null, "boom");
        var node = SessionJson.messageNode(result(new NestedToolCalls(List.of(record), false)));

        var first = node.get("nestedCalls").get("calls").get(0);
        assertThat(first.has("arguments")).isFalse();
        assertThat(first.get("argumentsBytes").asLong()).isEqualTo(9000L);

        var back = (Message.ToolResultMessage) MessageJsonCodec.decode(node);
        var backRecord = back.nestedCalls().calls().get(0);
        assertThat(backRecord.arguments()).isNull();
        assertThat(backRecord.argumentsBytes()).isEqualTo(9000L);
        assertThat(backRecord.status()).isEqualTo(NestedToolCallRecord.Status.ERROR);
        assertThat(back.nestedCalls().complete()).isFalse();
    }

    /** 旧盘无 nestedCalls 键 ⇒ null（≙ pi 的 undefined，写侧省略）。 */
    @Test
    void legacyDataWithoutKeyReadsNull() {
        var node = SessionJson.messageNode(result(null));
        assertThat(node.has("nestedCalls")).isFalse();

        var back = (Message.ToolResultMessage) MessageJsonCodec.decode(node);
        assertThat(back.nestedCalls()).isNull();
    }
}
