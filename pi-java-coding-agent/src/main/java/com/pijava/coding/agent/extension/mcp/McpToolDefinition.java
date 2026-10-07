package com.pijava.coding.agent.extension.mcp;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.mcp.McpRequestOptions;
import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.protocol.McpTool;
import com.pijava.mcp.protocol.ProgressNotification;
import com.pijava.mcp.runtime.McpToolCaller;

/**
 * One MCP tool as something the agent can run (pi {@code createMcpToolDefinition},
 * {@code tools.ts:260-299}).
 *
 * <p>{@code AgentTool} carries no {@code exposure}, {@code namespace}, {@code outputSchema}, or
 * {@code annotations}; pi's {@code ToolDefinition} does, and their consumers are not ported yet,
 * so the adapter computes them here and the wiring package decides what to activate.</p>
 *
 * @param name the tool name the model sees, already sanitized
 * @param label {@code <server>/<tool>}, for the UI
 * @param description what the model is told the tool does
 * @param parameters the input schema, completed for providers
 * @param outputSchema the {@code CallToolResult} schema a codemode script sees
 * @param exposure where the tool is exposed
 * @param namespace the group this tool belongs to, such as {@code mcp__docs}
 * @param annotations the tool's boolean hints, when it has any
 * @param agent the runnable tool
 */
public record McpToolDefinition(
        String name,
        String label,
        String description,
        Map<String, Object> parameters,
        Map<String, Object> outputSchema,
        McpToolExposure exposure,
        @Nullable String namespace,
        @Nullable Annotations annotations,
        AgentTool<Map<String, Object>, McpToolDetails> agent) {

    /** The boolean hints of an MCP tool's annotations ({@code types.ts:515-525}). */
    public record Annotations(
            @Nullable Boolean readOnlyHint,
            @Nullable Boolean destructiveHint,
            @Nullable Boolean idempotentHint,
            @Nullable Boolean openWorldHint) {
    }

    /** Where a runner gets the caller of one server. */
    @FunctionalInterface
    public interface CallerSource {

        /**
         * The caller for this server, connecting if needed.
         *
         * @return the caller
         * @throws Exception when the server cannot be reached
         */
        McpToolCaller get() throws Exception;
    }

    /**
     * What one tool definition is built from (pi's options object, {@code tools.ts:260-269}).
     *
     * @param server the MCP server name
     * @param tool the tool the server offers
     * @param name the sanitized name the model sees
     * @param exposure the server's exposure for this tool
     * @param namespace the group this tool belongs to
     * @param timeoutMs per-request timeout
     * @param getClient the caller, read on every call so a dropped connection reconnects
     * @param readableResources whether {@link McpResultContent#READ_MCP_RESOURCE_TOOL} can read
     *                          the server's resources, which resource links then name
     */
    public record Options(
            String server,
            McpTool tool,
            String name,
            McpExposure exposure,
            String namespace,
            long timeoutMs,
            CallerSource getClient,
            @Nullable BooleanSupplier readableResources) {
    }

    /**
     * Build one tool.
     *
     * @param options what it is built from
     * @return the definition
     */
    public static McpToolDefinition create(Options options) {
        var tool = options.tool();
        var annotationTitle = tool.annotations() == null ? null : tool.annotations().title();
        var title = tool.title() != null ? tool.title() : annotationTitle;
        var description = tool.description() == null || tool.description().isBlank()
                ? title != null ? title : "MCP tool " + tool.name() + " from server " + options.server()
                : tool.description().strip();
        var label = options.server() + "/" + tool.name();
        var parameters = McpResultSchema.toParameters(tool.inputSchema());
        return new McpToolDefinition(
                options.name(),
                label,
                description,
                parameters,
                McpResultSchema.createMcpResultSchema(tool.outputSchema()),
                McpToolExposure.of(options.exposure()),
                options.namespace(),
                McpResultSchema.toAnnotations(tool),
                new McpAgentTool(options, label, description, parameters));
    }

    /** The runnable half. */
    private static final class McpAgentTool implements AgentTool<Map<String, Object>, McpToolDetails> {

        private final Options options;
        private final String label;
        private final String description;
        private final Map<String, Object> parameters;

        private McpAgentTool(Options options, String label, String description,
                             Map<String, Object> parameters) {
            this.options = options;
            this.label = label;
            this.description = description;
            this.parameters = parameters;
        }

        @Override
        public String name() {
            return options.name();
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Map<String, Object> inputSchema() {
            return parameters;
        }

        @Override
        public ExecutionMode executionMode() {
            // pi's MCP tools declare no executionMode, and a tool without one is not sequential
            // (agent-loop.ts:368), so they batch in parallel.
            return new ExecutionMode.Parallel();
        }

        @Override
        public ToolResult<McpToolDetails> execute(
                String toolCallId, Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<McpToolDetails> onUpdate, ToolContext context) throws Exception {
            var caller = options.getClient().get();
            var progress = onUpdate == null ? null
                    : (Consumer<ProgressNotification>) notification ->
                            onUpdate.onUpdate(progressResult(notification));
            var result = caller.callTool(options.tool().name(),
                            params == null ? Map.of() : params,
                            new McpRequestOptions(signal, options.timeoutMs(), progress))
                    .get();
            return McpResultConverter.convert(options.server(), options.tool().name(), result,
                    new McpResultContent.Options(null, readable()));
        }

        private boolean readable() {
            return options.readableResources() != null && options.readableResources().getAsBoolean();
        }

        /**
         * pi's progress text: the server's message, or {@code Progress <n>[/<total>]}
         * ({@code tools.ts:290-293}).
         */
        private ToolResult<McpToolDetails> progressResult(ProgressNotification notification) {
            var total = notification.total() == null ? "" : "/" + number(notification.total());
            var text = notification.message() != null ? notification.message()
                    : "Progress " + number(notification.progress()) + total;
            return new ToolResult<>(
                    List.of(new ContentBlock.TextContent(text)),
                    McpToolDetails.of(options.server(), options.tool().name()),
                    null, false, List.of());
        }

        /** {@code String(value)} for a JavaScript number: no {@code .0} on integers. */
        private static String number(double value) {
            if (!Double.isInfinite(value) && value == Math.rint(value) && Math.abs(value) < 1e21) {
                return Long.toString((long) value);
            }
            return Double.toString(value);
        }
    }
}
