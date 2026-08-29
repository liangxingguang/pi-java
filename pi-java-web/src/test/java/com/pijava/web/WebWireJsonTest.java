package com.pijava.web;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

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
