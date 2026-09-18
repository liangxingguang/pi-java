package com.pijava.agent.harness;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.protocol.OpenAICompletionsApi;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B20 跨层回归门（{@code docs/31 §8.35.14} 第八节末行）：**线格 ⇒ 消息 ⇒ {@code PiLoopRunner} 的
 * {@code length} 门**。
 *
 * <p>缺口是「夹具在宿主层，缺口在协议层」（{@code docs/31:4696}）：{@link AgentLoopL1Test} 的 ③
 * 用例用 {@code scriptedStreamFn} **伪造**一条 {@code stopReason == "length"} 的助手消息，
 * 于是它只能证明「门**给定**截断消息会关」，证不了「**某条真实车道**会从线格上产出这样一条消息」。
 * 本类补上那一段：本地 {@link HttpServer} 喂**真实** completions 线格
 * （{@code finish_reason:"length"} + 一条工具调用），经**真实** {@link OpenAICompletionsApi}
 * 落成助手消息，再交给 {@code PiLoopRunner}，断言本回合的工具**一个都没执行**。</p>
 *
 * <p>两侧任一破掉都该红：把 completions 车道的 {@code length} 映射还原成 B20 提交 ④ 之前的
 * 样子（{@code finish_reason} 完全不读 ⇒ 收尾固定发 {@code "stop"}），这条夹具红成
 * 「工具被执行了」—— 即截断的半个工具调用被当真送进了执行器。实测红灯见实施记录。</p>
 *
 * <p>⚠️ 与 {@link AgentLoopL1Test} 的分工：那边证**门本身**（宿主层语义），这边证**门的入口**是从
 * 线格上来的（协议层 → 宿主层那一跳）。两条都在，才闭环。</p>
 */
class CrossLayerLengthGateTest {

    private static final ModelId<?> MODEL = ModelId.of("openai", "test-model");

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 门（本包核心）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 线格上 {@code finish_reason:"length"} + 一条工具调用 ⇒ 助手消息带 {@code "length"}，
     * 且 {@code PiLoopTools.run(..., true)} 把本回合的工具全部作废。
     *
     * <p>三段断言分别钉住链上的三跳：①「消息 ⇒ 门」（正题：工具没被执行、回灌的失败文案是截断
     * 文案）；②「线格 ⇒ 消息」（宿主看到的第一条助手消息的取值就是线格给的）；③ 回合没被截断
     * 中断 —— 宿主按 pi 把失败结果回灌后模型重试，第二个响应正常结束。</p>
     *
     * <p><b>实测红灯</b>（变异探针 = 把 {@code OpenAICompletionsApi} 换回 B20 提交 ④ 之前的版本，
     * 那一版根本不读 {@code finish_reason}）：{@code expected: 0 but was: 1}，
     * 转录 {@code [user, assistant(tool_use), tool, assistant(stop)]} —— 截断的调用被当真送进了
     * 执行器，且回灌的是一条**成功**的工具结果。</p>
     */
    @Test
    void lengthFromTheWireStopsToolExecutionInThisTurn() {
        var executed = new AtomicInteger();
        var h = harnessWithLane(List.of(truncatedToolCallSse(), finalTextSse()), executed);

        h.prompt("truncated call");

        // ① 消息 ⇒ 门（本类的正题）：这一回合的工具一个都没执行
        assertThat(executed.get())
            .as("转录：" + names(h))
            .isZero();
        var toolResults = toolResultMessages(h);
        assertThat(toolResults).hasSize(1);
        assertThat(toolResults.get(0).isError()).isTrue();
        assertThat(((ContentBlock.TextContent) toolResults.get(0).content().get(0)).text())
            .contains("hit the output token limit");

        // ② 线格 ⇒ 消息：宿主看到的第一条助手消息带着线格的 length
        var assistants = assistantMessages(h);
        assertThat(assistants)
            .as("第一条助手消息应当来自线格，实际转录：" + names(h))
            .isNotEmpty();
        assertThat(assistants.get(0).stopReason()).isEqualTo("length");

        // ③ 回合继续，模型重试后正常结束
        assertThat(h.lastAssistantMessage().stopReason()).isEqualTo("stop");
    }

    /**
     * **对照面**：同一套夹具喂 {@code finish_reason:"tool_calls"} ⇒ 工具**执行一次**。
     *
     * <p>没有它，上面那条的 {@code executed == 0} 可能是**假绿** —— 线格写错、工具调用没被解析出来、
     * 或宿主根本没接到工具，都会让计数停在 0。这条证明「线格 ⇒ 工具调用 ⇒ 执行器」这一段本身是通的，
     * 于是上面那条的 0 只可能来自 {@code length} 门。</p>
     *
     * <p><b>实测红灯</b>（本夹具确实是靠这条才发现线格写错的）：这套 SSE 助手第一次写成了
     * {@code data: data: {...}}（{@code chunk()} 已经带前缀，外层又套了一次），
     * 于是**两条**夹具同时红成 {@code assistant(error: Error reading response)}
     * —— SDK 把整行 {@code "data: {...}"} 当 JSON 解（{@code SseMessage.kt:62-64}）。</p>
     */
    @Test
    void toolCallFinishReasonFromTheWireExecutesTheTool() {
        var executed = new AtomicInteger();
        var h = harnessWithLane(List.of(toolCallSse(), finalTextSse()), executed);

        h.prompt("normal call");

        assertThat(assistantMessages(h).get(0).stopReason())
            .as("转录：" + names(h))
            .isEqualTo("tool_use");
        assertThat(executed.get()).isEqualTo(1);
        assertThat(h.lastAssistantMessage().stopReason()).isEqualTo("stop");
    }

    // ── 宿主装配（照 AgentLoopL1Test 的形状）────────────────────────────

    private AgentHarness harnessWithLane(List<String> bodies, AtomicInteger executed) {
        var registry = new ToolRegistry(null);
        registry.register(recordingTool("echo", executed));
        var stub = sseServerFor(bodies);
        var api = new OpenAICompletionsApi(new ApiOptions(
            "http://localhost:" + stub.getAddress().getPort() + "/v1", "test-key",
            Duration.ofSeconds(5), 0, Map.of()));
        return AgentHarness.create(new HarnessConfig(
            laneStreamFn(api), MODEL, ModelThinkingLevel.off(), "",
            Set.of(), 200_000, registry, null, null,
            null, Map.of(),
            com.pijava.ai.http.RetryPolicy.defaultPolicy(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }

    /**
     * 把**真实**车道适配成 {@link StreamFn} —— 与生产适配器
     * （{@code DefaultProviders.streamBlocking}）同形：Context → {@link StreamRequest} → 车道。
     *
     * <p>刻意不复用生产那个：它在 {@code pi-java-coding-agent}，本模块（agent-core）依赖不到它，
     * 而本夹具要断言的接缝在它**下面**（车道 → 消息）。</p>
     */
    private static StreamFn laneStreamFn(OpenAICompletionsApi api) {
        return (model, context, options) -> api.streamBlocking(
            new StreamRequest(ModelInfo.minimal(model), context.systemPrompt(),
                context.messages(), ToolRegistry.definitionsOf(context.tools()),
                -1, -1, Map.of()),
            ApiOptions.defaults());
    }

    /** A tool that records how many times it actually executed. */
    private static AgentTool<String, Void> recordingTool(String name, AtomicInteger executed) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "Test tool " + name; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) {
                return String.valueOf(raw.get("text"));
            }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                executed.incrementAndGet();
                return ToolResult.success(params);
            }
        };
    }

    // ── SSE 桩 ─────────────────────────────────────────────────────────

    /**
     * 起一个本地 SSE 服务：第 n 次请求回第 n 个 body，多出来的请求一律回最后一个
     * （重试幂等；槽用尽本身不该让夹具以「服务端 500」的形式红）。
     */
    private HttpServer sseServerFor(List<String> bodies) {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot start the SSE stub", e);
        }
        var calls = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            var body = bodies.get(Math.min(calls.getAndIncrement(), bodies.size() - 1))
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    // ── completions 线格 ───────────────────────────────────────────────

    /** 截断回合：工具调用参数是完整的（pi 的截断判定只看 {@code finish_reason}），但被 length 作废。 */
    private static String truncatedToolCallSse() {
        return chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"echo\","
                + "\"arguments\":\"{\\\"text\\\":\\\"hello\\\"}\"}}]}")
            + terminal("length") + DONE;
    }

    /** 正常回合：同一个工具调用，但线格说它完整。 */
    private static String toolCallSse() {
        return chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"echo\","
                + "\"arguments\":\"{\\\"text\\\":\\\"hello\\\"}\"}}]}")
            + terminal("tool_calls") + DONE;
    }

    /** 重试后的收尾回合：一段文本 + 正常结束。 */
    private static String finalTextSse() {
        return chunk("{\"content\":\"done\"}") + terminal("stop") + DONE;
    }

    private static final String DONE = "data: [DONE]\n\n";

    private static String chunk(String deltaJson) {
        return data("{\"id\":\"chunk\",\"object\":\"chat.completion.chunk\",\"created\":0,"
            + "\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":" + deltaJson
            + ",\"finish_reason\":null}]}");
    }

    private static String terminal(String reason) {
        return data("{\"id\":\"chunk\",\"object\":\"chat.completion.chunk\",\"created\":0,"
            + "\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},"
            + "\"finish_reason\":\"" + reason + "\"}]}");
    }

    private static String data(String json) {
        return "data: " + json + "\n\n";
    }

    // ── 转录帮手 ───────────────────────────────────────────────────────

    private static List<Message.AssistantMessage> assistantMessages(AgentHarness h) {
        return messages(h).stream()
            .filter(Message.AssistantMessage.class::isInstance)
            .map(Message.AssistantMessage.class::cast)
            .toList();
    }

    private static List<Message.ToolResultMessage> toolResultMessages(AgentHarness h) {
        return messages(h).stream()
            .filter(Message.ToolResultMessage.class::isInstance)
            .map(Message.ToolResultMessage.class::cast)
            .toList();
    }

    private static List<Message> messages(AgentHarness h) {
        return h.snapshot("default").transcript().stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> ((Entry.Message) e).message())
            .toList();
    }

    /** 转录的角色 + 取值，红灯里能直接读出线格被翻成了什么。 */
    private static List<String> names(AgentHarness h) {
        return messages(h).stream()
            .map(m -> m instanceof Message.AssistantMessage a
                ? "assistant(" + a.stopReason()
                    + (a.errorMessage() == null ? "" : ": " + a.errorMessage()) + ")"
                : m.role())
            .toList();
    }
}
