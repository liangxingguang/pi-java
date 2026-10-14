package com.pijava.web;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web 线格式转换层：pi-java 持久化形状（SessionJson）→ pi-webui 前端期望的
 * pi-ai 形状。持久化格式不动，只影响发往前端的 WS 载荷。
 */
class WebWireJsonTest {

    @Test
    void thinkingBlockUsesThinkingField() {
        var m = new Message.AssistantMessage(List.of(
            new ContentBlock.TextContent("answer"),
            new ContentBlock.ThinkingContent("reasoning...")));
        var node = WebWireJson.messageNode(m);
        assertThat(node.get("role").asText()).isEqualTo("assistant");
        JsonNode thinking = node.get("content").get(1);
        assertThat(thinking.get("type").asText()).isEqualTo("thinking");
        assertThat(thinking.get("thinking").asText()).isEqualTo("reasoning...");
        assertThat(thinking.get("text")).isNull();
    }

    @Test
    void toolUseBlockBecomesToolCall() {
        var m = new Message.AssistantMessage(List.of(
            new ContentBlock.ToolUseContent("call-1", "bash", Map.of("command", "ls"))));
        var node = WebWireJson.messageNode(m);
        JsonBlock block = new JsonBlock(node.get("content").get(0));
        assertThat(block.type()).isEqualTo("toolCall");
        assertThat(node.get("content").get(0).get("id").asText()).isEqualTo("call-1");
        assertThat(node.get("content").get(0).get("name").asText()).isEqualTo("bash");
        assertThat(node.get("content").get(0).get("arguments").get("command").asText())
            .isEqualTo("ls");
    }

    @Test
    void toolResultRoleAndFieldRename() {
        var m = new Message.ToolResultMessage("call-1", "bash",
            List.of(new ContentBlock.TextContent("out")), false);
        var node = WebWireJson.messageNode(m);
        assertThat(node.get("role").asText()).isEqualTo("toolResult");
        assertThat(node.get("toolCallId").asText()).isEqualTo("call-1");
        assertThat(node.get("toolUseId")).isNull();
        assertThat(node.get("toolName").asText()).isEqualTo("bash");
        assertThat(node.get("isError").asBoolean()).isFalse();
    }

    @Test
    void toolResultCarriesStructuredPayloadOnTheWire() {
        // A7 第三路（原 docs/23c §5「Web WS：帧上 details 非 null」）：载荷键原样上帧，
        // 且 pi 的省略规则同守 —— undefined/null/空 ⇒ 键缺席，不给前端送 null 噪声。
        var m = new Message.ToolResultMessage("call-1", "rich",
            List.of(new ContentBlock.TextContent("ok")), Map.of("kind", "card"),
            Map.of("input", 3, "output", 5), List.of("mcp:late"), false, null);
        var node = WebWireJson.messageNode(m);
        assertThat(node.get("details").get("kind").asText()).isEqualTo("card");
        assertThat(node.get("usage").get("input").asInt()).isEqualTo(3);
        assertThat(node.get("addedToolNames").get(0).asText()).isEqualTo("mcp:late");

        var bare = WebWireJson.messageNode(new Message.ToolResultMessage("call-2", "plain",
            List.of(new ContentBlock.TextContent("ok")), null, null, List.of(), true, null));
        assertThat(bare.get("details")).isNull();
        assertThat(bare.get("usage")).isNull();
        assertThat(bare.get("addedToolNames")).isNull();
    }

    @Test
    void assistantCarriesIdentityAndMetricsButNoTimestamp() {
        // 3a（原 docs/31 §8.19）：身份三元组 + usage + stopReason/errorMessage 上 wire；
        // timestamp 缺席是**维持既有有意偏离**（wire 无消息 timestamp，
        // client/main.ts:261/286 直贴不判重），不是遗漏 —— 这条断言就是那条偏离的哨兵。
        var usage = new com.pijava.ai.Usage(10, 5, 1, 2, null, null, 18,
            com.pijava.ai.Usage.Cost.zero());
        var m = new Message.AssistantMessage(List.of(new ContentBlock.TextContent("hi")),
            StopReason.STOP, null, "openai-responses", "openai", "mock", usage,
            java.time.Instant.ofEpochMilli(1_700_000_000_000L), null, "max_tokens");
        var node = WebWireJson.messageNode(m);
        assertThat(node.get("stopReason").asText()).isEqualTo("stop");
        assertThat(node.get("api").asText()).isEqualTo("openai-responses");
        assertThat(node.get("provider").asText()).isEqualTo("openai");
        assertThat(node.get("model").asText()).isEqualTo("mock");
        assertThat(node.get("usage").get("input").asInt()).isEqualTo(10);
        assertThat(node.get("usage").get("cost").get("total").asInt()).isZero();
        assertThat(node.has("timestamp")).isFalse();
        assertThat(node.get("errorMessage")).isNull();
        assertThat(node.get("deferred")).isNull();
        // ⑨（D5）：线格原值与映射结果并列上 wire（pi 的消息本就全形状，3a 口径）。
        // 先断存在性再读值：缺键时读出的是 NPE，说不清是「没投影」还是「值不对」。
        assertThat(node.has("rawStopReason")).isTrue();
        assertThat(node.get("rawStopReason").asText()).isEqualTo("max_tokens");

        var bare = WebWireJson.messageNode(new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("old"))));
        assertThat(bare.get("api")).isNull();
        assertThat(bare.get("usage")).isNull();
        assertThat(bare.get("stopReason")).isNull();
        // 反向保证：缺席（null）时**不写键**，不是写一个 JSON null。
        assertThat(bare.has("rawStopReason")).isFalse();
    }

    /** 批 5（B148/B234）：assistant 五件新字段上 wire，缺席省略（含批 4 漏同步的 providerThinkingLevel）。 */
    @Test
    void assistantBatch5FieldsOnWire() {
        var details = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
            .put("status", 500);
        var diagnostics = List.of(
            new com.pijava.ai.message.AssistantMessageDiagnostic(
                "anthropic_input_transformations", 123L, null,
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                    .set("transformations",
                        new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode())));
        var m = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("hi")),
            StopReason.STOP, null,
            "anthropic-messages", "anthropic", "claude-fable-5",
            "claude-opus-5", "high", "medium", diagnostics,
            new com.pijava.ai.Usage(1, 1, 0, 0, null, null, 2,
                com.pijava.ai.Usage.Cost.zero()),
            java.time.Instant.ofEpochMilli(1_700_000_000_000L),
            null, null, 88L);
        var node = WebWireJson.messageNode(m);

        assertThat(node.get("responseModel").asText()).isEqualTo("claude-opus-5");
        assertThat(node.get("providerThinkingLevel").asText()).isEqualTo("high");
        assertThat(node.get("thinkingLevel").asText()).isEqualTo("medium");
        assertThat(node.get("diagnostics").get(0).get("type").asText())
            .isEqualTo("anthropic_input_transformations");
        assertThat(node.get("durationMs").asLong()).isEqualTo(88L);
        // 键序：model, responseModel, providerThinkingLevel, thinkingLevel, diagnostics
        var names = new java.util.ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names.indexOf("model") + 1).isEqualTo(names.indexOf("responseModel"));
        assertThat(names.indexOf("responseModel") + 1)
            .isEqualTo(names.indexOf("providerThinkingLevel"));

        var bare = WebWireJson.messageNode(new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("old"))));
        assertThat(bare.has("responseModel")).isFalse();
        assertThat(bare.has("providerThinkingLevel")).isFalse();
        assertThat(bare.has("thinkingLevel")).isFalse();
        assertThat(bare.has("diagnostics")).isFalse();
        assertThat(bare.has("durationMs")).isFalse();
    }

    /** B234：toolResult 终局消息多一个可选 durationMs。 */
    @Test
    void toolResultCarriesDurationMs() {
        var m = new Message.ToolResultMessage("call-1", "bash",
            List.of(new ContentBlock.TextContent("out")), null, null, List.of(),
            false, null, 21L);
        var node = WebWireJson.messageNode(m);
        assertThat(node.get("durationMs").asLong()).isEqualTo(21L);

        var bare = WebWireJson.messageNode(new Message.ToolResultMessage("call-2", "bash",
            List.of(new ContentBlock.TextContent("out")), false));
        assertThat(bare.has("durationMs")).isFalse();
    }

    @Test
    void userTextMessagePassesThrough() {
        var m = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        var node = WebWireJson.messageNode(m);
        assertThat(node.get("role").asText()).isEqualTo("user");
        assertThat(node.get("content").get(0).get("type").asText()).isEqualTo("text");
        assertThat(node.get("content").get(0).get("text").asText()).isEqualTo("hi");
    }

    @Test
    void toolResultInnerToolResultFlattensToText() {
        var inner = new ContentBlock.ToolResultContent("call-1", "bash",
            List.of(new ContentBlock.TextContent("out")), false);
        var m = new Message.ToolResultMessage("call-1", "bash", List.of(inner), false);
        var node = WebWireJson.messageNode(m);
        JsonNode first = node.get("content").get(0);
        assertThat(first.get("type").asText()).isEqualTo("text");
        assertThat(first.get("text").asText()).isEqualTo("out");
    }

    @Test
    void assistantPartialUsesThinkingField() {
        var partial = new com.pijava.ai.message.AssistantMessage("id-1",
            List.of(new ContentBlock.ThinkingContent("partial thought")), null, null);
        var node = WebWireJson.assistantNode(partial);
        assertThat(node.get("role").asText()).isEqualTo("assistant");
        assertThat(node.get("content").get(0).get("thinking").asText())
            .isEqualTo("partial thought");
    }

    private record JsonBlock(JsonNode node) {
        String type() {
            return node.get("type").asText();
        }
    }
}
