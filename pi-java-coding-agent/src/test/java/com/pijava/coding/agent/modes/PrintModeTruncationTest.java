package com.pijava.coding.agent.modes;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W10（B281）：print 模式末条 assistant {@code stopReason == length} ⇒
 * stderr 逐字＋exit 1（pi print-mode.ts:151-157）；正常收尾不误报。
 */
class PrintModeTruncationTest {

    private static List<StreamEvent> response(StopReason reason, String text) {
        var message = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason(reason);
        var partial = AssistantMessage.empty();
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.TextStart(0, partial),
            new StreamEvent.TextDelta(0, text, message),
            new StreamEvent.TextEnd(0, text, message),
            StreamEvent.StreamDone.settle(reason, message));
    }

    private static int runWith(StopReason reason, String text,
                               ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux", List.of(response(reason, text))));
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux", "--model", "hello", "--no-session", "-p"});
        var capturedOut = System.out;
        var capturedErr = System.err;
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            return PrintMode.run(List.of("hi"), args, () -> AgentSession.create(
                args, providers,
                new ToolContext(System.getProperty("user.dir"), Map.of(),
                    new DefaultShellExecutor(null), new DefaultFileSystem())));
        } finally {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
        }
    }

    @Test
    void lengthStopReasonPrintsWarningAndExitsOne() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = runWith(StopReason.LENGTH, "partial answer", out, err);
        assertThat(code).isEqualTo(1);
        // stderr 还承载日志/运行摘要；只钉逐字警告行在场。
        assertThat(err.toString(StandardCharsets.UTF_8)).contains(
            "Response was truncated: the model reached its output token limit.");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("partial answer");
    }

    @Test
    void normalStopIsNotReportedAsTruncation() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = runWith(StopReason.STOP, "complete", out, err);
        assertThat(code).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain("truncated");
    }
}
