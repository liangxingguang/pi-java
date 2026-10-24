package com.pijava.coding.agent.rpc;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

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
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 20（B237，T1d）：RPC prompt/steer/follow_up 的 disposition 三态上线
 * （pi rpc-mode.ts:394-424）＋images 透传＋扩展命令入队的 fail 响应（错文逐字）。
 */
class RpcDispositionWireTest {

    @TempDir
    Path tmp;

    private static List<StreamEvent> textTurn(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private static SlashCommand probe(String name, List<String> calls) {
        return new SlashCommand() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " probe"; }
            @Override public String argumentHint() { return ""; }
            @Override public CompletionStage<String> execute(String args, SlashContext context) {
                calls.add(args);
                return CompletableFuture.completedFuture("ok");
            }
        };
    }

    private AgentSession session(String providerName, List<List<StreamEvent>> replies,
                                 int delayMs) {
        var args = ArgsParser.parse(new String[] {
            "--provider", providerName, "--model", "hello",
            "--session-dir", tmp.toString()});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence(providerName, replies, delayMs));
        return AgentSession.create(args, providers, new ToolContext(tmp.toString(),
            Map.of(), new DefaultShellExecutor(), new DefaultFileSystem()));
    }

    private static String readLine(ByteArrayOutputStream out, String command) {
        return out.toString().lines()
            .filter(l -> l.contains("\"command\":\"" + command + "\""))
            .reduce((a, b) -> b).orElse("");
    }

    /** 转录用户消息里是否已有 image/png 内容块（结构断言，不拼 toString）。 */
    private static boolean hasImage(AgentSession s) {
        return s.harness().snapshot(s.laneName()).transcript().stream()
            .filter(com.pijava.agent.entry.Entry.Message.class::isInstance)
            .map(com.pijava.agent.entry.Entry.Message.class::cast)
            .anyMatch(e -> e.message().content().stream()
                .anyMatch(b -> b instanceof ContentBlock.ImageContent img
                    && "image/png".equals(img.mediaType())));
    }

    @Test
    void promptWithExtensionCommandRespondsHandled() throws Exception {
        try (var s = session("faux-disp", List.of(textTurn("r")), 0)) {
            var calls = new CopyOnWriteArrayList<String>();
            s.services().slashCommands().extensionView().register(probe("hello", calls));
            var out = new ByteArrayOutputStream();
            var dispatcher = new RpcDispatcher(s, new JsonlWriter(out),
                ArgsParser.parse(new String[] {"--session-dir", tmp.toString()}));

            dispatcher.handleLine("{\"id\":\"1\",\"type\":\"prompt\",\"message\":\"/hello world\"}");

            // 批 20 评审回补（I-1）：响应在命令完成后异步写——轮询到线。
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline
                    && !out.toString().contains("\"command\":\"prompt\"")) {
                Thread.sleep(10);
            }
            assertThat(readLine(out, "prompt")).contains("\"disposition\":\"handled\"");
            assertThat(calls).containsExactly("world");
        }
    }

    @Test
    void plainPromptRespondsStarted() {
        try (var s = session("faux-disp", List.of(textTurn("r")), 0)) {
            var out = new ByteArrayOutputStream();
            var dispatcher = new RpcDispatcher(s, new JsonlWriter(out),
                ArgsParser.parse(new String[] {"--session-dir", tmp.toString()}));

            dispatcher.handleLine("{\"id\":\"1\",\"type\":\"prompt\",\"message\":\"hi\"}");

            assertThat(readLine(out, "prompt")).contains("\"disposition\":\"started\"");
        }
    }

    @Test
    void promptWhileStreamingRespondsQueued() throws Exception {
        // 首个回复延迟 300ms——第二个 prompt 在流式窗口内提交。
        try (var s = session("faux-disp",
                List.of(textTurn("slow"), textTurn("fast")), 300)) {
            var out = new ByteArrayOutputStream();
            var dispatcher = new RpcDispatcher(s, new JsonlWriter(out),
                ArgsParser.parse(new String[] {"--session-dir", tmp.toString()}));

            dispatcher.handleLine("{\"id\":\"1\",\"type\":\"prompt\",\"message\":\"first\"}");
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline && !s.harness().isRunning(s.laneName())) {
                Thread.sleep(5);
            }
            dispatcher.handleLine("{\"id\":\"2\",\"type\":\"prompt\",\"message\":\"second\","
                + "\"streamingBehavior\":\"steer\"}");

            assertThat(readLine(out, "prompt")).contains("\"disposition\":\"queued\"");
        }
    }

    @Test
    void steerRespondsQueuedAndPassesImages() {
        try (var s = session("faux-disp", List.of(textTurn("r")), 0)) {
            var out = new ByteArrayOutputStream();
            var dispatcher = new RpcDispatcher(s, new JsonlWriter(out),
                ArgsParser.parse(new String[] {"--session-dir", tmp.toString()}));

            dispatcher.handleLine("{\"id\":\"1\",\"type\":\"steer\",\"message\":\"hold\","
                + "\"images\":[{\"type\":\"image\",\"mediaType\":\"image/png\",\"data\":\"AA==\"}]}");

            assertThat(readLine(out, "steer")).contains("\"disposition\":\"queued\"");
            var cleared = s.clearQueue();
            assertThat(cleared.steering()).containsExactly("hold");
        }
    }

    @Test
    void followUpRespondsQueued() {
        try (var s = session("faux-disp", List.of(textTurn("r")), 0)) {
            var out = new ByteArrayOutputStream();
            var dispatcher = new RpcDispatcher(s, new JsonlWriter(out),
                ArgsParser.parse(new String[] {"--session-dir", tmp.toString()}));

            dispatcher.handleLine("{\"id\":\"1\",\"type\":\"follow_up\",\"message\":\"later\"}");

            assertThat(readLine(out, "follow_up")).contains("\"disposition\":\"queued\"");
            assertThat(s.clearQueue().followUp()).containsExactly("later");
        }
    }

    @Test
    void steerOnExtensionCommandRespondsFailWithVerbatimMessage() {
        try (var s = session("faux-disp", List.of(textTurn("r")), 0)) {
            s.services().slashCommands().extensionView()
                .register(probe("hello", new CopyOnWriteArrayList<>()));
            var out = new ByteArrayOutputStream();
            var dispatcher = new RpcDispatcher(s, new JsonlWriter(out),
                ArgsParser.parse(new String[] {"--session-dir", tmp.toString()}));

            dispatcher.handleLine("{\"id\":\"1\",\"type\":\"steer\",\"message\":\"/hello\"}");

            // pi：_throwIfExtensionCommand 的抛错走 rpc-mode 通用错道（fail 响应）。
            assertThat(readLine(out, "steer"))
                .contains("\"success\":false")
                .contains("Extension command \\\"/hello\\\" cannot be queued."
                    + " Use prompt() or execute the command when not streaming.");
        }
    }

    @Test
    void extensionCommandExecutionDoesNotBlockTheReaderThread() throws Exception {
        // 批 20 评审回补（I-1，pi rpc-mode.ts:398 的 void prompt 对位）：
        // 命令体在独立虚拟线程执行——读线程继续照护后续命令；prompt 响应
        // 在命令完成后才写（preflightResult 时序）。
        try (var s = session("faux-disp", List.of(textTurn("r")), 0)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            s.services().slashCommands().extensionView().register(new SlashCommand() {
                @Override public String name() { return "gate"; }
                @Override public String description() { return "gate probe"; }
                @Override public String argumentHint() { return ""; }
                @Override public CompletionStage<String> execute(String args, SlashContext context) {
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "ok";
                    });
                }
            });
            var out = new ByteArrayOutputStream();
            var dispatcher = new RpcDispatcher(s, new JsonlWriter(out),
                ArgsParser.parse(new String[] {"--session-dir", tmp.toString()}));

            dispatcher.handleLine("{\"id\":\"1\",\"type\":\"prompt\",\"message\":\"/gate\"}");
            // 命令还卡在 gate 上：后续命令照常照护（同步 join 会死在这里）。
            dispatcher.handleLine("{\"id\":\"2\",\"type\":\"get_commands\"}");
            var before = out.toString();
            assertThat(before).contains("\"command\":\"get_commands\",\"success\":true");
            assertThat(before).doesNotContain("\"command\":\"prompt\"")
                .as("prompt 响应须等命令完成（pi preflightResult 时序）");

            gate.countDown();
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline
                    && !out.toString().contains("\"command\":\"prompt\"")) {
                Thread.sleep(10);
            }
            assertThat(readLine(out, "prompt")).contains("\"disposition\":\"handled\"");
        }
    }

    @Test
    void promptImagesReachTranscript() throws Exception {
        try (var s = session("faux-disp", List.of(textTurn("r")), 0)) {
            var out = new ByteArrayOutputStream();
            var dispatcher = new RpcDispatcher(s, new JsonlWriter(out),
                ArgsParser.parse(new String[] {"--session-dir", tmp.toString()}));

            dispatcher.handleLine("{\"id\":\"1\",\"type\":\"prompt\",\"message\":\"look\","
                + "\"images\":[{\"type\":\"image\",\"mediaType\":\"image/png\",\"data\":\"AA==\"}]}");

            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline && !hasImage(s)) {
                Thread.sleep(10);
            }
            assertThat(hasImage(s))
                .as("images 经 PromptConfig 透传到用户消息（pi :399 对位）")
                .isTrue();
        }
    }
}
