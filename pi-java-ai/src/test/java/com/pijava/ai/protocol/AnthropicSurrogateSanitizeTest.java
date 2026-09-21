package com.pijava.ai.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.anthropic.models.messages.MessageCreateParams;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A0 步2（{@code docs/43 D4}）：Anthropic 车道的孤对代理净化落点 ——
 * pi {@code anthropic-messages.ts} 的 11 处调用在 pi-java 收敛为 <b>6 个字符串落线点</b>。
 *
 * <table>
 *   <caption>pi → java 落点对照</caption>
 *   <tr><th>pi</th><th>语义</th><th>java</th></tr>
 *   <tr><td>{@code :1089}/{@code :1098}</td><td>system（OAuth ／ 非 OAuth 两分支）</td><td>{@code buildParams} 的 {@code builder.system}</td></tr>
 *   <tr><td>{@code :1255}</td><td>后置 system 消息文本</td><td>java 无该面（pi-java 的 system 只有请求字段）</td></tr>
 *   <tr><td>{@code :1276}/{@code :1284}</td><td>user 串 ／ user 文本块</td><td>{@code toBlockParams} 文本块</td></tr>
 *   <tr><td>{@code :1318}</td><td>assistant 文本块</td><td>同上（两角色共用）</td></tr>
 *   <tr><td>{@code :1340}/{@code :1345}/{@code :1351}</td><td>thinking 三分支</td><td>{@code appendThinkingBlock} 三分支</td></tr>
 *   <tr><td>{@code :144}/{@code :152}</td><td>tool result 文本（{@code convertContentBlocks}）</td><td>{@code toTextBlocks}</td></tr>
 * </table>
 *
 * <p>观测面＝{@link #payloads}（出参里<b>全部</b>字符串载荷，含 system），断言统一为
 * 「含净化后文本」＋「无孤对代理原样出站」—— 一条断言即覆盖「有没有落点 / 落得对不对」。</p>
 */
class AnthropicSurrogateSanitizeTest {

    private static final char HIGH = (char) 0xD83D;
    private static final char LOW = (char) 0xDE48;
    private static final String EMOJI = "🙈";
    /** 含孤高代理的脏串 ⇒ pi 删掉孤高（{@code docs/43 P1}）。 */
    private static final String DIRTY = "Text " + HIGH + " here";
    /** {@link #DIRTY} 的净化结果：孤高被删 ⇒ 留下双空格（pi JSDoc 示例）。 */
    private static final String CLEAN = "Text  here";
    /** 配对 emoji 必须逐字保留。 */
    private static final String PAIRED = "Hi " + EMOJI + " there";
    private static final ModelId<?> TARGET = ModelId.of("anthropic", "claude-sonnet-5");

    // ── 出参构造（反射，先例：AnthropicThinkingReplayTest:49-58） ──────────

    private MessageCreateParams buildParams(StreamRequest request) throws Exception {
        var options = new ApiOptions("https://api.teamorouter.cn", "sk-test",
            Duration.ofSeconds(10), 1, Map.of());
        var api = new AnthropicMessagesApi(options, "ANTHROPIC_API_KEY");
        Method method = AnthropicMessagesApi.class.getDeclaredMethod("buildParams", StreamRequest.class);
        method.setAccessible(true);
        return (MessageCreateParams) method.invoke(api, request);
    }

    /** 出参里**全部**字符串载荷（system ＋ 各消息块）—— 净化只管这一层。 */
    private static List<String> payloads(MessageCreateParams params) {
        var out = new ArrayList<String>();
        params.system().ifPresent(s -> {
            if (s.isString()) {
                out.add(s.asString());
            } else {
                s.asTextBlockParams().forEach(b -> out.add(b.text()));
            }
        });
        for (var m : params.messages()) {
            for (var b : m.content().asBlockParams()) {
                if (b.isText()) {
                    out.add(b.asText().text());
                } else if (b.isThinking()) {
                    out.add(b.asThinking().thinking());
                } else if (b.isToolResult()) {
                    var content = b.asToolResult().content().orElse(null);
                    if (content == null) {
                        continue;
                    } else if (content.isString()) {
                        out.add(content.asString());
                    } else {
                        content.asBlocks().forEach(blk -> {
                            if (blk.isText()) out.add(blk.asText().text());
                        });
                    }
                }
            }
        }
        return out;
    }

    /** 断言两面：净化后文本在场 ＋ 无孤对代理原样出站。 */
    private static void assertSanitized(String where, MessageCreateParams params) {
        var actual = payloads(params);
        assertThat(actual).as("%s · 期望见到净化后文本", where).contains(CLEAN);
        assertThat(actual).as("%s · 不许有孤对代理原样出站", where)
            .noneMatch(AnthropicSurrogateSanitizeTest::hasLoneSurrogate);
    }

    /** 孤对代理探测（与 {@code SanitizeUnicodeTest} 的 oracle 同形）。 */
    private static boolean hasLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
                continue;
            }
            if (Character.isHighSurrogate(c) || Character.isLowSurrogate(c)) return true;
        }
        return false;
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    /** 同模型助手消息（闸的同模型判据看 {@code api}/{@code provider}/{@code model} 三元）。 */
    private static Message.AssistantMessage assistant(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), "stop", null,
            "anthropic-messages", TARGET.provider(), TARGET.modelName(), null, null, null, null);
    }

    private static Message toolResult(String text) {
        return new Message.ToolResultMessage("toolu_1", "ls",
            List.of(new ContentBlock.TextContent(text)), false);
    }

    private static Message toolUse() {
        return assistant(new ContentBlock.ToolUseContent("toolu_1", "ls", Map.of()));
    }

    private static StreamRequest request(String systemPrompt, List<Message> messages) {
        return new StreamRequest(TARGET, systemPrompt, messages, List.of(), 100, 0.5, Map.of());
    }

    /** 带 {@code compat.allowEmptySignature} 的目标模型（B8 投送面）。 */
    private static StreamRequest requestWithCompat(List<Message> messages) {
        var info = new ModelInfo(TARGET, TARGET.modelName(), Set.of(), 0, 0, false,
            PricingInfo.UNKNOWN, ThinkingLevelMap.empty(), Map.of(), Map.of(), ModelCompat.of(true));
        return new StreamRequest(info, null, messages, List.of(), 100, 0.5, Map.of());
    }

    // ── 六个落线点 ────────────────────────────────────────────────────────

    @Test
    void systemPromptIsSanitized() throws Exception {
        assertSanitized("system", buildParams(request(DIRTY, List.of(user("hi")))));
    }

    @Test
    void userTextBlockIsSanitized() throws Exception {
        assertSanitized("user", buildParams(request(null, List.of(user(DIRTY)))));
    }

    @Test
    void assistantTextBlockIsSanitized() throws Exception {
        assertSanitized("assistant", buildParams(request(null, List.of(user("hi"),
            assistant(new ContentBlock.TextContent(DIRTY))))));
    }

    @Test
    void toolResultTextIsSanitized() throws Exception {
        assertSanitized("tool result", buildParams(request(null,
            List.of(user("hi"), toolUse(), toolResult(DIRTY)))));
    }

    @Test
    void thinkingWithSignatureIsSanitized() throws Exception {
        assertSanitized("thinking（有签名）", buildParams(request(null, List.of(user("hi"),
            assistant(new ContentBlock.ThinkingContent(DIRTY, "sig-1"))))));
    }

    @Test
    void thinkingWithoutSignatureDowngradedToTextIsSanitized() throws Exception {
        assertSanitized("thinking（无签名 ⇒ 降级 text）", buildParams(request(null, List.of(user("hi"),
            assistant(new ContentBlock.ThinkingContent(DIRTY, ""))))));
    }

    @Test
    void thinkingWithAllowedEmptySignatureIsSanitized() throws Exception {
        assertSanitized("thinking（allowEmptySignature ⇒ 落 thinking）",
            buildParams(requestWithCompat(List.of(user("hi"),
                assistant(new ContentBlock.ThinkingContent(DIRTY, ""))))));
    }

    // ── 回归门：配对 emoji 不被打扰 ───────────────────────────────────────

    @Test
    void pairedEmojiSurvivesEverySite() throws Exception {
        var params = buildParams(request(PAIRED, List.of(user(PAIRED),
            assistant(new ContentBlock.TextContent(PAIRED),
                new ContentBlock.ThinkingContent(PAIRED, "sig-1")),
            toolUse(), toolResult(PAIRED))));

        assertThat(payloads(params)).as("配对 emoji 逐字保留").containsOnly(PAIRED);
    }

    // ── 线格：出站请求体（端到端，含 Jackson 对孤对代理的实测口径） ────────

    /**
     * 捕获真出站请求体：{@link #DIRTY} 经车道后，体里必须出现 {@link CLEAN}，
     * 且不能有孤对代理的任何形态（JSON 转义 {@code \uD83D} ／ UTF-8 解码出的
     * {@code U+FFFD}）。
     *
     * <p>这条同时是 {@code docs/43 D3}「待实证」项的取证：见 §10 实测记录。</p>
     */
    @Test
    void capturedRequestBodyHasNoLoneSurrogate() throws Exception {
        try (var server = new RecordingServer()) {
            var api = new AnthropicMessagesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "ANTHROPIC_API_KEY");
            var request = request(DIRTY, List.of(user(DIRTY), toolUse(),
                assistant(new ContentBlock.TextContent(DIRTY)), toolResult(DIRTY)));
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) events.add(iter.next());
            } catch (Exception ignored) {
                // 桩回 400，请求体已录到
            }

            var body = server.body();
            assertThat(hasLoneSurrogate(body)).as("出站体 · 整体无孤对代理").isFalse();
            assertThat(body).as("出站体 · 无 JSON 转义形态的孤高代理")
                .doesNotContain("\\uD83D");
            assertThat(body).as("出站体 · 无裸代理（UTF-8 解码残渣）")
                .doesNotContain(String.valueOf((char) 0xFFFD));
            assertThat(body).as("出站体 · 净化后文本在场").contains(CLEAN);
        }
    }

    /** 录制**最后一次请求体**的桩服务器：根上下文接所有路径，回 400 让车道尽快收场。 */
    private static final class RecordingServer implements AutoCloseable {

        private final HttpServer server;
        private final AtomicReference<String> body = new AtomicReference<>("");

        RecordingServer() throws IOException {
            server = HttpServer.create(new java.net.InetSocketAddress(0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
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
            return "http://localhost:" + server.getAddress().getPort() + "/v1";
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