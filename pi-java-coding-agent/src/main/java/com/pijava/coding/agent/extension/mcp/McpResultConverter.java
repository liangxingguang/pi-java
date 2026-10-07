package com.pijava.coding.agent.extension.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.ContentBlock;
import com.pijava.mcp.McpJson;
import com.pijava.mcp.protocol.content.CallToolResult;
import com.pijava.mcp.protocol.content.McpContentBlock;
import com.pijava.mcp.protocol.content.McpContents;

/**
 * One MCP tool result as a tool result for the model (pi {@code convertMcpResult},
 * {@code tools.ts:213-234}).
 *
 * <p>An {@code isError} result becomes an error result — the harness reads
 * {@link ToolResult#isError()} — that keeps the structured result.</p>
 */
public final class McpResultConverter {

    private McpResultConverter() {
    }

    /**
     * Convert one result.
     *
     * @param server the MCP server the result came from
     * @param tool the tool name as the server offers it
     * @param result the server's result
     * @param options how to save what is too big to show, and whether resources are readable
     * @return the tool result
     */
    public static ToolResult<McpToolDetails> convert(String server, String tool,
                                                     CallToolResult result,
                                                     McpResultContent.Options options) {
        var blocks = result.content() == null ? List.<McpContentBlock>of() : result.content();
        // Without content blocks, toLlmContent falls back to the structured content as JSON.
        var converted = new ArrayList<ContentBlock>(blocks.isEmpty()
                ? toModelContent(result)
                : McpResultContent.toModelContent(server, blocks, options));
        if (Boolean.TRUE.equals(result.error()) && McpResultContent.textOf(converted).isEmpty()) {
            converted.add(new ContentBlock.TextContent(
                    "MCP tool " + server + "/" + tool + " returned an error"));
        }
        var limited = McpResultContent.limit(List.copyOf(converted), options);
        return new ToolResult<>(
                limited.content(),
                new McpToolDetails(server, tool, limited.fullOutputPath()),
                null,
                false,
                List.of(),
                Boolean.TRUE.equals(result.error()),
                withoutMeta(result));
    }

    private static List<ContentBlock> toModelContent(CallToolResult result) {
        return McpContents.toLlmContent(result).stream().map(McpResultContent::toBlock).toList();
    }

    /**
     * The whole {@code CallToolResult} without {@code _meta} — what a codemode script receives
     * ({@code tools.ts:227}).
     */
    private static Map<String, Object> withoutMeta(CallToolResult result) {
        var out = new LinkedHashMap<String, Object>();
        McpJson.mapper().valueToTree(result).properties().forEach(entry -> {
            if (!"_meta".equals(entry.getKey())) {
                out.put(entry.getKey(), McpJson.mapper().convertValue(entry.getValue(), Object.class));
            }
        });
        return out;
    }
}
