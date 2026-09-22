package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A0 步4（{@code docs/43 D4}）：Google 车道的孤对代理净化落点 ——
 * pi {@code google-shared.ts}（6）＋ {@code google-generative-ai.ts}（1）的 7 处调用
 * 在 pi-java 收敛为 <b>4 个文本落线点</b>。
 *
 * <table>
 *   <caption>pi → java 落点对照</caption>
 *   <tr><th>pi</th><th>语义</th><th>java</th><th>口径</th></tr>
 *   <tr><td>{@code generative-ai:393}</td><td>systemInstruction</td><td>{@code buildConfig}</td><td>整串</td></tr>
 *   <tr><td>{@code shared:207}/{@code :212}</td><td>user 串 ／ user 文本项</td><td>{@code toGoogleParts} 文本分支（两角色共用）</td><td>整串 ／ 逐项</td></tr>
 *   <tr><td>{@code shared:242}</td><td>assistant 文本块</td><td>同上</td><td>逐块</td></tr>
 *   <tr><td>{@code shared:301}</td><td>tool result 的 responseValue</td><td>{@code toGoogleParts} 函数响应分支</td><td>选中串</td></tr>
 *   <tr><td>{@code shared:255}/{@code :262}</td><td>assistant thinking（同模型 ／ 跨模型两条分支）</td><td>—（java 一律丢 thinking，{@code :337}）</td><td>面不存在</td></tr>
 * </table>
 *
 * <p>观测面＝**真出站请求体**（本地 {@link RecordingHttpServer} ＋ SDK 的 {@code baseUrl} 覆盖）：
 * Google 车道的序列化在 {@code com.google.genai} SDK 内部，没有可反射的出参构造器
 * ⇒ 与 Anthropic 步2 同一形态。每个落点用**各自的哨兵串**，一条断言即定位到点。</p>
 *
 * <p>⚠️ <b>照缝（P5 家族）</b>：pi {@code shared:274} 的 {@code functionCall.args =
 * block.arguments ?? {}} **不净化** ⇒ java 的 {@code toGoogleParts} 工具调用分支同样不净化。</p>
 */
class GoogleSurrogateSanitizeTest {

    private static final char HIGH = (char) 0xD83D;
    private static final char LOW = (char) 0xDE48;
    private static final String EMOJI = "🙈";
    private static final ModelId<?> TARGET = ModelId.of("google", "gemini-2.5-flash");

    /** 命中位置由各家后缀区分。 */
    private static String dirty(String tag) {
        return tag + " " + HIGH + " end";
    }

    private static String clean(String tag) {
        return tag + "  end";
    }

    private static final String SYS = "sys";
    private static final String USER = "usr";
    private static final String ASSIST = "ast";
    private static final String TOOL = "tol";

    // ── 夹具 ──────────────────────────────────────────────────────────────

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message assistant(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), "stop", null,
            "google-generative-ai", TARGET.provider(), TARGET.modelName(), null, null, null, null);
    }

    private static Message toolUse(Map<String, Object> arguments) {
        return assistant(new ContentBlock.ToolUseContent("call_1", "ls", arguments));
    }

    private static Message toolResult(String text) {
        return new Message.ToolResultMessage("call_1", "ls",
            List.of(new ContentBlock.TextContent(text)), false);
    }

    /** 真出站请求体：桩回 400 让车道尽快收场，体已录到。 */
    private static String wireBody(String systemPrompt, List<Message> messages) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new GoogleGenerativeAiApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            var request = new StreamRequest(TARGET, systemPrompt, messages, List.of(), 100, 0.5, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) events.add(iter.next());
            } catch (Exception ignored) {
                // 桩回 400，请求体已录到
            }
            return server.body();
        }
    }

    private static void assertSanitized(String where, String body, String tag) {
        assertThat(body).as("%s · 净化后文本在场", where).contains(clean(tag));
        assertThat(body).as("%s · 原脏串不在场", where).doesNotContain("\\uD83D");
        assertThat(hasLoneSurrogate(body)).as("%s · 整体无孤对代理", where).isFalse();
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

    // ── 四个落线点 ────────────────────────────────────────────────────────

    @Test
    void systemInstructionIsSanitized() throws Exception {
        assertSanitized("systemInstruction", wireBody(dirty(SYS), List.of(user("hi"))), SYS);
    }

    @Test
    void userTextIsSanitized() throws Exception {
        assertSanitized("user 文本", wireBody(null, List.of(user(dirty(USER)))), USER);
    }

    @Test
    void assistantTextIsSanitized() throws Exception {
        assertSanitized("assistant 文本", wireBody(null, List.of(user("hi"),
            assistant(new ContentBlock.TextContent(dirty(ASSIST))))), ASSIST);
    }

    @Test
    void toolResultTextIsSanitized() throws Exception {
        assertSanitized("tool result", wireBody(null,
            List.of(user("hi"), toolUse(Map.of()), toolResult(dirty(TOOL)))), TOOL);
    }

    // ── 登记面与回归门 ────────────────────────────────────────────────────

    /**
     * <b>登记</b>：pi 在 {@code shared:255}/{@code :262} 净化 thinking 块，但 pi-java 的
     * Google 车道**一律丢 thinking**（{@code :337}：Gemini 有自己的思考协议）⇒ 那两个落点
     * 在 java 侧**无面**。本条钉住「丢」这件事本身，免得将来有人以为漏了净化。
     */
    @Test
    void thinkingBlocksAreDroppedNotSanitized() throws Exception {
        var body = wireBody(null, List.of(user("hi"),
            assistant(new ContentBlock.TextContent("visible"),
                new ContentBlock.ThinkingContent(dirty("thk"), "sig-1"))));

        assertThat(body).as("thinking 文本不上线（java 丢块）").doesNotContain("thk");
        assertThat(body).as("同一条助手消息的可见文本照常上线").contains("visible");
    }

    /**
     * <b>照缝登记</b>（P5 家族）：工具调用的 {@code args} **不净化** —— pi
     * {@code shared:274} 原样放 {@code block.arguments ?? {}}；java 的
     * {@code toGoogleParts} 同样原样放。钉住这个缝不被顺手补掉。
     *
     * <p>观测面取**出参**（反射私有 {@code toGoogleParts}）而不是线格：{@code com.google.genai}
     * 的序列化器把孤高代理写成 {@code ?}（{@code docs/43 §10} 实测），在线格上「未净化」
     * 与「净化」只差一个空格，判别力弱且依赖第三方编码器行为。</p>
     */
    @Test
    void functionCallArgumentsKeepLoneSurrogate() throws Exception {
        var api = new GoogleGenerativeAiApi(new ApiOptions(
            "http://localhost:1", "test-key", Duration.ofSeconds(5), 0, Map.of()));
        var method = GoogleGenerativeAiApi.class.getDeclaredMethod("toGoogleParts", ContentBlock.class);
        method.setAccessible(true);
        var parts = (List<?>) method.invoke(
            api, new ContentBlock.ToolUseContent("call_1", "ls", Map.of("text", dirty("arg"))));
        var part = (com.google.genai.types.Part) parts.get(0);

        var args = part.functionCall().orElseThrow().args().orElseThrow();
        assertThat(args.get("text")).as("照缝：args 原样透传（未净化）").isEqualTo(dirty("arg"));
        assertThat(hasLoneSurrogate((String) args.get("text"))).as("孤对代理确实还在").isTrue();
    }

    /** 回归门：配对 emoji 在所有落点逐字保留。 */
    @Test
    void pairedEmojiSurvivesEverySite() throws Exception {
        var paired = "Hi " + EMOJI + " there";
        var body = wireBody(paired, List.of(user(paired),
            assistant(new ContentBlock.TextContent(paired)), toolUse(Map.of()), toolResult(paired)));

        assertThat(body).as("配对 emoji 逐字保留（原始字符，不转义）").contains(paired);
        assertThat(body).as("配对 emoji 被拆成代理转义 ⇒ 说明净化误伤")
            .doesNotContain("\\uD83D\\uDE48");
    }
}