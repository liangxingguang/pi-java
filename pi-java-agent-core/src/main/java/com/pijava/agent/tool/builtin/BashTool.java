package com.pijava.agent.tool.builtin;
import com.pijava.ai.AbortSignal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    /** Max timeout in seconds: {@code Long.MAX_VALUE / 1000} ≈ 2^41 ms. */
    private static final long MAX_TIMEOUT_SECONDS = Long.MAX_VALUE / 1000;

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
                return "Execute a shell command in the current working directory. "
                    + shellNote()
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
                    // Save full output to temp file
                    String tempPath = saveTempFile(output);
                    details = new BashDetails(truncation, tempPath);
                    int startLine = truncation.totalLines() - truncation.outputLines() + 1;
                    int endLine = truncation.totalLines();
                    if (truncation.lastLinePartial()) {
                        outputText = truncation.content()
                            + "\n\n[Showing last " + TruncationUtils.formatSize(truncation.outputBytes())
                            + " of line " + endLine + " (line is "
                            + TruncationUtils.formatSize(shellResult.outputBytes()) + ")."
                            + "\nFull output saved to: " + tempPath + "]";
                    } else {
                        outputText = truncation.content()
                            + "\n\n[Showing lines " + startLine + "-" + endLine
                            + " of " + truncation.totalLines()
                            + " (" + TruncationUtils.formatSize(TruncationUtils.DEFAULT_MAX_BYTES)
                            + " limit). Full output saved to: " + tempPath + "]";
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

    /**
     * Tells the model which shell actually runs the command on this host so it
     * knows bash semantics always apply (Git Bash on Windows, per pi).
     */
    private static String shellNote() {
        var os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return "On this Windows host the command runs in Git Bash (real bash), so "
                + "bash syntax such as `ls -la`, `pwd`, `cat`, and `&&` works. If bash "
                + "commands fail with 'No bash shell found', install Git for Windows "
                + "or set shellPath in settings.json. ";
        }
        return "The command runs in bash. ";
    }

    /**
     * Save the full (untruncated) output to a temp file.
     * @return the absolute path to the temp file
     * @throws IOException if the temp file cannot be created or written
     */
    private static String saveTempFile(String output) throws IOException {
        Path tempFile = Files.createTempFile("bash-output-", ".txt");
        tempFile.toFile().deleteOnExit();
        Files.writeString(tempFile, output);
        return tempFile.toAbsolutePath().toString();
    }
}
