package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W5（pi {@code takeUnreportedDiagnostics}，
 * {@code agent-session-runtime.ts:127-136}）：诊断按 {@code type\0message} 去重；
 * 同一 warning 在同一会话只上报一次，替换会话继承已上报键（不每换一次再报）。
 */
class StartupDiagnosticsDedupTest {

    @TempDir
    Path tmp;

    private AgentSession session() {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-diag", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("ok"))).withStopReason(StopReason.STOP);
        providers.register(FauxProvider.sequence("faux-diag", List.of(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, "ok", done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done)))));
        return AgentSession.create(args, providers, new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem()));
    }

    /** 同一 type+message 第二次取 ⇒ 空（pi 键的 message 半）。 */
    @Test
    void sameWarningReportedOnlyOnce() {
        try (var s = session()) {
            s.startupDiagnosticsInternal().add(new Args.ArgDiagnostic("warning", "boom"));
            assertThat(s.startupDiagnostics())
                .extracting(Args.ArgDiagnostic::message).containsExactly("boom");
            assertThat(s.startupDiagnostics()).isEmpty();
        }
    }

    /** 键含 type：同 message 不同 type ⇒ 两条都上报（pi 键的 type 半）。 */
    @Test
    void typeIsPartOfTheDedupKey() {
        try (var s = session()) {
            s.startupDiagnosticsInternal().add(new Args.ArgDiagnostic("warning", "same"));
            s.startupDiagnosticsInternal().add(new Args.ArgDiagnostic("error", "same"));
            assertThat(s.startupDiagnostics())
                .extracting(Args.ArgDiagnostic::type).containsExactly("warning", "error");
        }
    }

    /** 替换会话继承已上报键：同一 warning 不再重报，新 warning 照报。 */
    @Test
    void replacementSessionInheritsReportedKeys() {
        try (var a = session(); var b = session()) {
            a.startupDiagnosticsInternal().add(new Args.ArgDiagnostic("warning", "settings"));
            assertThat(a.startupDiagnostics()).hasSize(1);

            b.startupDiagnosticsInternal().add(new Args.ArgDiagnostic("warning", "settings"));
            b.carryReportedDiagnosticsFrom(a);
            assertThat(b.startupDiagnostics()).as("替换后同一 warning 不重报").isEmpty();

            b.startupDiagnosticsInternal().add(new Args.ArgDiagnostic("warning", "fresh"));
            assertThat(b.startupDiagnostics())
                .extracting(Args.ArgDiagnostic::message).containsExactly("fresh");
        }
    }
}
