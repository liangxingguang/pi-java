package com.pijava.coding.agent.mode;

import java.util.List;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
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

    @Test
    void agentEndWireCarriesAssistantStopReasonAndOmitsNullDeferred() {
        var end = new AgentSessionEvent.AgentEnd(List.of(
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent("done")), "stop", null)),
            false);

        var node = JsonEventMapper.toWire(end);

        var msg = node.get("messages").get(0);
        assertThat(msg.get("stopReason").asText()).isEqualTo("stop");
        assertThat(msg.has("deferred")).isFalse();   // NON_NULL mixin
    }

    @Test
    void toStreamEventWireStripsPartial() {
        var partial = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("Hello world")));
        var json = JsonEventMapper.toStreamEventWire(
            new StreamEvent.TextDelta(0, "world", partial));
        assertThat(json).doesNotContain("partial");
        assertThat(json).contains("\"type\":\"text_delta\"")
            .contains("\"delta\":\"world\"");
    }

    // ── 3d 环 B：summarization_retry_*（pi agent-session.ts:2888-2911）──

    @Test
    void summarizationRetryScheduledCarriesFourFields() {
        var node = JsonEventMapper.toWire(new AgentSessionEvent.SummarizationRetryScheduled(
            2, 3, 4000L, "overloaded"));
        assertThat(node.get("type").asText()).isEqualTo("summarization_retry_scheduled");
        assertThat(node.get("attempt").asInt()).isEqualTo(2);
        assertThat(node.get("maxAttempts").asInt()).isEqualTo(3);
        assertThat(node.get("delayMs").asLong()).isEqualTo(4000L);
        assertThat(node.get("errorMessage").asText()).isEqualTo("overloaded");
    }

    @Test
    void summarizationRetryAttemptStartOmitsReasonForBranchSummary() {
        // pi 载荷 = source 对象展开：compaction 路 {source, reason}……
        var compaction = JsonEventMapper.toWire(
            new AgentSessionEvent.SummarizationRetryAttemptStart("compaction", "threshold"));
        assertThat(compaction.get("type").asText())
            .isEqualTo("summarization_retry_attempt_start");
        assertThat(compaction.get("source").asText()).isEqualTo("compaction");
        assertThat(compaction.get("reason").asText()).isEqualTo("threshold");

        // ……branchSummary 路 {source}，没有 reason 这个键（null ⇒ 主动省略）。
        var branch = JsonEventMapper.toWire(
            new AgentSessionEvent.SummarizationRetryAttemptStart("branchSummary", null));
        assertThat(branch.get("source").asText()).isEqualTo("branchSummary");
        assertThat(branch.has("reason")).isFalse();
        assertThat(branch.size()).isEqualTo(2);
    }

    @Test
    void summarizationRetryFinishedIsTypeOnly() {
        var node = JsonEventMapper.toWire(new AgentSessionEvent.SummarizationRetryFinished());
        assertThat(node.get("type").asText()).isEqualTo("summarization_retry_finished");
        assertThat(node.size()).isEqualTo(1);
    }

    // ── 3d 随附修正：compaction reason 上线是小写字面量（枚举名只是 Java 侧形状）──

    @Test
    void compactionReasonIsLowercaseLiteralOnWire() {
        var start = JsonEventMapper.toWire(new AgentSessionEvent.CompactionStart(
            AgentSessionEvent.CompactionReason.THRESHOLD));
        assertThat(start.get("type").asText()).isEqualTo("compaction_start");
        assertThat(start.get("reason").asText()).isEqualTo("threshold");

        var end = JsonEventMapper.toWire(new AgentSessionEvent.CompactionEnd(
            AgentSessionEvent.CompactionReason.OVERFLOW,
            new com.pijava.agent.compaction.CompactionResult(
                "summary", "kept-1", 100L, 40L, null, java.util.Map.of()),
            false, false, null));
        assertThat(end.get("type").asText()).isEqualTo("compaction_end");
        assertThat(end.get("reason").asText()).isEqualTo("overflow");
    }
}
