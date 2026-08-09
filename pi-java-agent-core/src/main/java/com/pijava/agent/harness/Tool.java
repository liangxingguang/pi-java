package com.pijava.agent.harness;

import java.util.Map;

/**
 * A tool that the agent can invoke.
 *
 * <p>Tools are registered with the agent and become available to the
 * LLM via function-calling. Each tool has a name, description, JSON
 * Schema for its input, and an execution method.</p>
 */
public interface Tool {

    /** Unique tool name (e.g. "bash", "read", "write"). */
    String name();

    /** Human-readable description shown to the LLM. */
    String description();

    /** JSON Schema describing the tool's input parameters. */
    Map<String, Object> inputSchema();

    /**
     * Execute the tool with the given arguments.
     *
     * @param arguments tool arguments as a JSON-compatible map
     * @return the tool result
     * @throws Exception if the tool fails
     */
    ToolResult execute(Map<String, Object> arguments) throws Exception;

    /**
     * The result of a tool execution.
     *
     * @param content the result text or structured data
     * @param isError {@code true} if the execution failed
     */
    record ToolResult(String content, boolean isError) {
        /** Create a successful result. */
        public static ToolResult success(String content) {
            return new ToolResult(content, false);
        }

        /** Create an error result. */
        public static ToolResult error(String content) {
            return new ToolResult(content, true);
        }
    }
}
