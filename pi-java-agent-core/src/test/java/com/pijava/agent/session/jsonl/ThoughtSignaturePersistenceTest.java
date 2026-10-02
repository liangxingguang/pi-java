package com.pijava.agent.session.jsonl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.agent.session.SessionJson;
import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;

/**
 * <b>Batch F 步 1</b>（{@code docs/67}）：第三、第四个块级签名字段的落线与回读 ——
 * {@code TextContent.textSignature}（Google 文本 part 的 thoughtSignature）
 * 与 {@code ToolUseContent.thoughtSignature}（Google functionCall／completions
 * legacy 路径）。
 *
 * <p>pi 的 {@code TextContent.textSignature?}（{@code types.ts:353}）与
 * {@code ToolCall.thoughtSignature?}（{@code types.ts:391}）均为可选字段：pi 经
 * {@code JSON.stringify} 逐字落盘 ⇒ 有签名必有键、{@code undefined} 必无键。
 * 写侧由 {@link SessionJson#blockNode} 显式建节点，读侧由
 * {@link MessageJsonCodec#decodeBlock} 显式取键。</p>
 */
class ThoughtSignaturePersistenceTest {

    private static List<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ── 写侧 ───────────────────────────────────────────────────────────

    @Test
    void blockNodeWritesTextSignature() {
        var node = SessionJson.blockNode(new ContentBlock.TextContent("hi", "U0lHTg=="));

        assertThat(node.get("type").asText()).isEqualTo("text");
        assertThat(node.get("text").asText()).isEqualTo("hi");
        assertThat(node.get("textSignature").asText()).isEqualTo("U0lHTg==");
    }

    @Test
    void blockNodeWritesThoughtSignatureOnToolUse() {
        var node = SessionJson.blockNode(new ContentBlock.ToolUseContent(
            "call_1", "bash", Map.of("command", "ls"), "U0lHTg=="));

        assertThat(node.get("type").asText()).isEqualTo("tool_use");
        assertThat(node.get("thoughtSignature").asText()).isEqualTo("U0lHTg==");
    }

    /** 无签名（便捷 ctor／null）⇒ 键缺席，与 pi 的 undefined 省略一致。 */
    @Test
    void blockNodeOmitsAbsentSignatures() {
        var text = SessionJson.blockNode(new ContentBlock.TextContent("hi"));
        assertThat(fieldNames(text)).containsExactly("type", "text");

        var tool = SessionJson.blockNode(
            new ContentBlock.ToolUseContent("call_1", "bash", Map.of()));
        assertThat(fieldNames(tool)).containsExactly("type", "id", "name", "arguments");
    }

    // ── 读侧 ───────────────────────────────────────────────────────────

    @Test
    void decodeBlockReadsTextSignature() throws Exception {
        var node = SessionJson.mapper().readTree(
            "{\"type\":\"text\",\"text\":\"hi\",\"textSignature\":\"U0lHTg==\"}");

        var block = (ContentBlock.TextContent) MessageJsonCodec.decodeBlock(node);

        assertThat(block.text()).isEqualTo("hi");
        assertThat(block.textSignature()).isEqualTo("U0lHTg==");
    }

    @Test
    void decodeBlockReadsThoughtSignature() throws Exception {
        var node = SessionJson.mapper().readTree(
            "{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"bash\","
            + "\"arguments\":{},\"thoughtSignature\":\"U0lHTg==\"}");

        var block = (ContentBlock.ToolUseContent) MessageJsonCodec.decodeBlock(node);

        assertThat(block.thoughtSignature()).isEqualTo("U0lHTg==");
    }

    /** 键缺席 ⇒ null（不是空串——空串是「有签名但为空」，语义不同，pi 侧两者都可能）。 */
    @Test
    void decodeBlockDefaultsMissingSignaturesToNull() {
        var text = (ContentBlock.TextContent) MessageJsonCodec.decodeBlock(
            SessionJson.blockNode(new ContentBlock.TextContent("hi")));
        assertThat(text.textSignature()).isNull();

        var tool = (ContentBlock.ToolUseContent) MessageJsonCodec.decodeBlock(
            SessionJson.blockNode(new ContentBlock.ToolUseContent("c", "n", Map.of())));
        assertThat(tool.thoughtSignature()).isNull();
    }

    // ── round-trip ─────────────────────────────────────────────────────

    @Test
    void roundTripsBothSignatures() {
        var original = List.of(
            new ContentBlock.TextContent("answer", "U0lHTg=="),
            new ContentBlock.ToolUseContent("call_1", "bash",
                Map.of("command", "ls"), "VE9PTA=="));

        var decoded = MessageJsonCodec.decodeBlocks(
            SessionJson.mapper().createArrayNode()
                .add(SessionJson.blockNode(original.get(0)))
                .add(SessionJson.blockNode(original.get(1))));

        var text = (ContentBlock.TextContent) decoded.get(0);
        assertThat(text.text()).isEqualTo("answer");
        assertThat(text.textSignature()).isEqualTo("U0lHTg==");
        var tool = (ContentBlock.ToolUseContent) decoded.get(1);
        assertThat(tool.thoughtSignature()).isEqualTo("VE9PTA==");
    }
}
