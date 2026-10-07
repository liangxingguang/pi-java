package com.pijava.mcp.protocol.content;

import com.pijava.mcp.McpJson;
import com.pijava.mcp.protocol.ToolExecution;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class McpContentBlocksTest {

    private final ObjectMapper mapper = McpJson.mapper();

    private void assertRoundTrip(McpContentBlock block, String... contains) throws Exception {
        var json = mapper.writeValueAsString(block);
        for (var fragment : contains) {
            assertThat(json).contains(fragment);
        }
        assertThat(mapper.readValue(json, McpContentBlock.class)).isEqualTo(block);
    }

    @Test
    void roundTripsAllBlockTypes() throws Exception {
        assertRoundTrip(new McpContentBlock.Text("hi", null, null), "\"type\":\"text\"");
        assertRoundTrip(new McpContentBlock.Image("data", "image/png", null, null),
                "\"type\":\"image\"", "\"mimeType\":\"image/png\"");
        assertRoundTrip(new McpContentBlock.Audio("data", "audio/wav", null, null),
                "\"type\":\"audio\"");
        assertRoundTrip(new McpContentBlock.ResourceLink("uri", "name", null, null, null, null, null, null),
                "\"type\":\"resource_link\"", "\"uri\":\"uri\"");
        assertRoundTrip(new McpContentBlock.Embedded(new ResourceContents.Text("u", null, "t", null), null, null),
                "\"type\":\"resource\"");
    }

    @Test
    void resourceContentsAreDeducedWithoutATypeField() throws Exception {
        var textJson = mapper.writeValueAsString(new ResourceContents.Text("u", null, "t", null));
        assertThat(textJson).doesNotContain("\"type\"");
        assertThat(mapper.readValue(textJson, ResourceContents.class))
                .isInstanceOf(ResourceContents.Text.class);

        var blobJson = mapper.writeValueAsString(new ResourceContents.Blob("u", null, "b", null));
        assertThat(blobJson).doesNotContain("\"type\"");
        assertThat(mapper.readValue(blobJson, ResourceContents.class))
                .isInstanceOf(ResourceContents.Blob.class);
    }

    @Test
    void callToolResultUsesIsErrorKey() throws Exception {
        var error = new CallToolResult(java.util.List.of(), null, true, null);
        assertThat(mapper.writeValueAsString(error)).contains("\"isError\":true");
    }

    @Test
    void enumsSerializeToWireValues() throws Exception {
        assertThat(mapper.writeValueAsString(com.pijava.mcp.protocol.jsonrpc.JsonRpcErrorCode.PARSE_ERROR))
                .isEqualTo("-32700");
        assertThat(mapper.writeValueAsString(ToolExecution.TaskSupport.OPTIONAL))
                .isEqualTo("\"optional\"");
    }
}
