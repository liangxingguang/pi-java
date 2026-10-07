package com.pijava.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.protocol.InitializeResult;
import com.pijava.mcp.protocol.content.CallToolResult;
import com.pijava.mcp.protocol.ReadResourceResult;
import com.pijava.mcp.protocol.jsonrpc.JsonRpcErrorCode;
import com.pijava.mcp.protocol.jsonrpc.McpError;

/**
 * Structural validation of server results ({@code client.ts:72-151}).
 */
final class McpClientValidators {

    /** One validated page: raw items and an optional cursor. */
    record Page(List<Map<String, Object>> items, @Nullable String nextCursor) {
    }

    private McpClientValidators() {
    }

    /** {@code client.ts:72-85} */
    static InitializeResult validateInitializeResult(Object value) {
        if (!(value instanceof Map<?, ?> map)
                || !(map.get("protocolVersion") instanceof String)
                || !isObject(map.get("capabilities"))
                || !isObject(map.get("serverInfo"))
                || !(map.get("serverInfo") instanceof Map<?, ?> info)
                || !(info.get("name") instanceof String)
                || !(info.get("version") instanceof String)
                || (map.get("instructions") != null
                        && !(map.get("instructions") instanceof String))) {
            throw new McpError(JsonRpcErrorCode.INVALID_REQUEST, "Invalid MCP initialize result");
        }
        return McpJson.mapper().convertValue(map, InitializeResult.class);
    }

    /** {@code client.ts:91-107} */
    static Page validateListPage(String method, String key, Object value,
                                 Predicate<Map<String, Object>> isItem) {
        if (!(value instanceof Map<?, ?> map) || !(map.get(key) instanceof List<?> rawItems)) {
            throw invalid("Invalid MCP " + method + " result");
        }
        var items = new ArrayList<Map<String, Object>>();
        for (var raw : rawItems) {
            if (!isObject(raw)) {
                throw invalid("Invalid entry in MCP " + method + " result");
            }
            // Jackson/JSON objects parse to Map<String,Object>; raw is verified isObject above.
            @SuppressWarnings("unchecked")
            var item = (Map<String, Object>) raw;
            if (!isItem.test(item)) {
                throw invalid("Invalid entry in MCP " + method + " result");
            }
            items.add(item);
        }
        // Some servers end pagination with null or "" instead of omitting the cursor.
        var rawCursor = map.get("nextCursor");
        if (rawCursor == null || "".equals(rawCursor)) {
            return new Page(items, null);
        }
        if (!(rawCursor instanceof String cursor)) {
            throw invalid("Invalid MCP " + method + " cursor");
        }
        return new Page(items, cursor);
    }

    /** {@code client.ts:128-140} */
    static ReadResourceResult validateReadResourceResult(Object value) {
        if (!(value instanceof Map<?, ?> map) || !(map.get("contents") instanceof List<?> rawContents)) {
            throw invalid("Invalid MCP resources/read result");
        }
        for (var raw : rawContents) {
            if (!isObject(raw)) {
                throw invalid("Invalid contents in MCP resources/read result");
            }
            var contents = (Map<?, ?>) raw;
            if (!(contents.get("uri") instanceof String)
                    || (!(contents.get("text") instanceof String)
                            && !(contents.get("blob") instanceof String))) {
                throw invalid("Invalid contents in MCP resources/read result");
            }
        }
        return McpJson.mapper().convertValue(map, ReadResourceResult.class);
    }

    /** {@code client.ts:142-151} */
    static CallToolResult validateCallToolResult(Object value) {
        if (!(value instanceof Map<?, ?> map)
                || (map.get("content") != null && !(map.get("content") instanceof List<?>))
                || (map.get("structuredContent") != null && !isObject(map.get("structuredContent")))) {
            throw new McpError(JsonRpcErrorCode.INVALID_REQUEST, "Invalid MCP tools/call result");
        }
        var normalized = map;
        if (map.get("content") == null) {
            // content is required by the spec, but the SDK defaults it too.
            var copy = new java.util.LinkedHashMap<String, Object>();
            map.forEach((k, v) -> copy.put((String) k, v));
            copy.put("content", List.of());
            normalized = copy;
        }
        return McpJson.mapper().convertValue(normalized, CallToolResult.class);
    }

    /** {@code client.ts:109}: name string and inputSchema object. */
    static boolean isTool(Map<String, Object> tool) {
        return tool.get("name") instanceof String && isObject(tool.get("inputSchema"));
    }

    /** {@code client.ts:111-112}: uri string; name, when present, a string. */
    static boolean isResource(Map<String, Object> resource) {
        return resource.get("uri") instanceof String
                && (resource.get("name") == null || resource.get("name") instanceof String);
    }

    /** {@code client.ts:113-114}: uriTemplate string; name, when present, a string. */
    static boolean isResourceTemplate(Map<String, Object> template) {
        return template.get("uriTemplate") instanceof String
                && (template.get("name") == null || template.get("name") instanceof String);
    }

    private static boolean isObject(@Nullable Object value) {
        return value instanceof Map<?, ?>;
    }

    private static McpError invalid(String message) {
        return new McpError(JsonRpcErrorCode.INVALID_REQUEST, message);
    }
}
