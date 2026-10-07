package com.pijava.coding.agent.rpc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A5（docs/25）：RPC 并发 prompt 路由 —— pi
 * {@code test/rpc-prompt-response-semantics.test.ts} 对应场景。
 *
 * <p>夹具<em>不经管道</em>：测试线程直接同步调 {@code handleLine}（pi 自己的
 * 测试形状）。首轮 {@code handleLine} 返回后驱动线程已起跑，直接轮询
 * {@code harness.isRunning} 确认运行窗口，再发第二令 —— 路由判定因此是
 * 确定性的。（管道读循环的覆盖在 {@link RpcModeEndToEndTest}；另注：JDK
 * {@code PipedInputStream} 对空管道的写入不唤醒 reader、reader 靠 1s
 * 超时轮询，不适合做时序夹具。）</p>
 */
class RpcConcurrentPromptRoutingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一个文本回合的完整事件序列。 */
    private static List<StreamEvent> textTurn(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason("stop");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone("stop", null, done));
    }

    private Fixture newFixture() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-rpc-route");
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-route", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        // 三个回合（steer/followUp 自动续跑会用到），事件间隔 100ms。
        var faux = FauxProvider.sequence("faux-route", List.of(
            textTurn("first reply"),
            textTurn("second reply"),
            textTurn("third reply")), 100);
        providers.register(faux);
        var toolContext = new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());

        var session = AgentSession.create(args, providers, toolContext);
        var stdout = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(session, new JsonlWriter(stdout), args);
        return new Fixture(session, dispatcher, stdout);
    }

    private record Fixture(AgentSession session, RpcDispatcher dispatcher,
                           ByteArrayOutputStream stdout) implements AutoCloseable {
        /** 同步分发一行（命令在测试线程处理）。 */
        void dispatch(String json) {
            dispatcher.handleLine(json);
        }

        JsonNode response(String id) throws Exception {
            return await(l -> "response".equals(l.path("type").asText())
                && id.equals(l.path("id").asText()));
        }

        JsonNode await(java.util.function.Predicate<JsonNode> predicate) throws Exception {
            var seen = new java.util.HashSet<String>();
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                for (var line : stdout.toString(StandardCharsets.UTF_8).split("\n")) {
                    if (line.isBlank() || !seen.add(line)) {
                        continue;
                    }
                    var node = JSON.readTree(line);
                    if (predicate.test(node)) {
                        return node;
                    }
                }
                Thread.sleep(10);
            }
            throw new AssertionError("timed out; output=\n"
                + stdout.toString(StandardCharsets.UTF_8));
        }

        void awaitRunning() throws Exception {
            var lane = session.laneName();
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline
                    && !session.harness().isRunning(lane)) {
                Thread.sleep(5);
            }
            assertThat(session.harness().isRunning(lane))
                .as("首轮驱动线程已进入运行窗口").isTrue();
        }

        @Override
        public void close() throws Exception {
            dispatcher.close();
            session.close();
        }
    }

    @Test
    void idlePromptReportsStarted() throws Exception {
        try (var f = newFixture()) {
            f.dispatch("{\"id\":\"s\",\"type\":\"prompt\",\"message\":\"Start\"}");

            var response = f.response("s");
            assertThat(response.get("success").asBoolean()).isTrue();
            assertThat(response.get("data").get("disposition").asText())
                .isEqualTo("started");
        }
    }

    @Test
    void promptWhileRunningWithoutBehaviorFailsVerbatim() throws Exception {
        try (var f = newFixture()) {
            f.dispatch("{\"id\":\"s\",\"type\":\"prompt\",\"message\":\"Start\"}");
            f.response("s");
            f.awaitRunning();

            f.dispatch("{\"id\":\"p\",\"type\":\"prompt\",\"message\":\"queue me\"}");
            var response = f.response("p");

            assertThat(response.get("success").asBoolean()).isFalse();
            assertThat(response.get("error").asText())
                .isEqualTo("Agent is already processing. Specify streamingBehavior "
                    + "('steer' or 'followUp') to queue the message.");
        }
    }

    @Test
    void followUpWhileRunningQueuesAndIsProcessedAfterRun() throws Exception {
        try (var f = newFixture()) {
            f.dispatch("{\"id\":\"s\",\"type\":\"prompt\",\"message\":\"Start\"}");
            f.response("s");
            f.awaitRunning();

            f.dispatch("{\"id\":\"q\",\"type\":\"prompt\",\"message\":\"do this later\","
                + "\"streamingBehavior\":\"followUp\"}");
            var response = f.response("q");
            assertThat(response.get("success").asBoolean()).isTrue();
            assertThat(response.get("data").get("disposition").asText())
                .isEqualTo("queued");

            // 原 run 结束后 followUp 自动起下一 run：排队文本作为用户消息出现，
            // 且引擎确实多跑了一个回合（second reply）。
            f.await(l -> l.toString().contains("do this later"));
            f.await(l -> l.toString().contains("second reply"));
        }
    }

    @Test
    void steerAndFollowUpCommandsReportQueuedDisposition() throws Exception {
        try (var f = newFixture()) {
            f.dispatch("{\"id\":\"s\",\"type\":\"prompt\",\"message\":\"Start\"}");
            f.response("s");
            f.awaitRunning();

            f.dispatch("{\"id\":\"st\",\"type\":\"steer\",\"message\":\"turn here\"}");
            var steer = f.response("st");
            assertThat(steer.get("success").asBoolean()).isTrue();
            assertThat(steer.get("data").get("disposition").asText())
                .isEqualTo("queued");

            f.dispatch("{\"id\":\"fu\",\"type\":\"follow_up\",\"message\":\"after finish\"}");
            var followUp = f.response("fu");
            assertThat(followUp.get("success").asBoolean()).isTrue();
            assertThat(followUp.get("data").get("disposition").asText())
                .isEqualTo("queued");
        }
    }
}
