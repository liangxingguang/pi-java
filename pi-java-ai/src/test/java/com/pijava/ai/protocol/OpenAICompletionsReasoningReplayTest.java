package com.pijava.ai.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B19 发侧：{@code openai-completions} 车道**回放** reasoning
 * （docs/31 §8.35.1-（2），pi {@code openai-completions.ts:1310-1318} / {@code :1356-1362}）。
 *
 * <p>pi 有**两条独立规则**，现役实现把 (i) 顶替掉了、只留 (ii) 的近似：</p>
 * <ol>
 *   <li><b>签名回填</b>：thinking 块自带的签名（收侧写的就是命中的线格字段名）决定
 *       发回哪个字段，<b>无 provider 门</b>；</li>
 *   <li><b>补空串</b>：deepseek 一类的 relay 要求助手历史消息**必带**
 *       {@code reasoning_content}，缺了就补 {@code ""}（仅在模型是推理模型时）。</li>
 * </ol>
 *
 * <p>断言打在**桩录到的请求体**上（不是参数对象）—— 序列化后才是线格真值。</p>
 */
class OpenAICompletionsReasoningReplayTest {

    // ── 规则 (i)：签名决定字段名 ────────────────────────────────────────

    @Test
    void reasoningContentSignatureReplaysThatFieldName() throws Exception {
        try (var server = new RecordingServer()) {
            var model = model("openai", "glm-5.3-flash", Set.of(ModelCapability.TEXT), ModelCompat.NONE);
            var body = body(server, model, assistant(model, thinking("REASON-A", "reasoning_content"),
                new ContentBlock.TextContent("answer")));

            assertThat(body).contains("\"reasoning_content\":\"REASON-A\"");
        }
    }

    /**
     * 签名 {@code reasoning} ⇒ 发回 {@code reasoning} —— <b>今天必红</b>：现役代码只在
     * provider 名为 {@code deepseek} 时发 {@code reasoning_content}，非 deepseek 的 relay
     * 上 thinking **永远发不回去**（pi 会发）。
     */
    @Test
    void reasoningSignatureReplaysThatFieldName() throws Exception {
        try (var server = new RecordingServer()) {
            var model = model("openai", "glm-5.3-flash", Set.of(ModelCapability.TEXT), ModelCompat.NONE);
            var body = body(server, model, assistant(model, thinking("REASON-A", "reasoning"),
                new ContentBlock.TextContent("answer")));

            assertThat(body).contains("\"reasoning\":\"REASON-A\"");
            assertThat(body).doesNotContain("\"reasoning_content\"");
        }
    }

    /** 未知签名（如 Anthropic 的）⇒ 不发任何 reasoning 字段，只留 (ii) 的空串。 */
    @Test
    void unknownSignatureDropsTheTextAndKeepsOnlyTheEmptyFill() throws Exception {
        try (var server = new RecordingServer()) {
            var model = model("deepseek", "deepseek-v4-pro",
                Set.of(ModelCapability.TEXT, ModelCapability.THINKING), ModelCompat.NONE);
            var body = body(server, model, assistant(model, thinking("REASON-A", "not-a-wire-field"),
                new ContentBlock.TextContent("answer")));

            assertThat(body).doesNotContain("REASON-A");
            assertThat(body).contains("\"reasoning_content\":\"\"");
        }
    }

    /** 多块同签名 ⇒ 用 {@code "\n"} 连接后一次发出（pi {@code :1317}）。 */
    @Test
    void multipleThinkingBlocksJoinWithNewline() throws Exception {
        try (var server = new RecordingServer()) {
            var model = model("openai", "glm-5.3-flash", Set.of(ModelCapability.TEXT), ModelCompat.NONE);
            var body = body(server, model, assistant(model,
                thinking("A", "reasoning"), thinking("B", "reasoning"),
                new ContentBlock.TextContent("answer")));

            assertThat(body).contains("\"reasoning\":\"A\\nB\"");
        }
    }

    /**
     * 纯空白块不算推理（pi {@code :1289} 按 {@code trim()} 过滤）：它不参与连接，
     * 也不参与「第一个非空块」的签名选取。
     */
    @Test
    void whitespaceOnlyThinkingBlocksAreExcludedFromTheJoin() throws Exception {
        try (var server = new RecordingServer()) {
            var model = model("openai", "glm-5.3-flash", Set.of(ModelCapability.TEXT), ModelCompat.NONE);
            var body = body(server, model, assistant(model,
                thinking("   ", "reasoning"), thinking("real", "reasoning"),
                new ContentBlock.TextContent("answer")));

            assertThat(body).contains("\"reasoning\":\"real\"");
        }
    }

    // ── 规则 (ii)：deepseek 一类 relay 的空串回填 ───────────────────────

    @Test
    void deepseekProviderGetsTheEmptyReasoningContent() throws Exception {
        try (var server = new RecordingServer()) {
            var model = model("deepseek", "deepseek-v4-pro",
                Set.of(ModelCapability.TEXT, ModelCapability.THINKING), ModelCompat.NONE);
            var body = body(server, model,
                assistant(model, new ContentBlock.TextContent("answer")));

            assertThat(body).contains("\"reasoning_content\":\"\"");
        }
    }

    /**
     * provider 名不含 deepseek，但 baseUrl 指向 deepseek.com ⇒ 同样回填
     * （pi {@code detectCompat:1592} 的两条判据）。
     *
     * <p>桩的 baseUrl 把 {@code deepseek.com} 放在**路径**里 —— 探测是子串匹配，
     * 而夹具必须打到本地端口才录得到请求体。</p>
     */
    @Test
    void deepseekBaseUrlGetsTheEmptyReasoningContent() throws Exception {
        try (var server = new RecordingServer("/deepseek.com/v1")) {
            var model = model("my-relay", "some-reasoner",
                Set.of(ModelCapability.TEXT, ModelCapability.THINKING), ModelCompat.NONE);
            var body = body(server, model,
                assistant(model, new ContentBlock.TextContent("answer")));

            assertThat(body).contains("\"reasoning_content\":\"\"");
        }
    }

    /** 非推理模型不回填 —— pi 的条件是 {@code compat && model.reasoning}（{@code :1357}）。 */
    @Test
    void nonReasoningModelGetsNoEmptyFill() throws Exception {
        try (var server = new RecordingServer()) {
            var model = model("deepseek", "deepseek-v4-flash",
                Set.of(ModelCapability.TEXT), ModelCompat.NONE);
            var body = body(server, model,
                assistant(model, new ContentBlock.TextContent("answer")));

            assertThat(body).doesNotContain("\"reasoning_content\"");
        }
    }

    /** 用户显式关掉（models.json {@code compat}）⇒ 不回填。 */
    @Test
    void explicitCompatFalseDisablesTheEmptyFill() throws Exception {
        try (var server = new RecordingServer()) {
            var model = model("deepseek", "deepseek-v4-pro",
                Set.of(ModelCapability.TEXT, ModelCapability.THINKING),
                new ModelCompat(false, false, true));
            var body = body(server, model,
                assistant(model, new ContentBlock.TextContent("answer")));

            assertThat(body).doesNotContain("\"reasoning_content\"");
        }
    }

    /** 非 deepseek relay 上什么都不补（对照：判据不是「凡推理模型都补」）。 */
    @Test
    void ordinaryRelayGetsNoEmptyFill() throws Exception {
        try (var server = new RecordingServer()) {
            var model = model("openai", "glm-5.3-flash",
                Set.of(ModelCapability.TEXT, ModelCapability.THINKING), ModelCompat.NONE);
            var body = body(server, model,
                assistant(model, new ContentBlock.TextContent("answer")));

            assertThat(body).doesNotContain("\"reasoning_content\"");
        }
    }

    // ── 空消息的落线规则 ────────────────────────────────────────────────

    /**
     * 只有 thinking、没有文本也没有工具调用的助手消息**整条丢掉**
     * （pi {@code :1365-1372}）。行车事故里那条「1014 token 换回空消息」的记录
     * 正是这个形状 —— 落进历史后必须不发出去。
     */
    @Test
    void thinkingOnlyAssistantMessageIsDropped() throws Exception {
        try (var server = new RecordingServer()) {
            var model = model("openai", "glm-5.3-flash", Set.of(ModelCapability.TEXT), ModelCompat.NONE);
            var body = body(server, model,
                assistant(model, thinking("REASON-A", "reasoning_content")));

            assertThat(body).doesNotContain("\"role\":\"assistant\"");
        }
    }

    // ── 夹具脚手架 ──────────────────────────────────────────────────────

    private static ModelInfo model(String provider, String modelName,
                                   Set<ModelCapability> caps, ModelCompat compat) {
        return new ModelInfo(ModelId.of(provider, modelName), modelName,
            Set.copyOf(caps), 128_000, 16_384, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), Map.of(), compat);
    }

    /**
     * 助手消息的**身份必须与请求目标一致** —— 否则共享预通道把它判成跨模型重放，
     * thinking 块在到达本车道之前就被降级成文本，整组夹具会去测另一条路径。
     */
    private static Message.AssistantMessage assistant(ModelInfo model, ContentBlock... content) {
        return new Message.AssistantMessage(List.of(content), "stop", null,
            "openai-completions", model.id().provider(), model.id().modelName(),
            null, null, null, null);
    }

    private static ContentBlock thinking(String text, String signature) {
        return new ContentBlock.ThinkingContent(text, signature, false);
    }

    /** 跑一次真实请求，返回桩录到的请求体。 */
    private static String body(RecordingServer server, ModelInfo model,
                               Message.AssistantMessage assistant) {
        var api = new OpenAICompletionsApi(
            new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
            "OPENAI_API_KEY");
        var request = new StreamRequest(model, null,
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))), assistant),
            List.of(), -1, -1, Map.of());
        drainQuietly(() -> api.streamBlocking(request, ApiOptions.defaults()));
        return server.body();
    }

    /** 消费整条流并**吞掉**异常 —— 桩回 400，请求体已经录到了。 */
    private static void drainQuietly(java.util.function.Supplier<StreamIterator> supplier) {
        try (var iter = supplier.get()) {
            var events = new ArrayList<StreamEvent>();
            while (iter.hasNext() && events.size() < 100) {
                events.add(iter.next());
            }
        } catch (Exception ignored) {
            // 桩只负责录制请求体
        }
    }

    /** 录制**最后一次请求体**的桩服务器：接所有路径，回 400 让车道尽快收场。 */
    private static final class RecordingServer implements AutoCloseable {

        private final HttpServer server;
        private final String pathPrefix;
        private final AtomicReference<String> body = new AtomicReference<>("");

        RecordingServer() throws IOException {
            this("/v1");
        }

        RecordingServer(String pathPrefix) throws IOException {
            this.pathPrefix = pathPrefix;
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (InputStream in = exchange.getRequestBody();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                body.set(out.toString(StandardCharsets.UTF_8));
            }
            byte[] payload = "{\"error\":{\"message\":\"recorded\",\"type\":\"invalid_request_error\"}}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort() + pathPrefix;
        }

        String body() {
            return body.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
