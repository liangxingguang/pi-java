package com.pijava.mcp.fixture;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.pijava.mcp.McpJson;
import com.pijava.mcp.protocol.McpVersion;

/**
 * MCP server fixture that answers initialize then refuses to die: a
 * non-removable shutdown hook ignores termination, and a grandchild process
 * is spawned (the stubborn-server.mjs equivalent).
 */
public final class StubbornFixtureServer {

    private StubbornFixtureServer() {
    }

    /** Entry point. */
    public static void main(String[] args) throws Exception {
        var javaBin = ProcessHandle.current().info().command().orElseThrow();
        var grandchild = new ProcessBuilder(javaBin, "-cp",
                System.getProperty("java.class.path"),
                IdleProcess.class.getName())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        System.err.println("grandchild " + grandchild.pid());
        System.err.flush();

        // Ignore SIGTERM-equivalent shutdown: a hook that never returns.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException ignored) {
            }
        }));

        var mapper = McpJson.mapper();
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            var line = reader.readLine();
            if (line == null) {
                // stdin closed: refuse to exit, wait for the kill.
                Thread.sleep(60_000);
            }
            var message = mapper.readTree(line);
            var id = message.get("id");
            if (id == null) {
                continue;
            }
            var method = message.get("method").asText();
            Object result = switch (method) {
                case "initialize" -> java.util.Map.of(
                        "protocolVersion", McpVersion.LATEST,
                        "capabilities", java.util.Map.of(),
                        "serverInfo", java.util.Map.of(
                                "name", "stubborn", "version", "1.0.0"));
                default -> java.util.Map.of();
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
}
