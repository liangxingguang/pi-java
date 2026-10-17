package com.pijava.agent.tool.builtin;
import com.pijava.ai.AbortSignal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ShellOptions;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.agent.tool.TruncationUtils;
import com.pijava.ai.api.JsonSchemaSampling;
import com.pijava.ai.api.StrictMode;
import com.pijava.ai.message.ContentBlock;

/**
 * Shell command execution tool.
 * Aligned with pi's {@code createBashTool}（anchor ea448f454，批 3/docs/22 §1）。
 *
 * <p>Schema: { command: String, timeout?: Number } —— timeout 小数秒、无默认、
 * 上限 2147483.647 s（{@link #resolveTimeoutMs}）。描述/schema/snippet/截断 notice
 * 全部逐字；非零退出 resolve {@code isError:true} 而非 throw（bash.ts:355-364）。</p>
 *
 * <h4>Security model (Phase 2b)</h4>
 * Phase 2b runs commands directly via {@code ProcessBuilder} with no sandbox.
 * The {@code ApprovalHandler} callback (§2.4) is the primary safeguard.
 */
public final class BashTool {
    /** pi {@code bash.ts:22}：{@code 2_147_483_647} ms（≙ 2147483.647 s）。 */
    private static final long MAX_TIMEOUT_MS = 2_147_483_647L;

    /** pi bash.ts:47（{@code bashToolSystemPromptContribution.guidelines}）逐字。 */
    private static final List<String> PI_GUIDELINES =
        List.of("You can inspect PI_* environment variables for current model and session details.");

    /** pi bash.ts:194-198 的 delete×5（先删后设；无论开关与否恒删，条件只门设）。 */
    private static final java.util.Set<String> PI_ENV_KEYS = java.util.Set.of(
        "PI_SESSION_ID", "PI_SESSION_FILE", "PI_PROVIDER", "PI_MODEL", "PI_REASONING_LEVEL");

    private BashTool() {}

    public record BashInput(String command, Optional<Double> timeoutSeconds) {}
    public record BashDetails(TruncationUtils.TruncationResult truncation, String fullOutputPath) {}

    /** Create the bash tool with no command prefix. */
    public static AgentTool<BashInput, BashDetails> create() {
        return create(null);
    }

    /** Create the bash tool with an optional command prefix. */
    public static AgentTool<BashInput, BashDetails> create(String commandPrefix) {
        // pi bash.ts:250：exposeSessionEnvironment 默认 true。
        return create(commandPrefix, true);
    }

    /**
     * Create the bash tool（pi {@code createBashTool} 全参形）。
     *
     * @param exposeSessionEnvironment PI_* 注入开关（pi bash.ts:221-222；与
     *        {@link #promptGuidelines} 同一闸门 —— 说了就必须给，不给就不说，:257）
     */
    public static AgentTool<BashInput, BashDetails> create(
            String commandPrefix, boolean exposeSessionEnvironment) {
        return new AgentTool<>() {
            @Override public String name() { return "bash"; }
            @Override public String label() { return "bash"; }
            @Override public String description() {
                // pi bash.ts:261-263 逐字（config.shellName 对 bash 恒 "bash"；
                // pi 在 Windows 跑 Git Bash 但不告诉模型 —— 无 shellNote 段）。
                return "Execute a bash command in the current working directory. "
                    + "Returns stdout and stderr. Output is truncated to last "
                    + TruncationUtils.DEFAULT_MAX_LINES + " lines or "
                    + (TruncationUtils.DEFAULT_MAX_BYTES / 1024) + "KB (whichever is hit first). "
                    + "If truncated, full output is saved to a temp file. "
                    + "Optionally provide a timeout in seconds.";
            }
            @Override public String promptSnippet() {
                // pi bashToolSystemPromptContribution.snippet（bash.ts:45）逐字。
                return "Execute bash commands (ls, grep, find, etc.)";
            }
            @Override public List<String> promptGuidelines() {
                // pi bash.ts:257：与注入同一开关门控 —— 说了就必须给，不给就不说。
                return exposeSessionEnvironment ? PI_GUIDELINES : List.of();
            }
            @Override
            public Map<String, Object> inputSchema() {
                // pi bash.ts:40-43 逐字。
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "command", Map.of("type", "string", "description", "Shell command to execute"),
                        "timeout", Map.of("type", "number", "description",
                        "Timeout in seconds (optional, no default timeout)")
                    ),
                    "required", List.of("command")
                );
            }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }

            // 原 docs/66：pi 的 bash 默认 strict-prefer。
            @Override public JsonSchemaSampling constrainedSampling() {
                return new JsonSchemaSampling(StrictMode.PREFER);
            }

            @Override
            public BashInput prepareArguments(Map<String, Object> raw) {
                String command = (String) raw.get("command");
                if (command == null && raw.get("_raw") instanceof String rawJson) {
                    // Stream adapters fall back to {"_raw": "<raw json>"} when the
                    // model tool arguments are truncated or malformed; recover the
                    // command so the call still runs instead of silently failing.
                    command = extractCommand(rawJson);
                }
                Optional<Double> timeout = Optional.empty();
                Object timeoutObj = raw.get("timeout");
                if (timeoutObj instanceof Number n) {
                    // pi Type.Number：小数秒保留（旧 longValue() 会把 0.5 截成 0）。
                    timeout = Optional.of(n.doubleValue());
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
                long timeoutMs = resolveTimeoutMs(params.timeoutSeconds().orElse(null));

                // 包⑧（原 docs/35）：流式部分结果。pi 侧只有 bash 一家真的流式
                // （bash.ts:265/:297），其余六个内置工具声明为 `_onUpdate?`（故意未使用）。
                // onUpdate 为 null 时不建发射器 —— 与 pi 的 `if (onUpdate)` 同。
                BashUpdateEmitter emitter = onUpdate == null ? null
                    : new BashUpdateEmitter(onUpdate, BashUpdateEmitter.THROTTLE_MS,
                        System::nanoTime, BashUpdateEmitter.defaultScheduler());
                try {
                    if (emitter != null) {
                        // 起手那条空载荷：在任何进程被拉起**之前**（bash.ts:296-298）。
                        emitter.start();
                    }
                    var options = new ShellOptions(
                        context.cwd(), sessionEnv(context, exposeSessionEnvironment), true,
                        timeoutMs < 0 ? OptionalLong.empty() : OptionalLong.of(timeoutMs),
                        signal,
                        emitter,
                        PI_ENV_KEYS // pi 先删后设：无论开关恒删 5 键，条件只门设（:194-208）
                    );
                    var shellResult = context.shell().execute(command, options);
                    if (emitter != null) {
                        // 收尾补冲一次（pi 的 finishOutput，bash.ts:306-314）。
                        emitter.flush();
                    }

                    // pi bash.ts:337-366 三路收尾。
                    if (shellResult.timedOut()) {
                        var snap = formatOutput(shellResult.output(), "");
                        throw new RuntimeException(appendStatus(snap.text(),
                            "Command timed out after "
                                + jsNumber(params.timeoutSeconds().orElseThrow()) + " seconds"));
                    }
                    var snap = formatOutput(shellResult.output(), "(no output)");
                    if (shellResult.exitCode() != 0) {
                        // pi resolve(isError:true)，不 reject（bash.ts:355-364）。
                        return new ToolResult<>(
                            List.of(new ContentBlock.TextContent(appendStatus(snap.text(),
                                "Command exited with code " + shellResult.exitCode()))),
                            snap.details(), null, false, List.of(), true, null);
                    }
                    return new ToolResult<>(List.of(new ContentBlock.TextContent(snap.text())),
                        snap.details(), null, false, List.of());
                } finally {
                    if (emitter != null) {
                        // pi 的 finally 只清定时器、不补发（bash.ts:367-369）。
                        emitter.disarm();
                    }
                }
            }
        };
    }

    /**
     * pi {@code resolveSpawnContext} 的组 env（bash.ts:199-208）：开关开且 ctx 在 ⇒
     * 条件设 PI_*（sessionId 恒设，sessionFile/model/thinkingLevel 各自 if 门控）。
     */
    private static Map<String, String> sessionEnv(ToolContext context, boolean expose) {
        var env = new java.util.LinkedHashMap<>(context.env());
        if (expose) {
            com.pijava.agent.tool.SessionEnvironment se = context.sessionEnvironment();
            if (se != null) {
                env.put("PI_SESSION_ID", se.sessionId());
                if (se.sessionFile() != null) env.put("PI_SESSION_FILE", se.sessionFile());
                if (se.provider() != null) env.put("PI_PROVIDER", se.provider());
                if (se.model() != null) env.put("PI_MODEL", se.model());
                if (se.thinkingLevel() != null) env.put("PI_REASONING_LEVEL", se.thinkingLevel());
            }
        }
        return env;
    }

    /** pi {@code resolveTimeoutMs}（bash.ts:27-37）：{@code null} ≙ undefined ⇒ 返回 {@code -1} 表无超时。 */
    private static long resolveTimeoutMs(Double timeout) {
        if (timeout == null) {
            return -1;
        }
        if (!Double.isFinite(timeout) || timeout <= 0) {
            throw new IllegalArgumentException(
                "Invalid timeout: must be a finite number of seconds");
        }
        double timeoutMs = timeout * 1000;
        if (timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException(
                "Invalid timeout: maximum is " + MAX_TIMEOUT_MS / 1000.0 + " seconds");
        }
        return (long) timeoutMs;
    }

    /**
     * JS {@code String(number)}：整值去小数点（30.0→"30"），其余原样（0.5→"0.5"）。
     * pi bash.ts:350 的超时回显值。
     */
    private static String jsNumber(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15
            ? Long.toString((long) v) : Double.toString(v);
    }

    /** pi {@code formatOutput}（bash.ts:316-333）：truncateTail＋截断时落 temp 文件＋三分支 notice。 */
    private static FormatOut formatOutput(String fullOutput, String emptyText) throws IOException {
        var truncation = TruncationUtils.truncateTail(fullOutput);
        String text = truncation.content().isEmpty() ? emptyText : truncation.content();
        BashDetails details = null;
        if (truncation.truncated()) {
            String tempPath = saveTempFile(fullOutput);
            details = new BashDetails(truncation, tempPath);
            int startLine = truncation.totalLines() - truncation.outputLines() + 1;
            int endLine = truncation.totalLines();
            if (truncation.lastLinePartial()) {
                text += "\n\n[Showing last " + TruncationUtils.formatSize(truncation.outputBytes())
                    + " of line " + endLine + " (line is " + lastLineSize(fullOutput) + ")."
                    + " Full output: " + tempPath + "]";
            } else if ("lines".equals(truncation.truncatedBy())) {
                text += "\n\n[Showing lines " + startLine + "-" + endLine
                    + " of " + truncation.totalLines() + ". Full output: " + tempPath + "]";
            } else {
                text += "\n\n[Showing lines " + startLine + "-" + endLine
                    + " of " + truncation.totalLines()
                    + " (" + TruncationUtils.formatSize(TruncationUtils.DEFAULT_MAX_BYTES)
                    + " limit). Full output: " + tempPath + "]";
            }
        }
        return new FormatOut(text, details);
    }

    private record FormatOut(String text, BashDetails details) {}

    /** pi {@code appendStatus}（bash.ts:335）。 */
    private static String appendStatus(String text, String status) {
        return (text.isEmpty() ? "" : text + "\n\n") + status;
    }

    /** pi {@code output.getLastLineBytes()}：全量输出最后一行的 UTF-8 字节数。 */
    private static String lastLineSize(String fullOutput) {
        int idx = fullOutput.lastIndexOf('\n');
        String last = idx < 0 ? fullOutput : fullOutput.substring(idx + 1);
        return TruncationUtils.formatSize(last.getBytes(StandardCharsets.UTF_8).length);
    }

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

    /**
     * Save the full (untruncated) output to a temp file.
     * pi {@code utils/output-files.ts:17-20}：{@code $TMPDIR/pi-bash-<hex>.log}
     * （前缀 bash.ts:435 {@code tempFilePrefix: "pi-bash"}；POSIX 0600 与 pi 同，
     * 随机段形状差 {@code <16hex>} vs JDK 长数不钉，docs/22 §1.3）。
     *
     * @return the absolute path to the temp file
     * @throws IOException if the temp file cannot be created or written
     */
    private static String saveTempFile(String output) throws IOException {
        Path tempFile = Files.createTempFile("pi-bash-", ".log");
        tempFile.toFile().deleteOnExit();
        Files.writeString(tempFile, output);
        return tempFile.toAbsolutePath().toString();
    }
}
