package com.pijava.agent.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.ShellExecutor;
import com.pijava.agent.tool.ShellOptions;
import com.pijava.agent.tool.ShellResult;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.ContentBlock;

class BashToolTest {

    @TempDir Path tmp;

    // ── 超时面（pi bash.ts:22,27-37 逐字）──────────────────────────

    @Test
    void missingTimeoutMeansNoTimeout() throws Exception {
        var captured = captureShellOptions(new ShellResult("ok", 0, false, false, 1, 2));

        BashTool.create().execute("id", new BashTool.BashInput("echo hi", Optional.empty()),
            null, null, context(captured));

        assertThat(captured.get().timeoutMillis()).isEmpty();
    }

    @Test
    void fractionalTimeoutBecomesMilliseconds() throws Exception {
        var captured = captureShellOptions(new ShellResult("ok", 0, false, false, 1, 2));

        BashTool.create().execute("id", new BashTool.BashInput("echo hi", Optional.of(0.5)),
            null, null, context(captured));

        assertThat(captured.get().timeoutMillis()).hasValue(500L);
    }

    @Test
    void zeroAndNegativeTimeoutAreRejected() {
        var context = context(captureShellOptions(new ShellResult("", 0, false, false, 0, 0)));

        assertThatThrownBy(() -> BashTool.create().execute("id",
                new BashTool.BashInput("echo hi", Optional.of(0.0)), null, null, context))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid timeout: must be a finite number of seconds");
        assertThatThrownBy(() -> BashTool.create().execute("id",
                new BashTool.BashInput("echo hi", Optional.of(-3.0)), null, null, context))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid timeout: must be a finite number of seconds");
    }

    @Test
    void timeoutAbovePiMaximumIsRejected() {
        var context = context(captureShellOptions(new ShellResult("", 0, false, false, 0, 0)));

        assertThatThrownBy(() -> BashTool.create().execute("id",
                new BashTool.BashInput("echo hi", Optional.of(2147483.648)), null, null, context))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid timeout: maximum is 2147483.647 seconds");
    }

    @Test
    void timeoutAtPiMaximumIsAccepted() throws Exception {
        var captured = captureShellOptions(new ShellResult("ok", 0, false, false, 1, 2));

        BashTool.create().execute("id",
            new BashTool.BashInput("echo hi", Optional.of(2147483.647)), null, null, context(captured));

        // 2147483.647×1000 在 double 下略低于 2_147_483_647（pi 同值直接过 setTimeout）；
        // 钉「通过校验且毫秒贴顶」，不钉精确低位。
        assertThat(captured.get().timeoutMillis()).isPresent();
        assertThat(captured.get().timeoutMillis().getAsLong())
            .isBetween(2_147_483_000L, 2_147_483_647L);
    }

    @Test
    void timeoutErrorEchoesUserSecondsWithoutTrailingDotZero() {
        // pi bash.ts:347-351：回显是 err.message.split(":")[1]，即用户原始秒值。
        var context = context(captureShellOptions(new ShellResult("partial", 0, true, false, 1, 7)));

        assertThatThrownBy(() -> BashTool.create().execute("id",
                new BashTool.BashInput("sleep 99", Optional.of(30.0)), null, null, context))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("partial\n\nCommand timed out after 30 seconds");
    }

    @Test
    void timeoutErrorEchoesFractionalSecondsVerbatim() {
        var context = context(captureShellOptions(new ShellResult("", 0, true, false, 0, 0)));

        assertThatThrownBy(() -> BashTool.create().execute("id",
                new BashTool.BashInput("sleep 99", Optional.of(0.5)), null, null, context))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("Command timed out after 0.5 seconds");
    }

    // ── 截断 notice 三分支（pi bash.ts:317-330 逐字）──────────────

    @Test
    void lineTruncationNoticeHasNoByteLimitSuffix() throws Exception {
        // 3000 短行：仅触发行数窗 ⇒ 不带 (50.0KB limit)。
        var big = "x\n".repeat(3000);
        var result = runWithOutput(big);

        var text = textOf(result);
        assertThat(text).containsPattern(
            "\\[Showing lines 1001-3000 of 3000\\. Full output: .+\\]");
        assertThat(text).doesNotContain("50.0KB limit");
        assertThat(result.details().fullOutputPath())
            .contains("pi-bash-").endsWith(".log");
        assertThat(Files.readString(Path.of(result.details().fullOutputPath()))).isEqualTo(big);
    }

    @Test
    void byteTruncationNoticeQuotesTheByteLimit() throws Exception {
        // 600 行 × 100B：行数窗内但超字节窗 ⇒ (50.0KB limit) 形。
        var big = ("y".repeat(100) + "\n").repeat(600);
        var result = runWithOutput(big);

        assertThat(textOf(result)).containsPattern(
            "\\[Showing lines \\d+-600 of 600 \\(50\\.0KB limit\\)\\. Full output: .+\\]");
    }

    @Test
    void partialLastLineNoticeUsesLastLineSize() throws Exception {
        // 单条 60_000 字节长线 ⇒ lastLinePartial 分支，line size 是最后一行（唯一行）字节数。
        var big = "z".repeat(60_000);
        var result = runWithOutput(big);

        assertThat(textOf(result)).containsPattern(
            "\\[Showing last 50\\.0KB of line 1 \\(line is 58\\.6KB\\)\\. Full output: .+\\]");
    }

    // ── 终局（pi bash.ts:337-366）────────────────────────────────

    @Test
    void nonZeroExitResolvesWithIsErrorAndKeepsDetails() throws Exception {
        // pi resolve(isError:true)，不 reject（bash.ts:355-364）。
        var big = "x\n".repeat(3000);   // 截断 ⇒ details 必须保留
        var context = context(captureShellOptions(
            new ShellResult(big, 3, false, false, 3000, big.length())));

        var result = BashTool.create().execute("id",
            new BashTool.BashInput("false", Optional.empty()), null, null, context);

        assertThat(result.isError()).isTrue();
        var text = textOf(result);
        assertThat(text).endsWith("Command exited with code 3");
        assertThat(text).contains("[Showing lines 1001-3000 of 3000.");
        assertThat(result.details()).isNotNull();
        assertThat(result.details().fullOutputPath()).contains("pi-bash-");
    }

    @Test
    void emptyOutputIsNoOutput() throws Exception {
        var context = context(captureShellOptions(new ShellResult("", 0, false, false, 0, 0)));

        var result = BashTool.create().execute("id",
            new BashTool.BashInput("true", Optional.empty()), null, null, context);

        assertThat(textOf(result)).isEqualTo("(no output)");
        assertThat(result.isError()).isFalse();
    }

    // ── 模型可见契约（pi bash.ts:40-43,261-263 逐字）──────────────

    @Test
    void descriptionIsPiVerbatim() {
        assertThat(BashTool.create().description()).isEqualTo(
            "Execute a bash command in the current working directory. Returns stdout and stderr. "
                + "Output is truncated to last 2000 lines or 50KB (whichever is hit first). "
                + "If truncated, full output is saved to a temp file. "
                + "Optionally provide a timeout in seconds.");
    }

    @Test
    void schemaIsPiVerbatim() {
        var schema = BashTool.create().inputSchema();
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) schema.get("required");
        assertThat(required).containsExactly("command");
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Map<String, Object>>) schema.get("properties");
        assertThat(properties.get("command").get("description"))
            .isEqualTo("Shell command to execute");
        assertThat(properties.get("timeout").get("description"))
            .isEqualTo("Timeout in seconds (optional, no default timeout)");
    }

    @Test
    void promptSnippetIsPiVerbatim() {
        assertThat(BashTool.create().promptSnippet())
            .isEqualTo("Execute bash commands (ls, grep, find, etc.)");
    }

    @Test
    void prepareArgumentsReadsFractionalTimeout() {
        var input = BashTool.create().prepareArguments(
            Map.of("command", "echo hi", "timeout", 0.5));
        assertThat(input.timeoutSeconds()).hasValue(0.5);
    }

    @Test
    void rawArgumentsFallbackRecoversCommand() {
        var tool = BashTool.create();

        var nested = tool.prepareArguments(Map.of("_raw",
            "{\"command\": \"echo hi\"}"));
        assertThat(nested.command()).isEqualTo("echo hi");

        var truncated = tool.prepareArguments(Map.of("_raw",
            "{\"command\": \"echo \\\"hi\\\" --max-time 15\", \"timeout\": 30"));
        assertThat(truncated.command()).isEqualTo("echo \"hi\" --max-time 15");
    }

    // ── PI_* 会话环境变量面（B242；pi bash.ts:45-48,186-212,250,257）──

    @Test
    void piEnvInjectedWhenExposedAndContextPresent() throws Exception {
        var captured = captureShellOptions(new ShellResult("ok", 0, false, false, 1, 2));
        var context = context(captured).withSessionEnvironment(() ->
            new com.pijava.agent.tool.SessionEnvironment(
                "sid-1", "/tmp/s.jsonl", "anthropic", "claude-fable-5", "high"));

        BashTool.create(null, true).execute("id",
            new BashTool.BashInput("env", Optional.empty()), null, null, context);

        var env = captured.get().env();
        assertThat(env).containsEntry("PI_SESSION_ID", "sid-1")
            .containsEntry("PI_SESSION_FILE", "/tmp/s.jsonl")
            .containsEntry("PI_PROVIDER", "anthropic")
            .containsEntry("PI_MODEL", "claude-fable-5")
            .containsEntry("PI_REASONING_LEVEL", "high");
        // pi 先删后设：无论开关恒删 5 键（bash.ts:194-198）。
        assertThat(captured.get().envRemovals()).containsExactlyInAnyOrder(
            "PI_SESSION_ID", "PI_SESSION_FILE", "PI_PROVIDER", "PI_MODEL", "PI_REASONING_LEVEL");
    }

    @Test
    void nullComponentsAreConditionallySkipped() throws Exception {
        // pi :203-208：sessionFile/model/thinkingLevel 各自 if 门控；sessionId 恒设。
        var captured = captureShellOptions(new ShellResult("ok", 0, false, false, 1, 2));
        var context = context(captured).withSessionEnvironment(() ->
            new com.pijava.agent.tool.SessionEnvironment("sid-2", null, null, null, null));

        BashTool.create(null, true).execute("id",
            new BashTool.BashInput("env", Optional.empty()), null, null, context);

        var env = captured.get().env();
        assertThat(env).containsEntry("PI_SESSION_ID", "sid-2");
        assertThat(env).doesNotContainKeys("PI_SESSION_FILE", "PI_PROVIDER",
            "PI_MODEL", "PI_REASONING_LEVEL");
    }

    @Test
    void notExposedMeansNoInjectionButStillDeletes() throws Exception {
        // pi :199-208：exposeSessionEnvironment=false ⇒ 不设；:194-198 的删除不受门控。
        var captured = captureShellOptions(new ShellResult("ok", 0, false, false, 1, 2));
        var context = context(captured).withSessionEnvironment(() ->
            new com.pijava.agent.tool.SessionEnvironment(
                "sid-3", "/tmp/s.jsonl", "p", "m", "high"));

        BashTool.create(null, false).execute("id",
            new BashTool.BashInput("env", Optional.empty()), null, null, context);

        assertThat(captured.get().env()).doesNotContainKeys(
            "PI_SESSION_ID", "PI_SESSION_FILE", "PI_PROVIDER", "PI_MODEL", "PI_REASONING_LEVEL");
        assertThat(captured.get().envRemovals()).containsExactlyInAnyOrder(
            "PI_SESSION_ID", "PI_SESSION_FILE", "PI_PROVIDER", "PI_MODEL", "PI_REASONING_LEVEL");
    }

    @Test
    void guidelineIsGatedByTheSameSwitch() {
        // pi :257：说了就必须给，不给就不说（guideline 与注入同闸）；:47 逐字。
        assertThat(BashTool.create(null, true).promptGuidelines()).containsExactly(
            "You can inspect PI_* environment variables for current model and session details.");
        assertThat(BashTool.create(null, false).promptGuidelines()).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────

    private static final ShellResult[] RESULT_SLOT = new ShellResult[1];

    private AtomicReference<ShellOptions> captureShellOptions(ShellResult result) {
        RESULT_SLOT[0] = result;
        return new AtomicReference<>();
    }

    private ToolContext context(AtomicReference<ShellOptions> captured) {
        ShellExecutor shell = (command, options) -> {
            captured.set(options);
            return RESULT_SLOT[0];
        };
        return new ToolContext(tmp.toString(), Map.of(), shell, new DefaultFileSystem());
    }

    private com.pijava.agent.tool.ToolResult<BashTool.BashDetails> runWithOutput(String output)
            throws Exception {
        RESULT_SLOT[0] = new ShellResult(output, 0, false, false, 0, output.length());
        return BashTool.create().execute("id",
            new BashTool.BashInput("big", Optional.empty()), null, null,
            context(new AtomicReference<>()));
    }

    private static String textOf(com.pijava.agent.tool.ToolResult<BashTool.BashDetails> result) {
        return ((ContentBlock.TextContent) result.content().get(0)).text();
    }
}
