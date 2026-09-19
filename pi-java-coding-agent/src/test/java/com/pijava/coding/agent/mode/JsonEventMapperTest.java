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

    // ── 包④ B 组：可空键按 pi 的 `?` 省略（docs/31 §8.37.4）──
    //
    // 每条都配一条**反向断言**（有值时必须在）——否则「一律删键」这种改坏法
    // 也能让夹具变绿。pi 的取值处：json-event.ts:48-51 原样透传 ⇒ 省略与否
    // 完全由 JSON.stringify 对 undefined 的行为决定。

    @Test
    void autoRetryEndOmitsFinalErrorOnSuccessfulReset() {
        // pi :700-706（成功复位）**根本不写**这个键。
        var success = JsonEventMapper.toWire(new AgentSessionEvent.AutoRetryEnd(true, 2, null));
        assertThat(success.get("type").asText()).isEqualTo("auto_retry_end");
        assertThat(success.get("success").asBoolean()).isTrue();
        assertThat(success.get("attempt").asInt()).isEqualTo(2);
        assertThat(success.has("finalError")).isFalse();
        assertThat(success.size()).isEqualTo(3);

        // 反向：终局失败路（:1127-1135）与退避中被中止路（:2955-2960）**必须**带。
        var failed = JsonEventMapper.toWire(
            new AgentSessionEvent.AutoRetryEnd(false, 3, "Retry cancelled"));
        assertThat(failed.get("finalError").asText()).isEqualTo("Retry cancelled");
        assertThat(failed.size()).isEqualTo(4);
    }

    @Test
    void compactionEndOmitsResultWhenAbsentAndKeepsItWhenPresent() {
        // pi 的 `result: CompactionResult | undefined`（:164）：取消/中止/失败/R1 闩锁
        // 四路都显式写 undefined（:2100/:2201/:2311/:2366）⇒ 键被省略。
        var aborted = JsonEventMapper.toWire(new AgentSessionEvent.CompactionEnd(
            AgentSessionEvent.CompactionReason.MANUAL, null, true, false, null));
        assertThat(aborted.get("reason").asText()).isEqualTo("manual");
        assertThat(aborted.get("aborted").asBoolean()).isTrue();
        assertThat(aborted.get("willRetry").asBoolean()).isFalse();
        assertThat(aborted.has("result")).isFalse();
        assertThat(aborted.has("errorMessage")).isFalse();
        assertThat(aborted.size()).isEqualTo(4);

        // 反向：成功路（CompactionExecutor:289 发 result + errorMessage=null）
        // ⇒ 有 result、无 errorMessage。
        var succeeded = JsonEventMapper.toWire(new AgentSessionEvent.CompactionEnd(
            AgentSessionEvent.CompactionReason.OVERFLOW,
            new com.pijava.agent.compaction.CompactionResult(
                "summary", "kept-1", 100L, 40L, null, java.util.Map.of()),
            false, false, null));
        assertThat(succeeded.has("result")).isTrue();
        assertThat(succeeded.get("result").get("firstKeptEntryId").asText())
            .isEqualTo("kept-1");
        assertThat(succeeded.has("errorMessage")).isFalse();

        // 反向：失败路（CompactionExecutor:98/:210 发 errorMessage + result=null）。
        var failed = JsonEventMapper.toWire(new AgentSessionEvent.CompactionEnd(
            AgentSessionEvent.CompactionReason.THRESHOLD, null, false, false,
            "Auto-compaction failed: boom"));
        assertThat(failed.get("errorMessage").asText()).isEqualTo("Auto-compaction failed: boom");
        assertThat(failed.has("result")).isFalse();
    }

    @Test
    void bashExecutionUpdateOmitsIdWhenAbsent() {
        // pi 的 `id?: string`（:185，取值 `options?.id`，:3027）：RPC 的 bash 命令
        // id 可选，缺省即无该键。
        var withoutId = JsonEventMapper.toWire(
            new AgentSessionEvent.BashExecutionUpdate(null, "out"));
        assertThat(withoutId.get("type").asText()).isEqualTo("bash_execution_update");
        assertThat(withoutId.get("delta").asText()).isEqualTo("out");
        assertThat(withoutId.has("id")).isFalse();
        assertThat(withoutId.size()).isEqualTo(2);

        // 反向：带 id 时必须在。
        var withId = JsonEventMapper.toWire(
            new AgentSessionEvent.BashExecutionUpdate("b-1", "out"));
        assertThat(withId.get("id").asText()).isEqualTo("b-1");
    }
}
