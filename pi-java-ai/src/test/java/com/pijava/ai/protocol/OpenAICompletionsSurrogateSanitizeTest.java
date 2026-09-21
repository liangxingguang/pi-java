package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.openai.models.chat.completions.ChatCompletionCreateParams;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A0 步3（{@code docs/43 D4}）：OpenAI-completions 车道的孤对代理净化落点 ——
 * pi {@code openai-completions.ts} 的 7 处调用在 pi-java 收敛为 <b>4 个文本落线点</b>。
 *
 * <table>
 *   <caption>pi → java 落点对照</caption>
 *   <tr><th>pi</th><th>语义</th><th>java</th><th>口径</th></tr>
 *   <tr><td>{@code :1251}</td><td>instruction/system 文本</td><td>{@code buildParams} 的 {@code addSystemMessage}</td><td>整串</td></tr>
 *   <tr><td>{@code :1257}/{@code :1264}</td><td>user 串 ／ user 文本块</td><td>{@code addUserMessage}（java 恒为串形态）</td><td>整串（＝pi 串分支）</td></tr>
 *   <tr><td>{@code :1295}</td><td>assistant 文本</td><td>{@code addAssistantMessage} 的累加</td><td><b>逐块</b>（pi 先净化后 join）</td></tr>
 *   <tr><td>{@code :1416}</td><td>tool result 文本</td><td>{@code ChatCompletionToolMessageParam}</td><td>整串（pi 先 join 后净化）</td></tr>
 *   <tr><td>{@code :1359}</td><td>grammar 工具入参</td><td>—（D2：java 无 grammar 面）</td><td>不落</td></tr>
 *   <tr><td>{@code :1316}</td><td>thinking 落成 text（{@code requiresThinkingAsText} 分支）</td><td>—（java 无该 compat 分支）</td><td>面不存在</td></tr>
 * </table>
 *
 * <p>⚠️ <b>照缝（P5 家族）</b>：pi 的签名驱动 reasoning 字段（{@code :1339}
 * {@code assistantMsg[signature] = ….join("\n")}）**不净化**，而 java 的
 * {@code :544} 就是这条路径 ⇒ 同样不净化，并由
 * {@link #signatureDrivenReasoningFieldKeepsLoneSurrogate} 钉住（登记，不是期望行为）。</p>
 */
class OpenAICompletionsSurrogateSanitizeTest {

    private static final char HIGH = (char) 0xD83D;
    private static final char LOW = (char) 0xDE48;
    private static final String EMOJI = "🙈";
    private static final String DIRTY = "Text " + HIGH + " here";
    private static final String CLEAN = "Text  here";
    private static final String PAIRED = "Hi " + EMOJI + " there";

    // ── 出参构造与观测面 ──────────────────────────────────────────────────

    private static ChatCompletionCreateParams buildParams(String systemPrompt, List<Message> messages) {
        var request = new StreamRequest(ModelId.of("deepseek", "deepseek-chat"), systemPrompt,
            messages, List.of(), 100, 0.5, Map.of());
        return OpenAICompletionsApi.buildParams(request, "openai-completions", null);
    }

    /** 出参里**全部**文本载荷（system ／ user ／ assistant 文本 ／ tool result）。 */
    private static List<String> payloads(ChatCompletionCreateParams params) {
        var out = new ArrayList<String>();
        for (var m : params.messages()) {
            if (m.isSystem() && m.asSystem().content().isText()) {
                out.add(m.asSystem().content().asText());
            } else if (m.isUser() && m.asUser().content().isText()) {
                out.add(m.asUser().content().asText());
            } else if (m.isTool() && m.asTool().content().isText()) {
                out.add(m.asTool().content().asText());
            } else if (m.isAssistant() && m.asAssistant().content().isPresent()) {
                var content = m.asAssistant().content().get();
                if (content.isText()) out.add(content.asText());
            }
        }
        return out;
    }

    private static void assertSanitized(String where, ChatCompletionCreateParams params) {
        var actual = payloads(params);
        assertThat(actual).as("%s · 期望见到净化后文本", where).contains(CLEAN);
        assertThat(actual).as("%s · 不许有孤对代理原样出站", where)
            .noneMatch(OpenAICompletionsSurrogateSanitizeTest::hasLoneSurrogate);
    }

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

    private static Message assistant(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), "stop", null,
            "openai-completions", "deepseek", "deepseek-chat", null, null, null, null);
    }

    private static Message toolResult(String text) {
        return new Message.ToolResultMessage("call_1", "ls",
            List.of(new ContentBlock.TextContent(text)), false);
    }

    private static Message toolUse() {
        return assistant(new ContentBlock.ToolUseContent("call_1", "ls", Map.of()));
    }

    // ── 四个落线点 ────────────────────────────────────────────────────────

    @Test
    void systemMessageIsSanitized() {
        assertSanitized("system", buildParams(DIRTY, List.of(user("hi"))));
    }

    @Test
    void userMessageIsSanitized() {
        assertSanitized("user", buildParams(null, List.of(user(DIRTY))));
    }

    @Test
    void toolResultTextIsSanitized() {
        assertSanitized("tool result", buildParams(null,
            List.of(user("hi"), toolUse(), toolResult(DIRTY))));
    }

    @Test
    void assistantTextIsSanitized() {
        assertSanitized("assistant", buildParams(null, List.of(user("hi"),
            assistant(new ContentBlock.TextContent(DIRTY)))));
    }

    /**
     * <b>逐块</b>净化，不是「先拼接再净化」（pi {@code :1295} 是
     * {@code map(sanitizeSurrogates).join("")}）。
     *
     * <p>判别串刻意跨块边界造：块1 以孤高收尾、块2 以孤低开头。逐块 ⇒ 两个孤对被各自删除，
     * 得 {@code "AB"}；先拼接 ⇒ 两个孤对**配对**成活 emoji，得 {@code "A🙈B"}。两种口径
     * 在这里给出不同答案，故这一条就是该落点「逐块」语义的牙。</p>
     */
    @Test
    void assistantTextIsSanitizedPerBlockNotAcrossBlocks() {
        var params = buildParams(null, List.of(user("hi"),
            assistant(new ContentBlock.TextContent("A" + HIGH),
                new ContentBlock.TextContent(LOW + "B"))));

        assertThat(payloads(params)).as("逐块净化 ⇒ 跨块边界不许成对").contains("AB");
        assertThat(payloads(params)).as("拼接后再净化才会出现的形态").doesNotContain("A" + EMOJI + "B");
    }

    // ── 照缝（登记）与回归门 ──────────────────────────────────────────────

    /**
     * <b>照缝登记</b>（P5 家族）：签名驱动的 reasoning 字段**不净化** —— pi
     * {@code :1339} 就是 {@code assistantMsg[signature] = nonEmptyThinkingBlocks.map(b =>
     * b.thinking).join("\n")}，没有 {@code sanitizeSurrogates}；java 的 {@code :544} 是
     * 同一路径（同一模型 + 已知线格名 + 非空 thinking）。本条件**钉住这个缝不被顺手补掉**：
     * 补了才是行为偏离（中央净化会多净化 pi 不净化的东西，{@code docs/43 D1}）。</p>
     *
     * <p>断言读的是**出参对象**（{@code JsonValue.convert(String.class)} 不做 JSON 往返）
     * ⇒ 与序列化器无关，只看这条路径有没有净化。</p>
     */
    @Test
    void signatureDrivenReasoningFieldKeepsLoneSurrogate() {
        var params = buildParams(null, List.of(user("hi"),
            // ⚠️ 必须带一个可见文本块：只带 thinking 的助手消息在 pi 与 java 两侧都**整条丢弃**
            // （pi :1365-1372 / java :561 —— reasoning 不算内容）⇒ 那样这个缝根本到不了线上。
            assistant(new ContentBlock.TextContent("visible"),
                new ContentBlock.ThinkingContent(DIRTY, "reasoning_content"))));

        var assistantParam = params.messages().stream()
            .filter(m -> m.isAssistant()).findFirst().orElseThrow().asAssistant();
        var reasoning = assistantParam._additionalProperties().get("reasoning_content");

        assertThat(reasoning).as("reasoning 字段在场").isNotNull();
        var reasoningText = reasoning.convert(String.class);
        assertThat(reasoningText).as("照缝：reasoning 字段的值原样未净化").isEqualTo(DIRTY);
        assertThat(hasLoneSurrogate(reasoningText)).as("孤对代理确实还在").isTrue();
    }

    /** 回归门：配对 emoji 在所有落点逐字保留。 */
    @Test
    void pairedEmojiSurvivesEverySite() {
        var params = buildParams(PAIRED, List.of(user(PAIRED),
            assistant(new ContentBlock.TextContent(PAIRED)), toolUse(), toolResult(PAIRED)));

        assertThat(payloads(params)).as("配对 emoji 逐字保留").containsOnly(PAIRED);
    }

    // ── 线格：出站请求体 ──────────────────────────────────────────────────

    /** 端到端：脏串经车道后，出站体里必须是净化后文本，且无孤对代理的任何形态。 */
    @Test
    void capturedRequestBodyHasNoLoneSurrogate() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "DEEPSEEK_API_KEY");
            var request = new StreamRequest(ModelId.of("deepseek", "deepseek-chat"), DIRTY,
                List.of(user(DIRTY), toolUse(), assistant(new ContentBlock.TextContent(DIRTY)),
                    toolResult(DIRTY)),
                List.of(), 100, 0.5, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) events.add(iter.next());
            } catch (Exception ignored) {
                // 桩回 400，请求体已录到
            }

            var body = server.body();
            assertThat(hasLoneSurrogate(body)).as("出站体 · 整体无孤对代理").isFalse();
            assertThat(body).as("出站体 · 无 JSON 转义形态的孤高代理").doesNotContain("\\uD83D");
            assertThat(body).as("出站体 · 净化后文本在场").contains(CLEAN);
        }
    }
}