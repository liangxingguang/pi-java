package com.pijava.coding.agent.rpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.pijava.agent.harness.QueueMode;
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
import com.pijava.coding.agent.core.AgentSessionEvent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-5b: RpcDispatcher — prompt 先回 success 随后事件流；未知命令回失败；get_state。
 */
class RpcDispatcherTest {

    @Test
    void promptReturnsSuccessThenStreamsEvents() throws Exception {
        var ctx = context("faux-rpc", textStream("Hi there"));
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(ctx.session(), new JsonlWriter(out), ctx.args());

        dispatcher.handleLine("{\"id\":\"1\",\"type\":\"prompt\",\"message\":\"hi\"}");

        String output = awaitSettled(out);
        String firstLine = output.lines().findFirst().orElse("");
        assertThat(firstLine).contains("\"id\":\"1\"")
            .contains("\"command\":\"prompt\"")
            .contains("\"success\":true");
        assertThat(output).contains("\"type\":\"message_update\"");
        assertThat(output).contains("\"type\":\"agent_end\"");
        assertThat(output).contains("\"type\":\"agent_settled\"");
        // 事件信封不带顶层 id（无关联通知流）；response 行除外。
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(output.lines()
            .filter(l -> !l.isBlank())
            .map(l -> {
                try {
                    return mapper.readTree(l);
                } catch (Exception e) {
                    return null;
                }
            })
            .filter(n -> n != null && n.get("type") != null)
            .filter(n -> !"response".equals(n.get("type").asText()))
            .allMatch(n -> n.get("id") == null)).isTrue();
    }

    @Test
    void unknownCommandReturnsFailure() throws Exception {
        var ctx = context("faux-unknown", textStream("Hi"));
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(ctx.session(), new JsonlWriter(out), ctx.args());

        dispatcher.handleLine("{\"id\":\"4\",\"type\":\"bad_command\"}");
        dispatcher.handleLine("{\"id\":\"5\",\"type\":\"set_model\",\"model\":\"x\"}");

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"id\":\"4\"")
            .contains("\"command\":\"bad_command\"")
            .contains("\"success\":false")
            .contains("Unknown command: bad_command");
        // set_model 解析成功但模型不存在 → 处理器异常报实际消息
        assertThat(output).contains("\"id\":\"5\"")
            .contains("\"command\":\"set_model\"")
            .contains("\"success\":false")
            .contains("Unknown model: x");
    }

    @Test
    void secondBatchControlCommands() throws Exception {
        var ctx = context("faux-batch", textStream("Hi"));
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(ctx.session(), new JsonlWriter(out), ctx.args());

        dispatcher.handleLine("{\"id\":\"1\",\"type\":\"set_model\",\"model\":\"openai/gpt-5\"}");
        dispatcher.handleLine("{\"id\":\"2\",\"type\":\"get_available_models\"}");
        dispatcher.handleLine("{\"id\":\"3\",\"type\":\"set_thinking_level\",\"level\":\"medium\"}");
        dispatcher.handleLine("{\"id\":\"4\",\"type\":\"get_available_thinking_levels\"}");
        dispatcher.handleLine("{\"id\":\"5\",\"type\":\"set_session_name\",\"name\":\"my-session\"}");
        dispatcher.handleLine("{\"id\":\"6\",\"type\":\"compact\"}");
        dispatcher.handleLine("{\"id\":\"7\",\"type\":\"set_auto_compaction\",\"enabled\":true}");
        dispatcher.handleLine("{\"id\":\"8\",\"type\":\"get_commands\"}");
        dispatcher.handleLine("{\"id\":\"9\",\"type\":\"get_session_stats\"}");

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"command\":\"set_model\",\"success\":true");
        assertThat(output).contains("\"command\":\"get_available_models\",\"success\":true")
            .contains("openai/gpt-5");
        assertThat(output).contains("\"command\":\"set_thinking_level\",\"success\":true");
        assertThat(output).contains("\"command\":\"get_available_thinking_levels\",\"success\":true");
        assertThat(output).contains("\"command\":\"set_session_name\",\"success\":true");
        // compact 空会话可能无内容可压缩 → 只要返回 compact 响应即可
        assertThat(output).contains("\"command\":\"compact\"");
        assertThat(output).contains("\"command\":\"set_auto_compaction\",\"success\":true");
        assertThat(output).contains("\"command\":\"get_commands\",\"success\":true")
            .contains("\"name\":\"help\"");
        assertThat(output).contains("\"command\":\"get_session_stats\",\"success\":true");

        // set_model 实际改动了 harness 模型
        assertThat(ctx.session().harness().getModel().modelName()).isEqualTo("gpt-5");
        // set_auto_compaction 切换到 harness 压缩设置并反映在 get_state
        assertThat(ctx.session().harness().getCompactionSettings()).isNotNull();
        assertThat(ctx.session().harness().getCompactionSettings().enabled()).isTrue();
    }

    @Test
    void extensionUiRequestGetsResponseFromStdin() throws Exception {
        var ctx = context("faux-ui", textStream("Hi"));
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(ctx.session(), new JsonlWriter(out), ctx.args());

        var result = new AtomicReference<RpcExtensionUIResponse>();
        Thread thread = Thread.startVirtualThread(() ->
            result.set(ctx.session().extensionUI().request(
                new RpcExtensionUIRequest("ui-1", "input", Map.of("prompt", "Name?")))));
        awaitOutput(out, "extension_ui_request");

        // 客户端从 stdin 回响应
        dispatcher.handleLine(
            "{\"type\":\"extension_ui_response\",\"id\":\"ui-1\",\"value\":\"hello\"}");

        awaitResult(result);
        assertThat(result.get()).isNotNull();
        assertThat(result.get().value()).isEqualTo("hello");
        assertThat(out.toString()).contains("\"type\":\"extension_ui_request\"")
            .contains("\"method\":\"input\"");
    }

    @Test
    void extensionUiWireRoundTrip() throws Exception {
        var mapper = new ObjectMapper();
        var request = new RpcExtensionUIRequest("r1", "confirm", Map.of("message", "OK?"));
        var requestJson = mapper.writeValueAsString(request);
        assertThat(requestJson).contains("\"type\":\"extension_ui_request\"");
        var back = mapper.readValue(requestJson, RpcExtensionUIRequest.class);
        assertThat(back).isEqualTo(request);

        var response = RpcExtensionUIResponse.confirm("r1", true);
        var responseJson = mapper.writeValueAsString(response);
        assertThat(responseJson).contains("\"type\":\"extension_ui_response\"");
        assertThat(mapper.readValue(responseJson, RpcExtensionUIResponse.class))
            .isEqualTo(response);
    }

    @Test
    void getStateReturnsPayload() throws Exception {
        var ctx = context("faux-state", textStream("Hi"));
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(ctx.session(), new JsonlWriter(out), ctx.args());

        dispatcher.handleLine("{\"id\":\"2\",\"type\":\"get_state\"}");

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"id\":\"2\"")
            .contains("\"command\":\"get_state\"")
            .contains("\"success\":true")
            .contains("\"model\":\"faux-state/hello\"");

        // 包④（docs/31 §8.37）：state 载荷的四个字段此前写死 false/null/null/0，
        // 现在逐字段取自会话。这里钉住静态会话下的取值 —— 真值侧
        // （压缩进行中 / 有排队 / 有落盘路径）各自在下面或 agent-core 侧钉。
        var payload = payloadOf(output, "2");
        assertThat(payload.get("isCompacting").asBoolean()).isFalse();
        assertThat(payload.get("pendingMessageCount").asInt()).isZero();
        assertThat(payload.get("messageCount").asInt()).isZero();
        assertThat(payload.get("isStreaming").asBoolean()).isFalse();
        assertThat(payload.get("sessionId").asText()).isEqualTo("session");
        assertThat(payload.get("sessionName").asText()).isEqualTo("session");
        assertThat(payload.get("steeringMode").asText()).isEqualTo("one-at-a-time");
        assertThat(payload.get("followUpMode").asText()).isEqualTo("one-at-a-time");
        // --no-session ⇒ 无落盘路径。pi 的 `sessionFile?: string`（rpc-types.ts:103）
        // 是可选的，JSON.stringify 对 undefined **省略键** —— 必须是缺键，
        // **不是** `"sessionFile":null`（NON_NULL 是契约的一部分）。
        assertThat(payload.has("sessionFile")).isFalse();
    }

    @Test
    void getStatePendingMessageCountCountsOnlySteerAndFollowUp() throws Exception {
        // pi 的 pendingMessageCount = _steeringMessages.length + _followUpMessages.length
        // （agent-session.ts:1619-1621）—— **不含** nextRun 队列。这颗哨兵就是钉住
        // 「不数 nextRun」这一条：三个队列各放一条，计数必须是 2。
        var ctx = context("faux-pending", textStream("Hi"));
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(ctx.session(), new JsonlWriter(out), ctx.args());

        dispatcher.handleLine("{\"id\":\"1\",\"type\":\"get_state\"}");
        assertThat(payloadOf(out.toString(StandardCharsets.UTF_8), "1")
            .get("pendingMessageCount").asInt()).isZero();

        var harness = ctx.session().harness();
        var lane = ctx.session().laneName();
        harness.steer(lane, "steer me");
        harness.followUp(lane, "follow up");
        harness.nextRun(lane, "next run");

        out.reset();
        dispatcher.handleLine("{\"id\":\"2\",\"type\":\"get_state\"}");

        var payload = payloadOf(out.toString(StandardCharsets.UTF_8), "2");
        assertThat(payload.get("pendingMessageCount").asInt())
            .as("steer 1 + followUp 1；nextRun 不计入（pi 只数那两个数组）")
            .isEqualTo(2);
        // 反向：排队的消息**不在**转录里 —— 计数不是 messageCount 的别名。
        assertThat(payload.get("messageCount").asInt()).isZero();
    }

    @Test
    void lastBatchModeAndRetryCommands() throws Exception {
        var ctx = context("faux-modes", textStream("Hi"));
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(ctx.session(), new JsonlWriter(out), ctx.args());

        dispatcher.handleLine("{\"id\":\"1\",\"type\":\"set_steering_mode\",\"mode\":\"all\"}");
        dispatcher.handleLine("{\"id\":\"2\",\"type\":\"set_follow_up_mode\",\"mode\":\"one-at-a-time\"}");
        dispatcher.handleLine("{\"id\":\"3\",\"type\":\"set_auto_retry\",\"enabled\":true}");
        dispatcher.handleLine("{\"id\":\"4\",\"type\":\"abort_retry\"}");
        dispatcher.handleLine("{\"id\":\"5\",\"type\":\"abort_bash\"}");

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"command\":\"set_steering_mode\",\"success\":true");
        assertThat(output).contains("\"command\":\"set_follow_up_mode\",\"success\":true");
        assertThat(output).contains("\"command\":\"set_auto_retry\",\"success\":true");
        assertThat(output).contains("\"command\":\"abort_retry\",\"success\":true");
        assertThat(output).contains("\"command\":\"abort_bash\",\"success\":true");

        // 模式/重试状态实际生效
        assertThat(ctx.session().harness().steeringMode()).isEqualTo(new QueueMode.All());
        assertThat(ctx.session().harness().followUpMode()).isEqualTo(new QueueMode.OneAtATime());
        assertThat(ctx.session().autoRetryEnabled()).isTrue();
    }

    @Test
    void bashCommandRunsAndReturnsResult() throws Exception {
        var ctx = context("faux-bash", textStream("Hi"));
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(ctx.session(), new JsonlWriter(out), ctx.args());

        dispatcher.handleLine("{\"id\":\"1\",\"type\":\"bash\",\"command\":\"echo hello\"}");

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"command\":\"bash\",\"success\":true")
            .contains("\"output\":\"hello")
            .contains("\"exitCode\":0");
    }

    @Test
    void sessionQueryAndExportCommands() throws Exception {
        var ctx = context("faux-query", textStream("Hi there"));
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(ctx.session(), new JsonlWriter(out), ctx.args());

        // 先跑一轮 prompt 填充 transcript
        dispatcher.handleLine("{\"id\":\"p\",\"type\":\"prompt\",\"message\":\"hi\"}");
        awaitSettled(out);

        var transcript = ctx.session().harness()
            .snapshot(ctx.session().laneName()).transcript();
        assertThat(transcript).isNotEmpty();
        String firstEntryId = transcript.get(0).id();

        dispatcher.handleLine("{\"id\":\"1\",\"type\":\"get_fork_messages\"}");
        dispatcher.handleLine("{\"id\":\"2\",\"type\":\"get_entries\"}");
        dispatcher.handleLine("{\"id\":\"3\",\"type\":\"get_tree\"}");
        dispatcher.handleLine("{\"id\":\"4\",\"type\":\"export_html\"}");
        dispatcher.handleLine("{\"id\":\"5\",\"type\":\"switch_session\",\"sessionPath\":\"unknown-session\"}");
        dispatcher.handleLine("{\"id\":\"6\",\"type\":\"clone\"}");
        dispatcher.handleLine("{\"id\":\"7\",\"type\":\"fork\",\"entryId\":\"" + firstEntryId + "\"}");

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"command\":\"get_fork_messages\",\"success\":true")
            .contains("\"messages\":[");
        assertThat(output).contains("\"command\":\"get_entries\",\"success\":true")
            .contains("\"entries\":[");
        assertThat(output).contains("\"command\":\"get_tree\",\"success\":true")
            .contains("\"tree\":[");
        assertThat(output).contains("\"command\":\"export_html\",\"success\":true")
            .contains("\"path\":");
        assertThat(output).contains("\"command\":\"switch_session\",\"success\":false")
            .contains("Session not found");
        assertThat(output).contains("\"command\":\"clone\",\"success\":true");
        assertThat(output).contains("\"command\":\"fork\",\"success\":true");
    }

    @Test
    void autoRetryRerunsAfterError() throws Exception {
        // 3d：环 A 住引擎、默认开启（pi retry.enabled ?? true），不再手动 setter
        // （那如今是落盘写）；错误文本必须命中 pi 白名单才会重试（"boom" 不可重试）。
        var partial = AssistantMessage.empty().withStopReason("error");
        var errorSeq = List.<StreamEvent>of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.StreamError("error", new RuntimeException("overloaded"), partial));
        var ctx = context("faux-retry",
            List.of(errorSeq, textStream("recovered").get(0)));

        var events = new java.util.concurrent.CopyOnWriteArrayList<AgentSessionEvent>();
        try (var sub = ctx.session().subscribe(events::add)) {
            // 退避 2s（baseDelayMs 默认）⇒ 超时放宽。
            var status = ctx.session().processPrompt("go")
                .statusFuture().get(15, TimeUnit.SECONDS);
            assertThat(status.exitCode()).isEqualTo(0);
            assertThat(status.reason()).isEqualTo("stop");
        }
        // 首轮 error → 引擎环 A 退避续跑 → 第二轮成功：AutoRetryStart/End 均发射
        assertThat(events).anyMatch(e -> e instanceof AgentSessionEvent.AutoRetryStart);
        assertThat(events).anyMatch(e ->
            e instanceof AgentSessionEvent.AutoRetryEnd end && end.success());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * 取出某条 response 行的 {@code data} 载荷（信封 = {@code {id, command, success, data}}）。
     */
    private static JsonNode payloadOf(String output, String id) throws Exception {
        var mapper = new ObjectMapper();
        for (String line : output.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            var node = mapper.readTree(line);
            if ("response".equals(asText(node, "type")) && id.equals(asText(node, "id"))) {
                return node.get("data");
            }
        }
        throw new AssertionError("no response payload for id=" + id + " in:\n" + output);
    }

    private static String asText(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private record Ctx(AgentSession session, com.pijava.coding.agent.cli.Args args) {}

    private Ctx context(String provider, List<List<StreamEvent>> sequences) throws Exception {
        var tmp = Files.createTempDirectory("pi-java-rpc-test");
        var args = ArgsParser.parse(new String[] {
            "--provider", provider, "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence(provider, sequences));
        // 3d：setRetryEnabled 即刻落盘（pi save 同义），set_auto_retry RPC 会走它。
        // FileSettingsStorage 在构造时捕获 user.home，构造发生在 AgentSession.create
        // 里 ⇒ 属性窗口只包住 create，测试不碰真实用户目录。
        var home = Files.createTempDirectory("pi-java-rpc-home");
        String savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var session = AgentSession.create(args, providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()));
            return new Ctx(session, args);
        } finally {
            if (savedHome != null) {
                System.setProperty("user.home", savedHome);
            }
        }
    }

    private static List<List<StreamEvent>> textStream(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason("stop");
        return List.of(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone("stop", null, done)));
    }

    private static void awaitOutput(ByteArrayOutputStream out, String fragment) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (out.toString(StandardCharsets.UTF_8).contains(fragment)) {
                return;
            }
            sleepQuietly(20);
        }
        throw new AssertionError("timed out waiting for: " + fragment);
    }

    private static void awaitResult(AtomicReference<RpcExtensionUIResponse> ref) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (ref.get() != null) {
                return;
            }
            sleepQuietly(20);
        }
        throw new AssertionError("extension UI request did not complete");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 轮询输出直到出现 agent_settled（异步事件），带超时。 */
    private static String awaitSettled(ByteArrayOutputStream out) throws IOException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            String s = out.toString(StandardCharsets.UTF_8);
            if (s.contains("agent_settled")) {
                return s;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
