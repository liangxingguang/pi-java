package com.pijava.agent.session.jsonl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.agent.session.SessionJson;
import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;

/**
 * 批 30（ai#8）：{@code ToolUseContent.namespace}（pi {@code ToolCall.namespace?}，
 * {@code types.ts:442-443}）的落盘与回读——有值必有键、缺席必无键
 * （{@link ThoughtSignaturePersistenceTest} 同形先例）。
 */
class ToolUseNamespacePersistenceTest {

    private static List<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void blockNodeWritesNamespace() {
        var node = SessionJson.blockNode(new ContentBlock.ToolUseContent(
            "call_1", "get_weather", Map.of(), null, "mcp__docs"));

        assertThat(node.get("namespace").asText()).isEqualTo("mcp__docs");
    }

    /** 无 namespace ⇒ 键缺席（pi 的 undefined 省略）。 */
    @Test
    void blockNodeOmitsAbsentNamespace() {
        var node = SessionJson.blockNode(
            new ContentBlock.ToolUseContent("call_1", "bash", Map.of()));
        assertThat(fieldNames(node)).containsExactly("type", "id", "name", "arguments");
    }

    /** 读旧档（无 namespace 键）⇒ null，向前兼容。 */
    @Test
    void decodeBlockDefaultsMissingNamespaceToNull() throws Exception {
        var node = SessionJson.mapper().readTree(
            "{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"bash\",\"arguments\":{}}");

        var block = (ContentBlock.ToolUseContent) MessageJsonCodec.decodeBlock(node);
        assertThat(block.namespace()).isNull();
    }

    @Test
    void roundTripsNamespace() {
        var original = new ContentBlock.ToolUseContent(
            "call_1", "get_weather", Map.of("city", "Oslo"), "sig", "mcp__docs");

        var decoded = (ContentBlock.ToolUseContent) MessageJsonCodec.decodeBlock(
            SessionJson.blockNode(original));

        assertThat(decoded.namespace()).isEqualTo("mcp__docs");
        assertThat(decoded.thoughtSignature()).isEqualTo("sig");
        assertThat(decoded.arguments()).containsEntry("city", "Oslo");
    }
}
