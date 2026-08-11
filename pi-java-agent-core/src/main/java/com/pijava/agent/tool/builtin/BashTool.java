package com.pijava.agent.tool.builtin;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.agent.tool.*;
import com.pijava.ai.message.ContentBlock;

/**
 * Shell command execution tool.
 * Aligned with pi's {@code createBashTool}.
 *
 * <p>Schema: { command: String, timeout?: Number }
 * Execution: ProcessBuilder + Virtual Threads + output capture
 * Truncation: last 2000 lines or 100KB (whichever hit first),
 *   save full output to temp file if truncated</p>
 *
 * <h4>Security model (Phase 2b)</h4>
 * Phase 2b runs commands directly via {@code ProcessBuilder} with no sandbox.
 * The {@code ApprovalHandler} callback (§2.4) is the primary safeguard.
 */
public final class BashTool {
    private static final long MAX_TIMEOUT_SECONDS = 2_147_483_647L / 1000;

    private BashTool() {}

    public record BashInput(String command, Optional<Long> timeoutSeconds) {}
    public record BashDetails(TruncationUtils.TruncationResult truncation, String fullOutputPath) {}

    public static AgentTool<BashInput, BashDetails> create() {
        return create(null);
    }

    public static AgentTool<BashInput, BashDetails> create(String commandPrefix) {
        return new AgentTool<>() {
            @Override public String name() { return "bash"; }
            @Override public String label() { return "bash"; }
            @Override public String description() {
                return "Execute a bash command in the current working directory. "
                    + "Returns stdout and stderr. Output is truncated to last "
                    + TruncationUtils.DEFAULT_MAX_LINES + " lines or "
                    + (TruncationUtils.DEFAULT_MAX_BYTES / 1024) + "KB (whichever is hit first). "
                    + "If truncated, full output is saved to a temp file. "
                    + "Optionally provide a timeout in seconds.";
            }
            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "command", Map.of("type", "string", "description", "Bash command to execute"),
                        "timeout", Map.of("type", "number", "description", "Timeout in seconds (optional)")
                    ),
                    "required", List.of("command")
                );
            }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }

            @Override
            @SuppressWarnings("unchecked")
            public BashInput prepareArguments(Map<String, Object> raw) {
                String command = (String) raw.get("command");
                Optional<Long> timeout = Optional.empty();
                Object timeoutObj = raw.get("timeout");
                if (timeoutObj instanceof Number n) {
                    timeout = Optional.of(n.longValue());
                }
                return new BashInput(command, timeout);
            }

            @Override
            public ToolResult<BashDetails> execute(String toolCallId, BashInput params,
                    AbortSignal signal, ToolUpdateCallback<BashDetails> onUpdate,
                    ToolContext context) throws Exception {
                String command = params.command();
                if (commandPrefix != null && !commandPrefix.isEmpty()) {
                    command = commandPrefix + "\n" + command;
                }
                long timeout = params.timeoutSeconds().orElse(0L);
                if (timeout > 0 && timeout > MAX_TIMEOUT_SECONDS) {
                    throw new IllegalArgumentException(
                        "Timeout exceeds maximum of " + MAX_TIMEOUT_SECONDS + " seconds");
                }

                var options = new ShellOptions(
                    context.cwd(), context.env(), true,
                    timeout > 0 ? java.util.OptionalLong.of(timeout) : java.util.OptionalLong.empty(),
                    signal
                );
                var shellResult = context.shell().execute(command, options);

                if (shellResult.timedOut()) {
                    throw new RuntimeException("Command timed out after " + timeout + " seconds");
                }

                String output = shellResult.output();
                if (output.isEmpty()) {
                    return ToolResult.success("(no output)");
                }

                var truncation = TruncationUtils.truncateTail(output);
                BashDetails details = null;
                String outputText;

                if (truncation.truncated()) {
                    details = new BashDetails(truncation, null);
                    int startLine = truncation.totalLines() - truncation.outputLines() + 1;
                    int endLine = truncation.totalLines();
                    if (truncation.lastLinePartial()) {
                        outputText = truncation.content()
                            + "\n\n[Showing last " + TruncationUtils.formatSize(truncation.outputBytes())
                            + " of line " + endLine + " (line is "
                            + TruncationUtils.formatSize(shellResult.outputBytes()) + ").]";
                    } else {
                        outputText = truncation.content()
                            + "\n\n[Showing lines " + startLine + "-" + endLine
                            + " of " + truncation.totalLines()
                            + " (" + TruncationUtils.formatSize(TruncationUtils.DEFAULT_MAX_BYTES)
                            + " limit).]";
                    }
                } else {
                    outputText = truncation.content();
                }

                if (shellResult.exitCode() != 0) {
                    throw new RuntimeException((outputText.isEmpty() ? "" : outputText + "\n\n")
                        + "Command exited with code " + shellResult.exitCode());
                }
                return new ToolResult<>(
                    List.of(new ContentBlock.TextContent(outputText)),
                    details, null, false, List.of());
            }
        };
    }
}
