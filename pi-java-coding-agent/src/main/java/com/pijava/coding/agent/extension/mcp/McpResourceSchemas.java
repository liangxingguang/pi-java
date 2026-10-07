package com.pijava.coding.agent.extension.mcp;

import java.util.List;
import java.util.Map;

/**
 * The JSON schemas of the three resource tools (pi {@code resources.ts:61-168}).
 */
final class McpResourceSchemas {

    static final Map<String, Object> LIST_PARAMETERS = Map.of(
            "type", "object",
            "properties", Map.of(
                    "server", stringProperty(
                            "MCP server name. Omit to list every server with resources."),
                    "cursor", stringProperty(
                            "Opaque cursor from a previous call with the same server;"
                                    + " omit for the first page.")),
            "additionalProperties", false);

    static final Map<String, Object> READ_PARAMETERS = Map.of(
            "type", "object",
            "properties", Map.of(
                    "server", stringProperty("MCP server name exactly as configured. Must match the"
                            + " 'server' field returned by list_mcp_resources."),
                    "uri", stringProperty("Resource URI to read. Must be one of the URIs returned"
                            + " by list_mcp_resources.")),
            "required", List.of("server", "uri"),
            "additionalProperties", false);

    static final Map<String, Object> LISTING_ERRORS = Map.of(
            "type", "array",
            "description", "Servers that could not be listed",
            "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "server", Map.of("type", "string"),
                            "error", Map.of("type", "string")),
                    "required", List.of("server", "error")));

    static final Map<String, Object> LIST_OUTPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "server", Map.of("type", "string"),
                    "resources", Map.of(
                            "type", "array",
                            "items", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "server", Map.of("type", "string"),
                                            "uri", Map.of("type", "string"),
                                            "name", Map.of("type", "string"),
                                            "title", Map.of("type", "string"),
                                            "description", Map.of("type", "string"),
                                            "mimeType", Map.of("type", "string"),
                                            "size", Map.of("type", "number")),
                                    "required", List.of("server", "uri", "name"))),
                    "nextCursor", Map.of("type", "string"),
                    "errors", LISTING_ERRORS),
            "required", List.of("resources"));

    static final Map<String, Object> LIST_TEMPLATES_OUTPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "server", Map.of("type", "string"),
                    "resourceTemplates", Map.of(
                            "type", "array",
                            "items", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "server", Map.of("type", "string"),
                                            "uriTemplate", Map.of("type", "string",
                                                    "description", "RFC 6570 URI template"),
                                            "name", Map.of("type", "string"),
                                            "title", Map.of("type", "string"),
                                            "description", Map.of("type", "string"),
                                            "mimeType", Map.of("type", "string")),
                                    "required", List.of("server", "uriTemplate", "name"))),
                    "nextCursor", Map.of("type", "string"),
                    "errors", LISTING_ERRORS),
            "required", List.of("resourceTemplates"));

    static final Map<String, Object> READ_OUTPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "server", Map.of("type", "string"),
                    "uri", Map.of("type", "string"),
                    "contents", Map.of(
                            "type", "array",
                            "items", Map.of("anyOf", List.of(
                                    Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "uri", Map.of("type", "string"),
                                                    "mimeType", Map.of("type", "string"),
                                                    "text", Map.of("type", "string")),
                                            "required", List.of("uri", "text")),
                                    Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "uri", Map.of("type", "string"),
                                                    "mimeType", Map.of("type", "string"),
                                                    "blob", Map.of("type", "string",
                                                            "description", "base64")),
                                            "required", List.of("uri", "blob")))))),
            "required", List.of("server", "uri", "contents"));

    private McpResourceSchemas() {
    }

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }
}
