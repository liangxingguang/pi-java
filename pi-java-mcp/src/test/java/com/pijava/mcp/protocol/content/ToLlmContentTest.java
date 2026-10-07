package com.pijava.mcp.protocol.content;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToLlmContentTest {

    private static CallToolResult result(McpContentBlock... blocks) {
        return new CallToolResult(List.of(blocks), null, null, null);
    }

    @Test
    void passesTextAndImagesThrough() {
        assertThat(McpContents.toLlmContent(result(new McpContentBlock.Text("hi", null, null))))
                .containsExactly(new LlmContent.Text("hi"));
        assertThat(McpContents.toLlmContent(result(new McpContentBlock.Image("d", "image/png", null, null))))
                .containsExactly(new LlmContent.Image("d", "image/png"));
    }

    @Test
    void replacesAudioLinksAndResourcesWithPlaceholders() {
        assertThat(McpContents.toLlmContent(result(new McpContentBlock.Audio("d", "audio/wav", null, null))))
                .containsExactly(new LlmContent.Text("[audio audio/wav omitted]"));
        assertThat(McpContents.toLlmContent(result(new McpContentBlock.ResourceLink(
                        "uri", "name", null, null, null, null, null, null))))
                .containsExactly(new LlmContent.Text("name: uri"));

        assertThat(McpContents.toLlmContent(result(new McpContentBlock.Embedded(
                        new ResourceContents.Text("u", null, "t", null), null, null))))
                .containsExactly(new LlmContent.Text("t"));
        assertThat(McpContents.toLlmContent(result(new McpContentBlock.Embedded(
                        new ResourceContents.Blob("u", "image/png", "blobdata", null), null, null))))
                .containsExactly(new LlmContent.Image("blobdata", "image/png"));
        assertThat(McpContents.toLlmContent(result(new McpContentBlock.Embedded(
                        new ResourceContents.Blob("u", "application/octet-stream", "b", null), null, null))))
                .containsExactly(new LlmContent.Text(
                        "[binary resource u (application/octet-stream) omitted]"));
        assertThat(McpContents.toLlmContent(result(new McpContentBlock.Embedded(
                        new ResourceContents.Blob("u", null, "b", null), null, null))))
                .containsExactly(new LlmContent.Text("[binary resource u (unknown type) omitted]"));
    }

    @Test
    void fallsBackToStructuredContentJsonWhenBlocksAreEmpty() {
        var withStructured = new CallToolResult(null, Map.of("a", 1), null, null);
        var converted = McpContents.toLlmContent(withStructured);
        assertThat(converted).hasSize(1);
        assertThat(((LlmContent.Text) converted.get(0)).text()).contains("\"a\" : 1");

        var empty = new CallToolResult(List.of(), null, null, null);
        assertThat(McpContents.toLlmContent(empty)).isEmpty();
    }
}
