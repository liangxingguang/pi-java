package com.pijava.coding.agent.mode;

import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.core.AgentSessionEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包⑦（docs/34）：工具的**执行生命周期**事件上线 —— 此前落到
 * {@code default -> "unsupported_event"}。
 *
 * <p>pi 对非 {@code message_update} 事件是**原样透传**（{@code json-event.ts:48-51}
 * 的 {@code return event;}），所以这三条的载荷在 pi 的线上**逐字节**可见；
 * 三条变体的字段**全部必填、pi 侧一个 {@code ?} 都没有**（{@code types.ts:443-446}）
 * ⇒ 一个键都不许省（这与 package ④ 的可空键纪律相反，别照抄邻行）。</p>
 */
class JsonEventMapperToolExecutionTest {

    @Test
    void toolExecutionStartCarriesThreeFields() {
        var node = JsonEventMapper.toWire(new AgentSessionEvent.ToolExecutionStart(
            "call_1", "write", Map.of("path", "a.txt")));

        assertThat(node.get("type").asText()).isEqualTo("tool_execution_start");
        assertThat(node.get("toolCallId").asText()).isEqualTo("call_1");
        assertThat(node.get("toolName").asText()).isEqualTo("write");
        assertThat(node.get("args").get("path").asText()).isEqualTo("a.txt");
        assertThat(node.size()).as("start 恰好三个载荷键，一个不多一个不少").isEqualTo(4);
    }

    @Test
    void toolExecutionUpdateCarriesFourFields() {
        var node = JsonEventMapper.toWire(new AgentSessionEvent.ToolExecutionUpdate(
            "call_1", "bash", Map.of("command", "ls"),
            ToolResult.success("partial out")));

        assertThat(node.get("type").asText()).isEqualTo("tool_execution_update");
        assertThat(node.get("toolCallId").asText()).isEqualTo("call_1");
        assertThat(node.get("toolName").asText()).isEqualTo("bash");
        assertThat(node.get("args").get("command").asText()).isEqualTo("ls");
        assertThat(node.get("partialResult").get("content").get(0).get("text").asText())
            .isEqualTo("partial out");
        assertThat(node.size()).isEqualTo(5);
    }

    @Test
    void toolExecutionEndCarriesFourFieldsAndIsError() {
        var node = JsonEventMapper.toWire(new AgentSessionEvent.ToolExecutionEnd(
            "call_1", "write", ToolResult.success("ok"), false));

        assertThat(node.get("type").asText()).isEqualTo("tool_execution_end");
        assertThat(node.get("toolCallId").asText()).isEqualTo("call_1");
        assertThat(node.get("toolName").asText()).isEqualTo("write");
        assertThat(node.get("result").get("content").get(0).get("text").asText())
            .isEqualTo("ok");
        assertThat(node.get("isError").asBoolean()).isFalse();
        assertThat(node.size()).isEqualTo(5);
    }

    @Test
    void toolExecutionEndCarriesIsErrorTrueOnFailure() {
        // 反向：isError 不能恒 false（它是**另立的**字段，不在 result 里面）。
        var node = JsonEventMapper.toWire(new AgentSessionEvent.ToolExecutionEnd(
            "call_1", "write", ToolResult.success("denied"), true));
        assertThat(node.get("isError").asBoolean()).isTrue();
    }

    @Test
    void argsAndResultsArePassedThroughAsObjectsNotStrings() {
        // 透传：不是把对象再 stringify 一遍。
        var node = JsonEventMapper.toWire(new AgentSessionEvent.ToolExecutionStart(
            "c", "write", Map.of("nested", Map.of("k", 1))));
        assertThat(node.get("args").isObject()).isTrue();
        assertThat(node.get("args").get("nested").get("k").asInt()).isEqualTo(1);
    }

    @Test
    void nonToolResultPayloadFallsBackToPlainSerialization() {
        // 防御：result 的静态类型是 Object（≙ pi 的 any）。不是 ToolResult 时
        // 不该炸，按普通对象落线。
        var node = JsonEventMapper.toWire(new AgentSessionEvent.ToolExecutionEnd(
            "c", "t", Map.of("raw", true), false));
        assertThat(node.get("result").get("raw").asBoolean()).isTrue();
    }

    // ── 裁决 B：result 走显式投影，不拿默认 Jackson 直接落线 ──────────────
    //
    // pi 的 AgentToolResult 是 {content, details, usage?, addedToolNames?, terminate?}
    // （types.ts:362-376，后三个带 `?`）⇒ 缺席即省略。Java 的 ToolResult 是
    // record，terminate 是原始 boolean、addedToolNames 恒 []，照默认序列化会多出
    // 三个 pi 没有的键（docs/34 §5-N2）。

    @Test
    void resultProjectionOmitsAbsentOptionalFields() {
        var node = JsonEventMapper.toWire(new AgentSessionEvent.ToolExecutionEnd(
            "c", "write", ToolResult.success("ok"), false));

        var result = node.get("result");
        assertThat(result.get("content")).isNotNull();
        assertThat(result.has("details")).as("details 缺席 ⇒ 省略").isFalse();
        assertThat(result.has("usage")).as("usage 缺席 ⇒ 省略").isFalse();
        assertThat(result.has("addedToolNames")).as("空 addedToolNames ⇒ 省略").isFalse();
        assertThat(result.has("terminate")).as("terminate=false ⇒ 省略").isFalse();
        assertThat(result.size()).isEqualTo(1);
    }

    @Test
    void resultProjectionKeepsPresentOptionalFields() {
        var rich = new ToolResult<>(
            List.of(new ContentBlock.TextContent("done")),
            Map.of("k", "v"),
            new ToolResult.UsageInfo(7, 3),
            true,
            List.of("mcp__x"));

        var result = JsonEventMapper.toWire(new AgentSessionEvent.ToolExecutionEnd(
            "c", "write", rich, false)).get("result");

        assertThat(result.get("details").get("k").asText()).isEqualTo("v");
        assertThat(result.get("usage").get("inputTokens").asInt()).isEqualTo(7);
        assertThat(result.get("addedToolNames").get(0).asText()).isEqualTo("mcp__x");
        assertThat(result.get("terminate").asBoolean()).isTrue();
    }
}
