package com.pijava.coding.agent.cli;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 §16: ArgsParser per-parameter, combination, unknown-flag and
 * {@code @file} behavior.
 */
class ArgsParserTest {

    @Test
    void parsesKnownFlags() {
        var args = ArgsParser.parse(new String[] {
            "--provider", "openai", "--model", "gpt-5", "--api-key", "k",
            "--system-prompt", "sys", "--append-system-prompt", "extra",
            "--thinking", "high", "--name", "demo",
            "--no-session", "--session", "abc", "--session-dir", "dir",
            "--models", "gpt-5,claude", "--tools", "read,write",
            "--exclude-tools", "bash", "--no-builtin-tools",
            "--extension", "ext", "--no-extensions",
            "--skill", "s1", "--no-skills",
            "--prompt-template", "p1", "--no-prompt-templates",
            "--theme", "light", "--no-themes", "--no-context-files",
            "--offline", "--tui-mode", "fullscreen", "--verbose", "--approve"
        });

        assertThat(args.provider()).isEqualTo("openai");
        assertThat(args.model()).isEqualTo("gpt-5");
        assertThat(args.apiKey()).isEqualTo("k");
        assertThat(args.systemPrompt()).isEqualTo("sys");
        assertThat(args.appendSystemPrompt()).containsExactly("extra");
        assertThat(args.thinking()).isEqualTo("high");
        // 批 14（T6b）后 -c/--session-id/--fork 与 --session 等互斥，不再同屏出现——
        // 三者的字段覆盖见下方 conflict/legal 夹具。
        assertThat(args.continue_()).isFalse();
        assertThat(args.name()).isEqualTo("demo");
        assertThat(args.noSession()).isTrue();
        assertThat(args.session()).isEqualTo("abc");
        assertThat(args.sessionId()).isNull();
        assertThat(args.fork()).isNull();
        assertThat(args.sessionDir()).isEqualTo("dir");
        assertThat(args.models()).containsExactly("gpt-5", "claude");
        assertThat(args.tools()).containsExactly("read", "write");
        assertThat(args.excludeTools()).containsExactly("bash");
        assertThat(args.noBuiltinTools()).isTrue();
        assertThat(args.extensions()).containsExactly("ext");
        assertThat(args.noExtensions()).isTrue();
        assertThat(args.skills()).containsExactly("s1");
        assertThat(args.noSkills()).isTrue();
        assertThat(args.promptTemplates()).containsExactly("p1");
        assertThat(args.noPromptTemplates()).isTrue();
        assertThat(args.themes()).containsExactly("light");
        assertThat(args.noThemes()).isTrue();
        assertThat(args.noContextFiles()).isTrue();
        assertThat(args.offline()).isTrue();
        assertThat(args.tuiMode()).isEqualTo("fullscreen");
        assertThat(args.verbose()).isTrue();
        assertThat(args.projectTrustOverride()).isTrue();
        assertThat(args.diagnostics()).isEmpty();
    }

    @Test
    void collectsMessagesAndFileArgs() {
        var args = ArgsParser.parse(new String[] {
            "@notes.txt", "hello", "world", "--print"
        });

        assertThat(args.fileArgs()).containsExactly("notes.txt");
        assertThat(args.messages()).containsExactly("hello", "world");
        assertThat(args.print()).isTrue();
    }

    @Test
    void collectsUnknownFlagsAsExtensionFlags() {
        var args = ArgsParser.parse(new String[] {
            "--custom-flag=value", "--other-flag", "value", "message"
        });

        assertThat(args.unmatched())
            .containsExactly("--custom-flag=value", "--other-flag", "value");
        assertThat(args.messages()).containsExactly("message");
    }

    @Test
    void reportsUnknownShortOption() {
        var args = ArgsParser.parse(new String[] {"-z"});

        assertThat(args.diagnostics())
            .anyMatch(d -> "error".equals(d.type())
                && d.message().contains("-z"));
    }

    @Test
    void invalidModeIsErrorDiagnostic() {
        var args = ArgsParser.parse(new String[] {"--mode", "bogus"});

        assertThat(args.diagnostics())
            .anyMatch(d -> "error".equals(d.type())
                && d.message().contains("bogus"));
    }

    @Test
    void validFutureModeHasNoDiagnostic() {
        var args = ArgsParser.parse(new String[] {"--mode", "json"});

        assertThat(args.diagnostics()).isEmpty();
        assertThat(args.mode()).isEqualTo("json");
    }

    @Test
    void invalidThinkingIsWarningDiagnostic() {
        var args = ArgsParser.parse(new String[] {"--thinking", "ultra"});

        assertThat(args.diagnostics())
            .anyMatch(d -> "warning".equals(d.type())
                && d.message().contains("ultra"));
    }

    @Test
    void multiCharShortFlagsExpandWithoutNameCollision() {
        var args = ArgsParser.parse(new String[] {
            "-nt", "-nbt", "-xt", "bash", "-na", "-ns", "-np", "-nc", "-ne"
        });

        assertThat(args.noTools()).isTrue();
        assertThat(args.noBuiltinTools()).isTrue();
        assertThat(args.excludeTools()).containsExactly("bash");
        assertThat(args.projectTrustOverride()).isFalse();
        assertThat(args.noSkills()).isTrue();
        assertThat(args.noPromptTemplates()).isTrue();
        assertThat(args.noContextFiles()).isTrue();
        assertThat(args.noExtensions()).isTrue();
    }

    @Test
    void nameShortOptionStillWorks() {
        var args = ArgsParser.parse(new String[] {"-n", "my-session"});

        assertThat(args.name()).isEqualTo("my-session");
    }

    @Test
    void listModelsTriState() {
        assertThat(ArgsParser.parse(new String[] {"--list-models"}).listModels())
            .isEqualTo("");
        assertThat(ArgsParser.parse(new String[] {"--list-models", "gemini"})
            .listModels()).isEqualTo("gemini");
        assertThat(ArgsParser.parse(new String[] {}).listModels()).isNull();
    }

    @Test
    void tracePayloadsFlagParses() {
        assertThat(ArgsParser.parse(new String[] {"--trace-payloads"}).tracePayloads())
            .isTrue();
        assertThat(ArgsParser.parse(new String[] {}).tracePayloads())
            .isFalse();
        assertThat(ArgsParser.parse(new String[] {"--debug", "--trace-payloads"})
            .tracePayloads()).isTrue();
    }

    @Test
    void helpAndVersionShortFlags() {
        assertThat(ArgsParser.parse(new String[] {"-h"}).help()).isTrue();
        assertThat(ArgsParser.parse(new String[] {"-v"}).version()).isTrue();
    }

    // ── B236（docs/11 §3.4）：tools/excludeTools 的 null ≙ 缺席语义＋解析期校验 ──

    @Test
    void absentToolFlagsYieldNullNotEmpty() {
        var args = ArgsParser.parse(new String[] {});
        assertThat(args.tools()).isNull();
        assertThat(args.excludeTools()).isNull();
    }

    @Test
    void blankToolsValueIsAnEmptyAllowlist() {
        // pi args.ts:151-161：给了但全空 ⇒ []（空 allowlist，零工具），与缺席不同。
        var args = ArgsParser.parse(new String[] {"--tools", ""});
        assertThat(args.tools()).isEmpty();
        assertThat(args.diagnostics()).isEmpty();
    }

    @Test
    void mixedToolsEntriesAreAnErrorDiagnostic() {
        // pi getToolListError 逐字文案 + `${arg}: ${error}` 前缀（窄差 D1：恒 --tools）。
        var args = ArgsParser.parse(new String[] {"--tools", "read,+grep"});
        assertThat(args.diagnostics())
            .anyMatch(d -> "error".equals(d.type())
                && d.message().equals(
                    "--tools: tool names cannot be mixed with +name or -name entries"));
        // 校验失败不写 tools（≙ pi 不写 result.tools）。
        assertThat(args.tools()).isNull();
    }

    @Test
    void modifierPatternIsAnErrorDiagnosticEvenViaShortAlias() {
        var args = ArgsParser.parse(new String[] {"-t", "-gr*"});
        assertThat(args.diagnostics())
            .anyMatch(d -> "error".equals(d.type())
                && d.message().equals(
                    "--tools: +name and -name entries take exact tool names, not patterns: -gr*"));
        assertThat(args.tools()).isNull();
    }

    @Test
    void pureModifierToolsListIsAccepted() {
        var args = ArgsParser.parse(new String[] {"--tools", "+grep,-bash"});
        assertThat(args.tools()).containsExactly("+grep", "-bash");
        assertThat(args.diagnostics()).isEmpty();
    }

    // ── 批 14（docs/25 T6b）：pi validateForkFlags/validateSessionIdFlags 两臂 ──

    @Test
    void forkConflictsWithSessionContinueResumeNoSession() {
        var args = ArgsParser.parse(new String[] {
            "--fork", "abc", "--session", "x", "-c", "-r", "--no-session"});
        assertThat(args.diagnostics())
            .anyMatch(d -> "error".equals(d.type()) && d.message().equals(
                "--fork cannot be combined with --session, --continue, --resume, --no-session"));
    }

    @Test
    void forkWithSessionIdAloneIsLegal() {
        // F-b 组合（pi forkSessionOrExit 消费 sessionId 作新会话 id）不诊断。
        var args = ArgsParser.parse(new String[] {"--fork", "abc", "--session-id", "fork-id-1"});
        assertThat(args.diagnostics()).isEmpty();
        assertThat(args.fork()).isEqualTo("abc");
        assertThat(args.sessionId()).isEqualTo("fork-id-1");

        var c = ArgsParser.parse(new String[] {"-c"});
        assertThat(c.diagnostics()).isEmpty();
        assertThat(c.continue_()).isTrue();
    }

    @Test
    void sessionIdConflictsWithSessionContinueResume() {
        var args = ArgsParser.parse(new String[] {"--session-id", "sid", "--session", "x"});
        assertThat(args.diagnostics())
            .anyMatch(d -> "error".equals(d.type()) && d.message().equals(
                "--session-id cannot be combined with --session"));

        var args2 = ArgsParser.parse(new String[] {"--session-id", "sid", "-c", "-r"});
        assertThat(args2.diagnostics())
            .anyMatch(d -> "error".equals(d.type()) && d.message().equals(
                "--session-id cannot be combined with --continue, --resume"));
    }

    @Test
    void sessionIdWithNoSessionAloneIsLegal() {
        // pi validateSessionIdFlags 不含 --no-session（内存会话带 id，main.ts:364-366）。
        var args = ArgsParser.parse(new String[] {"--session-id", "sid", "--no-session"});
        assertThat(args.diagnostics()).isEmpty();
    }

    @Test
    void sessionIdFormatAssertionFollowsPiRegex() {
        // pi assertValidSessionId：^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$
        // （"=" 形式喂值，避免 picocli 把 "-" 起头的值当选项）
        // 前导 "-" 的坏值（"-abc"）喂不进 picocli（撞短旗簇）——那是 CLI 分词边界，
        // 非格式断言面，反例表不列。
        for (var bad : new String[] {".abc", "abc-", "abc def", "a/b", ""}) {
            var args = ArgsParser.parse(new String[] {"--session-id=" + bad});
            assertThat(args.diagnostics())
                .as("session-id %s", (Object) bad)
                .anyMatch(d -> "error".equals(d.type()) && d.message().startsWith(
                    "Session id must be non-empty, contain only alphanumeric characters"));
        }
        for (var good : new String[] {"a", "abc", "a.b-c_d", "A1"}) {
            var args = ArgsParser.parse(new String[] {"--session-id", good});
            assertThat(args.diagnostics()).as("session-id %s", good).isEmpty();
        }
    }

    // ── 批 14（docs/25 T12）：pi args.ts:83-90 的 "--" 预分流 ──

    @Test
    void doubleDashRoutesDashPrefixedTokensToMessages() {
        // 现误判 Unknown option 的修真：-- 后 -foo 是消息不是旗。
        var args = ArgsParser.parse(new String[] {"--", "-foo"});
        assertThat(args.messages()).containsExactly("-foo");
        assertThat(args.diagnostics()).isEmpty();
    }

    @Test
    void doubleDashRoutesFileArgsAndMessages() {
        var args = ArgsParser.parse(new String[] {"--", "@a.txt", "b"});
        assertThat(args.fileArgs()).containsExactly("a.txt");
        assertThat(args.messages()).containsExactly("b");
        assertThat(args.diagnostics()).isEmpty();
    }

    @Test
    void doubleDashStopsFlagParsing() {
        var args = ArgsParser.parse(new String[] {"--tools", "read", "--", "-t"});
        assertThat(args.tools()).containsExactly("read");
        assertThat(args.messages()).containsExactly("-t");
        assertThat(args.diagnostics()).isEmpty();
    }

    // ── 批 14（docs/25 T16）：--use-theme 两臂诊断（pi args.ts:204-205 同文）──

    @Test
    void useThemeMissingValueIsPiDiagnostic() {
        var args = ArgsParser.parse(new String[] {"--use-theme"});
        assertThat(args.diagnostics()).anyMatch(d -> "error".equals(d.type())
            && d.message().equals("--use-theme requires a theme name"));
    }

    @Test
    void useThemeDashPrefixedValueIsPiDiagnostic() {
        var args = ArgsParser.parse(new String[] {"--use-theme=-dark"});
        assertThat(args.diagnostics()).anyMatch(d -> "error".equals(d.type())
            && d.message().equals("--use-theme requires a theme name"));
    }

    @Test
    void useThemeParsesClean() {
        var args = ArgsParser.parse(new String[] {"--use-theme", "light"});
        assertThat(args.useTheme()).isEqualTo("light");
        assertThat(args.diagnostics()).isEmpty();
    }

    @Test
    void conflictDiagnosticPrecedesFormatAssertion() {
        // pi 先冲突诊断后格式断言（main.ts:329-335 同序），两者独立上报。
        var args = ArgsParser.parse(new String[] {"--session-id=-bad-", "--session", "x"});
        var errors = args.diagnostics().stream()
            .filter(d -> "error".equals(d.type())).map(d -> d.message()).toList();
        assertThat(errors).containsSubsequence(
            "--session-id cannot be combined with --session",
            "Session id must be non-empty, contain only alphanumeric characters,"
                + " '-', '_', and '.', and start and end with an alphanumeric character");
    }
}
