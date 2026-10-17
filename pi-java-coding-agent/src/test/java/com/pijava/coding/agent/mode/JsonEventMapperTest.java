package com.pijava.coding.agent.mode;

import java.util.List;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
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

    /**
     * 批 2：`agent_settled` 改回 pi 的真形状 —— {@code { type: "agent_settled";
     * aborted: boolean }}（{@code agent-session.ts:203}），单字段必填。
     * 旧的 message+toolResults 载荷由真 {@code turn_end} 帧继承（{@code types.ts:517}）。
     */
    @Test
    void agentSettledCarriesOnlyAborted() {
        var node = JsonEventMapper.toWire(new AgentSessionEvent.AgentSettled(false));

        assertThat(node.get("type").asText()).isEqualTo("agent_settled");
        assertThat(node.get("aborted").asBoolean()).isFalse();
        assertThat(node.has("message")).as("旧载荷已拆（同型异义雷）").isFalse();
        assertThat(node.has("toolResults")).isFalse();
    }

    @Test
    void turnEndCarriesMessageAndToolResults() {
        var assistant = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("done")), StopReason.STOP, null,
            null, null, null, null, null, null, null);
        var result = new Message.ToolResultMessage("call_1", "write",
            List.of(new ContentBlock.TextContent("ok")), null, null, List.of(), false, null);

        var node = JsonEventMapper.toWire(
            new AgentSessionEvent.TurnEnd(assistant, List.of(result)));

        // pi 的 turn_end 是 { message, toolResults }，两个字段都必填（types.ts:517）。
        assertThat(node.get("type").asText()).isEqualTo("turn_end");
        assertThat(node.get("message").get("role").asText()).isEqualTo("assistant");
        assertThat(node.get("message").get("stopReason").asText()).isEqualTo("stop");
        assertThat(node.get("toolResults")).hasSize(1);
        assertThat(node.get("toolResults").get(0).get("toolCallId").asText())
            .isEqualTo("call_1");
        assertThat(node.get("toolResults").get(0).get("isError").asBoolean()).isFalse();
    }

    /** 批 2 五新帧的其余四个：字段全必填 ⇒ 一律不省键。 */
    @Test
    void wrappedAgentEventFrames() {
        assertThat(JsonEventMapper.toWire(new AgentSessionEvent.AgentStart())
            .get("type").asText()).isEqualTo("agent_start");
        assertThat(JsonEventMapper.toWire(new AgentSessionEvent.TurnStart())
            .get("type").asText()).isEqualTo("turn_start");

        var user = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        var start = JsonEventMapper.toWire(new AgentSessionEvent.MessageStart(user));
        assertThat(start.get("type").asText()).isEqualTo("message_start");
        assertThat(start.get("message").get("role").asText()).isEqualTo("user");

        var end = JsonEventMapper.toWire(new AgentSessionEvent.MessageEnd(user));
        assertThat(end.get("type").asText()).isEqualTo("message_end");
        assertThat(end.get("message").get("role").asText()).isEqualTo("user");
    }

    @Test
    void agentEndWireCarriesAssistantStopReasonAndOmitsNullDeferred() {
        var end = new AgentSessionEvent.AgentEnd(List.of(
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent("done")),
            StopReason.STOP, null, null, null, null, null, null, null, null)),
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

    /**
     * B110（pi {@code proxy.ts:42-58}）：done/error 终局帧在 {@code usage} 后写
     * {@code providerThinkingLevel?}；partial 没带值（非 managed 车道）⇒ 整键省略。
     */
    @Test
    void terminalFramesCarryProviderThinkingLevelAfterUsage() {
        var stamped = AssistantMessage.empty()
            .withIdentity("anthropic-messages", "anthropic", "claude-fable-5-1", null)
            .withProviderThinkingLevel("high");
        var done = JsonEventMapper.toStreamEventWire(
            new StreamEvent.StreamDone(StopReason.STOP, null, stamped));
        assertThat(done).contains("\"type\":\"done\"")
            .contains("\"providerThinkingLevel\":\"high\"");
        // 键序：providerThinkingLevel 在 usage 之后。
        assertThat(done.indexOf("\"usage\"")).isLessThan(done.indexOf("providerThinkingLevel"));

        var error = JsonEventMapper.toStreamEventWire(new StreamEvent.StreamError(
            StopReason.ERROR, new RuntimeException("boom"), stamped));
        assertThat(error).contains("\"type\":\"error\"")
            .contains("\"providerThinkingLevel\":\"high\"");
    }

    /** 非 managed 车道的终局帧不带该键。 */
    @Test
    void terminalFramesOmitProviderThinkingLevelWhenUnstamped() {
        var plain = AssistantMessage.empty()
            .withIdentity("openai-completions", "openai", "gpt-5", null);
        var done = JsonEventMapper.toStreamEventWire(
            new StreamEvent.StreamDone(StopReason.STOP, null, plain));
        assertThat(done).doesNotContain("providerThinkingLevel");
    }

    /**
     * 批 5 裁决⑥：proxy 形状终局帧只有 type/reason/usage/providerThinkingLevel ——
     * 消息字段（responseModel/diagnostics/thinkingLevel/durationMs）随
     * message_end 的整条消息上线，不进这个扁平帧。
     */
    @Test
    void terminalFramesNeverCarryMessageFields() {
        var stamped = AssistantMessage.empty()
            .withIdentity("anthropic-messages", "anthropic", "claude-fable-5", null)
            .withResponseModel("claude-opus-5")
            .withDurationMs(42L)
            .withDiagnostics(List.of(
                new com.pijava.ai.message.AssistantMessageDiagnostic(
                    "pi_messages_rewrite", 1L, null,
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode())));
        var done = JsonEventMapper.toStreamEventWire(
            new StreamEvent.StreamDone(StopReason.STOP, null, stamped));
        assertThat(done).doesNotContain("responseModel")
            .doesNotContain("diagnostics")
            .doesNotContain("thinkingLevel")
            .doesNotContain("durationMs");
    }

    /**
     * 批 5：message_end 的整条消息（messageNode 泛投影）携带
     * responseModel/thinkingLevel/diagnostics/durationMs；null 省略。
     */
    @Test
    void messageNodeCarriesBatch5Fields() {
        var diagnostics = List.of(
            new com.pijava.ai.message.AssistantMessageDiagnostic(
                "pi_messages_rewrite", 9L, null,
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                    .put("policyId", "p1")));
        var message = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("hi")),
            StopReason.STOP, null,
            "anthropic-messages", "anthropic", "claude-fable-5",
            "claude-opus-5", "high", "medium", diagnostics,
            new com.pijava.ai.Usage(1, 1, 0, 0, null, null, 2,
                com.pijava.ai.Usage.Cost.zero()),
            java.time.Instant.ofEpochMilli(1_700_000_000_000L),
            null, null, 33L);
        var node = JsonEventMapper.messageNode(message);

        assertThat(node.get("responseModel").asText()).isEqualTo("claude-opus-5");
        assertThat(node.get("thinkingLevel").asText()).isEqualTo("medium");
        assertThat(node.get("providerThinkingLevel").asText()).isEqualTo("high");
        assertThat(node.get("diagnostics").get(0).get("type").asText())
            .isEqualTo("pi_messages_rewrite");
        assertThat(node.get("durationMs").asLong()).isEqualTo(33L);

        var bare = JsonEventMapper.messageNode(new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("hi"))));
        assertThat(bare.has("responseModel")).isFalse();
        assertThat(bare.has("thinkingLevel")).isFalse();
        assertThat(bare.has("diagnostics")).isFalse();
        assertThat(bare.has("durationMs")).isFalse();
    }

    /** B234：工具结果消息的 durationMs 随 messageNode 上线（键名仍是工具方言 toolCallId）。 */
    @Test
    void toolResultMessageCarriesDuration() {
        var tool = new Message.ToolResultMessage("tc1", "bash",
            List.of(new ContentBlock.TextContent("ok")), null, null, List.of(),
            false, java.time.Instant.ofEpochMilli(1_700_000_000_000L), 14L);
        var node = JsonEventMapper.messageNode(tool);
        assertThat(node.get("toolCallId").asText()).isEqualTo("tc1");
        assertThat(node.get("durationMs").asLong()).isEqualTo(14L);
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

    // ── 包④ B 组：可空键按 pi 的 `?` 省略（原 docs/31 §8.37.4）──
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
