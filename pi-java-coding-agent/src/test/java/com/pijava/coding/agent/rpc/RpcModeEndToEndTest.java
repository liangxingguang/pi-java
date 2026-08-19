package com.pijava.coding.agent.rpc;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-5b: RpcMode 端到端 —— PipedInputStream/PipedOutputStream 模拟 stdin/stdout，
 * 完整 prompt → 事件 → 响应回路。
 */
class RpcModeEndToEndTest {

    @Test
    void fullPromptLoopOverPipes() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-rpc-e2e");
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-e2e", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("Hi"))).withStopReason("stop");
        providers.register(FauxProvider.sequence("faux-e2e", List.of(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, "Hi", done),
            new StreamEvent.TextEnd(0, "Hi", done),
            new StreamEvent.StreamDone("stop", null, done)))));
        var toolContext = new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());

        var stdinOut = new PipedOutputStream();
        var stdinIn = new PipedInputStream(stdinOut);
        var stdout = new ByteArrayOutputStream();
        var exitCode = new AtomicInteger(-1);

        var thread = Thread.startVirtualThread(() ->
            exitCode.set(RpcMode.run(stdinIn, stdout, args, providers, toolContext)));

        stdinOut.write("{\"id\":\"1\",\"type\":\"prompt\",\"message\":\"hi\"}\n"
            .getBytes(StandardCharsets.UTF_8));
        // 等 run 完成（agent_settled）再发 get_state —— 避免关闭 stdin 杀掉异步 run。
        awaitOutput(stdout, "\"type\":\"agent_settled\"");
        stdinOut.write("{\"id\":\"2\",\"type\":\"get_state\"}\n"
            .getBytes(StandardCharsets.UTF_8));
        awaitOutput(stdout, "\"command\":\"get_state\"");
        stdinOut.close();

        waitForExit(thread);
        assertThat(exitCode.get()).isZero();

        String output = stdout.toString(StandardCharsets.UTF_8);
        // 响应：type=response + command=prompt + success=true（字段顺序由 record 决定，按 JSON 解析断言）
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var first = mapper.readTree(output.lines().findFirst().orElse(""));
        assertThat(first.get("type").asText()).isEqualTo("response");
        assertThat(first.get("command").asText()).isEqualTo("prompt");
        assertThat(first.get("success").asBoolean()).isTrue();
        assertThat(first.get("id").asText()).isEqualTo("1");

        assertThat(output).contains("\"type\":\"message_update\"");
        assertThat(output).contains("\"type\":\"agent_end\"");
        assertThat(output).contains("\"type\":\"agent_settled\"");
        assertThat(output).contains("\"command\":\"get_state\"");
    }

    private static void awaitOutput(ByteArrayOutputStream stdout, String fragment) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (stdout.toString(StandardCharsets.UTF_8).contains(fragment)) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("timed out waiting for: " + fragment
            + "\noutput so far: " + stdout.toString(StandardCharsets.UTF_8));
    }

    private static void waitForExit(Thread thread) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (!thread.isAlive()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("RpcMode did not exit within timeout");
    }
}
