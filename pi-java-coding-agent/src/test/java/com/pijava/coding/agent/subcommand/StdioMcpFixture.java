package com.pijava.coding.agent.subcommand;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A minimal MCP stdio server for the {@code mcp list} fixtures: answers {@code initialize},
 * {@code tools/list} and {@code resources/list}, and ignores everything else.
 *
 * <p>It is launched as a child process the way a real stdio server is, so the fixtures exercise
 * the transport rather than a stub.</p>
 */
public final class StdioMcpFixture {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Set by the {@code no-tools} argument, so the plural tool count is reachable. */
    private static boolean NO_TOOLS;

    private StdioMcpFixture() {
    }

    /**
     * Run the fixture until stdin closes.
     *
     * @param args {@code no-tools} makes it answer with no tools
     * @throws Exception when the streams fail
     */
    public static void main(String[] args) throws Exception {
        NO_TOOLS = List.of(args).contains("no-tools");
        var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        try (var reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                var message = (Map<String, Object>) MAPPER.readValue(line, Map.class);
                var id = message.get("id");
                if (id == null) {
                    continue;
                }
                out.println(MAPPER.writeValueAsString(answer(String.valueOf(message.get("method")), id)));
            }
        }
        new CountDownLatch(0).await();
    }

    private static boolean noTools() {
        return NO_TOOLS;
    }

    private static Map<String, Object> answer(String method, Object id) {
        var response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", switch (method) {
            case "initialize" -> initialize();
            case "tools/list" -> Map.of("tools", noTools() ? List.of() : List.of(
                    Map.of("name", "echo", "description", "Echoes", "inputSchema", Map.of("type", "object"))));
            case "resources/list" -> Map.of("resources", List.of(
                    Map.of("uri", "file:///a.txt", "name", "a")));
            case "resources/templates/list" -> Map.of("resourceTemplates", List.of(
                    Map.of("uriTemplate", "file:///{id}", "name", "t")));
            default -> Map.of();
        });
        return response;
    }

    private static Map<String, Object> initialize() {
        return Map.of(
                "protocolVersion", "2025-11-25",
                "capabilities", Map.of(
                        "tools", Map.of("listChanged", true),
                        "resources", Map.of("listChanged", true)),
                "serverInfo", Map.of("name", "mcp-cli-fixture", "version", "1.0"));
    }
}
