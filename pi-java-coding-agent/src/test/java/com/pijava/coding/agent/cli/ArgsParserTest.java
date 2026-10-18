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
            "--thinking", "high", "-c", "--name", "demo",
            "--no-session", "--session", "abc", "--session-id", "xyz",
            "--fork", "f", "--session-dir", "dir",
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
        assertThat(args.continue_()).isTrue();
        assertThat(args.name()).isEqualTo("demo");
        assertThat(args.noSession()).isTrue();
        assertThat(args.session()).isEqualTo("abc");
        assertThat(args.sessionId()).isEqualTo("xyz");
        assertThat(args.fork()).isEqualTo("f");
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
}
