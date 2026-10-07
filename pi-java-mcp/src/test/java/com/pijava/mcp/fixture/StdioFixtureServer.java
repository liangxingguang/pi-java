package com.pijava.mcp.fixture;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.pijava.mcp.McpJson;
import com.pijava.mcp.protocol.McpVersion;

/**
 * Minimal line-framed MCP server fixture (the stdio-server.mjs equivalent).
 */
public final class StdioFixtureServer {

    private StdioFixtureServer() {
    }

    /** Entry point. */
    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable failure) {
            System.err.println("FIXM TOP: " + failure);
            failure.printStackTrace(System.err);
            System.err.flush();
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        System.err.println("stdio fixture ready");
        System.err.flush();
        var mapper = McpJson.mapper();
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            var line = reader.readLine();
            var message = line == null ? null : mapper.readTree(line);
            if (message == null) {
                return;
            }
            handleMessage(mapper, message);
        }
    }

    private static void handleMessage(
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            com.fasterxml.jackson.databind.JsonNode message) throws Exception {
        var method = message.get("method").asText();
        var id = message.get("id");
        // Notifications (no id) carry no response; ignore them.
        if (id == null) {
            return;
        }
        Object result = switch (method) {
                case "initialize" -> java.util.Map.of(
                        "protocolVersion", McpVersion.LATEST,
                        "capabilities", java.util.Map.of("tools", java.util.Map.of()),
                        "serverInfo", java.util.Map.of("name", "fixture", "version", "1.0.0"));
                case "tools/list" -> java.util.Map.of("tools", java.util.List.of(java.util.Map.of(
                        "name", "echo",
                        "inputSchema", java.util.Map.of("type", "object"))));
                case "tools/call" -> {
                    var text = message.get("params").get("arguments").get("text").asText();
                    yield java.util.Map.of("content", java.util.List.of(java.util.Map.of(
                            "type", "text", "text", text)));
                }
                default -> throw new IllegalStateException("unexpected method " + method);
            };
            var wire = new java.util.LinkedHashMap<String, Object>();
            wire.put("jsonrpc", "2.0");
            wire.put("id", mapper.treeToValue(id, Object.class));
            wire.put("result", result);
            System.out.write(mapper.writeValueAsBytes(wire));
            System.out.write('\n');
            System.out.flush();
    }
}
