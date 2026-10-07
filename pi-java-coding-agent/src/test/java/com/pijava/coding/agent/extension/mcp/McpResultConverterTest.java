package com.pijava.coding.agent.extension.mcp;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.ai.message.ContentBlock;
import com.pijava.mcp.protocol.content.CallToolResult;
import com.pijava.mcp.protocol.content.McpContentBlock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code convertMcpResult}（{@code tools.ts:214-234}）。
 */
class McpResultConverterTest {

    private static final McpResultContent.Options PLAIN = McpResultContent.Options.defaults();

    private static CallToolResult result(List<McpContentBlock> content, Boolean error,
                                         Map<String, Object> structured, Map<String, Object> meta) {
        return new CallToolResult(content, structured, error, meta);
    }

    private static McpContentBlock.Text text(String value) {
        return new McpContentBlock.Text(value, null, null);
    }

    @Test
    void convertsContentBlocksAndCarriesTheDetails() {
        var converted = McpResultConverter.convert("files", "echo",
                result(List.of(text("hello")), false, null, null), PLAIN);

        assertThat(converted.isError()).isFalse();
        assertThat(((ContentBlock.TextContent) converted.content().get(0)).text()).isEqualTo("hello");
        assertThat(converted.details()).isEqualTo(McpToolDetails.of("files", "echo"));
    }

    @Test
    void emptyContentFallsBackToTheStructuredContentAsJson() {
        var converted = McpResultConverter.convert("files", "echo",
                result(List.of(), false, Map.of("answer", 42), null), PLAIN);

        var shown = ((ContentBlock.TextContent) converted.content().get(0)).text();
        assertThat(shown).contains("\"answer\"").contains("42");
    }

    @Test
    void anErrorResultWithoutAnyTextGetsASentence() {
        var converted = McpResultConverter.convert("files", "boom",
                result(List.of(), true, null, null), PLAIN);

        assertThat(converted.isError()).isTrue();
        assertThat(((ContentBlock.TextContent) converted.content().get(0)).text())
                .contains("MCP tool files/boom returned an error");
    }

    @Test
    void anErrorResultWithOnlyAnImageAlsoGetsTheSentence() {
        // The test is `textOf(converted) === ""`, not `content.length === 0` — an image leaves
        // the text empty, so the sentence is added even though there is a block.
        var image = new McpContentBlock.Image("QUJD", "image/png", null, null);
        var converted = McpResultConverter.convert("files", "boom",
                result(List.of(image), true, null, null), PLAIN);

        // 补句是 append 的：内容非空时它排在原块之后。
        assertThat(converted.content()).hasSize(2);
        assertThat(converted.content().get(0)).isInstanceOf(ContentBlock.ImageContent.class);
        assertThat(((ContentBlock.TextContent) converted.content().get(1)).text())
                .contains("MCP tool files/boom returned an error");
    }

    @Test
    void anErrorResultThatAlreadyHasTextIsLeftAlone() {
        var converted = McpResultConverter.convert("files", "boom",
                result(List.of(text("server said no")), true, null, null), PLAIN);

        assertThat(converted.content()).hasSize(1);
        assertThat(((ContentBlock.TextContent) converted.content().get(0)).text())
                .isEqualTo("server said no");
        assertThat(converted.isError()).isTrue();
    }

    @Test
    void theStructuredContentIsTheWholeResultWithoutMeta() {
        var converted = McpResultConverter.convert("files", "echo",
                result(List.of(text("hello")), false, Map.of("answer", 42),
                        Map.of("trace", "abc")), PLAIN);

        @SuppressWarnings("unchecked")
        var structured = (Map<String, Object>) converted.structuredContent();
        // The wire keys of a CallToolResult, without `_meta`; `error` serializes as `isError`.
        assertThat(structured).containsOnlyKeys("content", "structuredContent", "isError");
        assertThat(structured).doesNotContainKey("_meta");
        assertThat(structured.get("structuredContent")).isEqualTo(Map.of("answer", 42));
    }
}
