package com.pijava.coding.agent.mode;

import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.ai.Usage;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;
import com.pijava.coding.agent.core.AgentSessionEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 包⑥（docs/33）：{@code message_update} 的线格式补两样东西 —— 恒写的顶层
 * {@code usage}，以及 {@code toolcall_start} 上的 {@code id}/{@code toolName}。
 *
 * <p>出处：pi {@code modes/json-event.ts:56-66}（恒写 {@code usage}）、
 * {@code :22-30}（起点补身份、非 toolCall 就抛）。</p>
 */
class JsonEventMapperMessageUpdateTest {

    private static AgentSessionEvent msgUpdate(StreamEvent event) {
        return new AgentSessionEvent.MessageUpdate(event);
    }

    private static StreamEvent.ToolCallStart toolCallStartViaBuilder(String id, String name) {
        // ⚠️ 走 StreamPartialBuilder 造帧：两个桩（FauxProvider 的旧形状 /
        // ScriptedStreams 的空内容快照）都不是 pi 的读法（docs/33 §4-F）。
        var builder = new StreamPartialBuilder();
        builder.emitStart();
        return builder.emitToolCallStart(id, name);
    }

    @Test
    void messageUpdateAlwaysCarriesTopLevelUsage() {
        var partial = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("hi")))
            .withUsage(new StreamEvent.UsageInfo(120, 30, null, Usage.of(120, 30)));

        var node = JsonEventMapper.toWire(msgUpdate(
            new StreamEvent.TextDelta(0, "hi", partial)));

        assertThat(node.get("type").asText()).isEqualTo("message_update");
        var usage = node.get("usage");
        assertThat(usage).isNotNull();
        assertThat(usage.get("input").asDouble()).isEqualTo(120.0);
        assertThat(usage.get("output").asDouble()).isEqualTo(30.0);
        assertThat(usage.get("totalTokens").asDouble()).isEqualTo(150.0);
    }

    @Test
    void messageUpdateWithoutUsageWritesZeroValuedObjectNotMissingKey() {
        // pi 的 usage 永不为 undefined（流起点初始化为全零对象，
        // anthropic-messages.ts:518-525）⇒ 缺 UsageInfo 时也必须写零值对象。
        var partial = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("hi")));

        var node = JsonEventMapper.toWire(msgUpdate(
            new StreamEvent.TextDelta(0, "hi", partial)));

        var usage = node.get("usage");
        assertThat(usage).isNotNull();
        assertThat(usage.get("input").asDouble()).isZero();
        assertThat(usage.get("output").asDouble()).isZero();
        assertThat(usage.get("cacheRead").asDouble()).isZero();
        assertThat(usage.get("cacheWrite").asDouble()).isZero();
        assertThat(usage.get("totalTokens").asDouble()).isZero();
        assertThat(usage.get("cost").get("total").asDouble()).isZero();
    }

    @Test
    void toolcallStartCarriesIdAndToolNameFromThePartialBlock() {
        var start = toolCallStartViaBuilder("call_7", "write");

        var node = JsonEventMapper.toWire(msgUpdate(start));

        var delta = node.get("assistantMessageEvent");
        assertThat(delta.get("type").asText()).isEqualTo("toolcall_start");
        assertThat(delta.has("partial")).isFalse();
        assertThat(delta.get("contentIndex").asInt()).isZero();
        assertThat(delta.get("id").asText()).isEqualTo("call_7");
        assertThat(delta.get("toolName").asText()).isEqualTo("write");
    }

    @Test
    void toolcallStartThrowsWhenTheBlockIsNotAToolCall() {
        // pi json-event.ts:25-27 原文：`toolCall?.type !== "toolCall"` ⇒ 抛。
        // Java 侧用 build 好的快照造同样的错位（起点块位置放的是文本块）。
        var partial = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("not a tool call")));
        var event = new StreamEvent.ToolCallStart(0, partial);

        assertThatThrownBy(() -> JsonEventMapper.toWire(msgUpdate(event)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("toolcall_start content at index 0 is not a tool call");
    }

    @Test
    void nonToolCallStreamEventsKeepTheirOwnFields() {
        // 反向：身份键**只**缀在 toolcall_start 上，别的增量不能被污染。
        var partial = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("hello")));

        ObjectNode delta = (ObjectNode) JsonEventMapper.toWire(msgUpdate(
            new StreamEvent.TextDelta(0, "hello", partial))).get("assistantMessageEvent");

        assertThat(delta.has("id")).isFalse();
        assertThat(delta.has("toolName")).isFalse();
        assertThat(delta.get("delta").asText()).isEqualTo("hello");
        assertThat(delta.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("type", "contentIndex", "delta");
    }

    @Test
    void toolcallDeltaIsUntouchedByTheIdentityRule() {
        // pi 只对 toolcall_start 补身份 ⇒ toolcall_delta 的载荷原样（含它自己的 id 字段）。
        var builder = new StreamPartialBuilder();
        builder.emitStart();
        builder.emitToolCallStart("call_7", "write");
        var delta = builder.emitToolCallDelta("call_7", "{\"a\":1}");

        var deltaNode = JsonEventMapper.toWire(msgUpdate(delta))
            .get("assistantMessageEvent");

        assertThat(deltaNode.get("type").asText()).isEqualTo("toolcall_delta");
        assertThat(deltaNode.get("id").asText()).isEqualTo("call_7");
        assertThat(deltaNode.has("toolName")).isFalse();
    }

    @Test
    void toolcallStartWireKeysAreOrderedTypeUsageAssistantMessageEvent() {
        // 键序与 pi 的字面量同（json-event.ts:59-64）：type, usage, assistantMessageEvent。
        var node = JsonEventMapper.toWire(msgUpdate(
            new StreamEvent.TextDelta(0, "x", AssistantMessage.empty())));
        assertThat(node.fieldNames()).toIterable()
            .containsExactly("type", "usage", "assistantMessageEvent");
    }

    @Test
    void usageNormalizationPrefersTheFullBreakdownOverTheCounts() {
        // 有全量分解用全量（含 cache/cost），不是拿 input/output 现算。
        var full = new Usage(10, 5, 7, 3, null, null, 25, new Usage.Cost(0.1, 0.2, 0, 0, 0.3));
        var partial = AssistantMessage.empty()
            .withUsage(new StreamEvent.UsageInfo(10, 5, null, full));

        var node = JsonEventMapper.toWire(msgUpdate(
            new StreamEvent.TextDelta(0, "x", partial)));

        assertThat(node.get("usage").get("cacheRead").asDouble()).isEqualTo(7.0);
        assertThat(node.get("usage").get("totalTokens").asDouble()).isEqualTo(25.0);
        assertThat(node.get("usage").get("cost").get("total").asDouble()).isEqualTo(0.3);
    }

    @Test
    void usageInfoEventWithoutPartialStillGetsZeroUsage() {
        // UsageInfo 是唯一允许 partial == null 的变体（StreamEvent:57）——
        // 这条路上不能 NPE，仍要写出零值对象。
        var node = JsonEventMapper.toWire(msgUpdate(
            new StreamEvent.UsageInfo(9, 4, null)));

        assertThat(node.get("usage").get("totalTokens").asDouble()).isZero();
        assertThat(node.get("assistantMessageEvent").get("type").asText())
            .isEqualTo("usage");
    }
}
