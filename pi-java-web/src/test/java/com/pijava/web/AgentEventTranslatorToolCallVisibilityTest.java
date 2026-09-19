package com.pijava.web;

import java.util.List;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.web.WebProtocol.WebServerMessage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包⑥（docs/33 B40）：web 面上的工具调用可见性。
 *
 * <p>缺口是**推送时机**，不是载荷字段：前端在 {@code tool_execution_*} 上只调
 * {@code renderApp()}、完全不读载荷（{@code client/main.ts:316-320}），而它手上
 * 最后一条 {@code message_update} 是工具调用**之前**那条 ⇒ 工具卡要等
 * {@code agent_end} 整表替换才出现。渲染侧**已经就绪**（
 * {@code node_modules/@mariozechner/pi-web-ui/dist/components/Messages.js:74/:88/:223}
 * 会渲染 {@code chunk.type === "toolCall"}，工具名回落到块里的 {@code name}）。</p>
 *
 * <p>故本包在三个工具增量上**增加**一条 {@code message_update}（携带累积
 * {@code partial}），**保留**既有的三个 {@code tool_execution_*}
 * （{@code docs/15:148} 有意为之，既有测试断言它们）。</p>
 */
class AgentEventTranslatorToolCallVisibilityTest {

    private final AgentEventTranslator translator = new AgentEventTranslator();

    private static StreamEvent.ToolCallStart toolCallStart() {
        var builder = new StreamPartialBuilder();
        builder.emitStart();
        return builder.emitToolCallStart("call_1", "write");
    }

    private static String type(WebServerMessage msg) {
        return ((WebServerMessage.AgentEvent) msg).event().get("type").asText();
    }

    private static List<String> types(List<WebServerMessage> msgs) {
        return msgs.stream().map(AgentEventTranslatorToolCallVisibilityTest::type).toList();
    }

    private static com.fasterxml.jackson.databind.JsonNode event(WebServerMessage msg) {
        return ((WebServerMessage.AgentEvent) msg).event();
    }

    @Test
    void toolCallStartAlsoPushesAMessageUpdateCarryingTheToolCallBlock() {
        var msgs = translator.translate(new AgentSessionEvent.MessageUpdate(toolCallStart()));

        // ⑥ 不回归：三个 tool_execution_* 仍在。
        assertThat(types(msgs)).containsExactly("tool_execution_start", "message_update");

        var update = event(msgs.get(1));
        assertThat(update.get("message").get("role").asText()).isEqualTo("assistant");
        var block = update.get("message").get("content").get(0);
        assertThat(block.get("type").asText()).isEqualTo("toolCall");
        assertThat(block.get("id").asText()).isEqualTo("call_1");
        assertThat(block.get("name").asText()).isEqualTo("write");
    }

    @Test
    void toolCallDeltaAlsoPushesAMessageUpdate() {
        var builder = new StreamPartialBuilder();
        builder.emitStart();
        builder.emitToolCallStart("call_1", "write");
        var delta = builder.emitToolCallDelta("call_1", "{\"path\":\"a.txt\"}");

        var msgs = translator.translate(new AgentSessionEvent.MessageUpdate(delta));

        assertThat(types(msgs)).containsExactly("tool_execution_update", "message_update");
        var block = event(msgs.get(1)).get("message").get("content").get(0);
        assertThat(block.get("type").asText()).isEqualTo("toolCall");
        assertThat(block.get("name").asText()).isEqualTo("write");
    }

    @Test
    void toolCallEndAlsoPushesAMessageUpdate() {
        var builder = new StreamPartialBuilder();
        builder.emitStart();
        builder.emitToolCallStart("call_1", "write");
        builder.emitToolCallDelta("call_1", "{\"path\":\"a.txt\"}");
        var end = builder.emitToolCallEnd("call_1", "write");

        var msgs = translator.translate(new AgentSessionEvent.MessageUpdate(end));

        assertThat(types(msgs)).containsExactly("tool_execution_end", "message_update");
        var block = event(msgs.get(1)).get("message").get("content").get(0);
        assertThat(block.get("type").asText()).isEqualTo("toolCall");
        assertThat(block.get("arguments").get("path").asText()).isEqualTo("a.txt");
    }

    @Test
    void textDeltaStillPushesExactlyOneMessageUpdate() {
        // 反向：新增的推送**只**挂在三个工具增量上，文本/思考路径不能被翻倍。
        var partial = AssistantMessage.empty()
            .withContent(List.of(new com.pijava.ai.message.ContentBlock.TextContent("hi")));

        var msgs = translator.translate(new AgentSessionEvent.MessageUpdate(
            new StreamEvent.TextDelta(0, "hi", partial)));

        assertThat(types(msgs)).containsExactly("message_update");
    }
}
