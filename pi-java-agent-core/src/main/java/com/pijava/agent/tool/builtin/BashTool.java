package com.pijava.agent.tool.builtin;
import com.pijava.ai.AbortSignal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ShellOptions;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.agent.tool.TruncationUtils;
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
    /** Default timeout in seconds when the model omits {@code timeout} (aligned with Claude Code / Codex: 120s). */
    private static final long DEFAULT_TIMEOUT_SECONDS = 120L;
    /** Max timeout in seconds (aligned with Claude Code / pi-bash-timeout: 600s). */
    private static final long MAX_TIMEOUT_SECONDS = 600L;

    private BashTool() {}

    public record BashInput(String command, Optional<Long> timeoutSeconds) {}
    public record BashDetails(TruncationUtils.TruncationResult truncation, String fullOutputPath) {}

    /** Create the bash tool with no command prefix. */
    public static AgentTool<BashInput, BashDetails> create() {
        return create(null);
    }

    /** Create the bash tool with an optional command prefix. */
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
                    + "Optionally provide a timeout in seconds "
                    + "(default 120, max 600; pass a larger value for long-running commands).";
            }
            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "command", Map.of("type", "string", "description", "Bash command to execute"),
                        "timeout", Map.of("type", "number", "description",
                        "Timeout in seconds (optional; default 120, max 600)")
                    ),
                    "required", List.of("command")
                );
            }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }

            @Override
            public BashInput prepareArguments(Map<String, Object> raw) {
                String command = (String) raw.get("command");
                if (command == null && raw.get("_raw") instanceof String rawJson) {
                    // Stream adapters fall back to {"_raw": "<raw json>"} when the
                    // model tool arguments are truncated or malformed; recover the
                    // command so the call still runs instead of silently failing.
                    command = extractCommand(rawJson);
                }
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
                long timeout = params.timeoutSeconds().filter(t -> t > 0)
                    .orElse(DEFAULT_TIMEOUT_SECONDS);
                if (timeout > MAX_TIMEOUT_SECONDS) {
                    throw new IllegalArgumentException(
                        "Timeout exceeds maximum of " + MAX_TIMEOUT_SECONDS + " seconds");
                }

                var options = new ShellOptions(
                    context.cwd(), context.env(), true,
                    java.util.OptionalLong.of(timeout),
                    signal
                );
                var shellResult = context.shell().execute(command, options);

                if (shellResult.timedOut()) {
                    String partial = shellResult.output();
                    if (partial.length() > 2000) {
                        partial = partial.substring(0, 2000) + "\n...(truncated)";
                    }
                    throw new RuntimeException("Command timed out after " + timeout + " seconds"
                        + (partial.isBlank() ? "" : "\n\nPartial output before timeout:\n" + partial));
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
    /** Best-effort command extraction from a raw (possibly malformed) JSON argument. */
    private static String extractCommand(String rawJson) {
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_TRAILING_COMMA)
                .readValue(rawJson, Object.class);
            if (parsed instanceof Map<?, ?> map && map.get("command") instanceof String cmd) {
                return cmd;
            }
        } catch (Exception ignored) {
            // fall through to regex extraction
        }
        var matcher = java.util.regex.Pattern.compile(
            "\"command\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(rawJson);
        return matcher.find()
            ? matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\")
            : null;
    }
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
