package com.pijava.ai.protocol;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A0 步4（{@code docs/43 D4}）：Mistral 车道的**请求面**孤对代理净化落点 ——
 * pi {@code mistral-conversations.ts} 的 9 处调用里，6 处请求面在 pi-java 收敛为
 * <b>4 个文本落线点</b>（另 3 处在**响应面**，属步5）。
 *
 * <table>
 *   <caption>pi → java 落点对照</caption>
 *   <tr><th>pi</th><th>语义</th><th>java</th><th>口径</th></tr>
 *   <tr><td>{@code :789}</td><td>system 消息 content</td><td>{@code toMistralMessages} 的 system</td><td>整串</td></tr>
 *   <tr><td>{@code :795}</td><td>user 串 content</td><td>user 分支（java 恒串形态）</td><td>整串</td></tr>
 *   <tr><td>{@code :802}</td><td>user 文本项</td><td>同上（java 无 parts 形态）</td><td>逐项</td></tr>
 *   <tr><td>{@code :822}</td><td>assistant 文本块</td><td>assistant 分支</td><td><b>逐块</b>（pi 先净化后 join）</td></tr>
 *   <tr><td>{@code :830}</td><td>assistant thinking 块</td><td>—（java 的 extractText 只收 TextContent）</td><td>面不存在</td></tr>
 *   <tr><td>{@code :853}</td><td>tool result 文本 part</td><td>tool 分支</td><td><b>逐块</b>（pi 先净化后 join）</td></tr>
 * </table>
 *
 * <p>观测面＝{@code buildRequestBody} 的**出参 JSON 串**（反射私有方法；Mistral 车道的
 * 序列化自带 {@code ObjectMapper} ⇒ 不需要网络，也不用 SDK 的字节生成器）。</p>
 */
class MistralSurrogateSanitizeTest {

    private static final char HIGH = (char) 0xD83D;
    private static final char LOW = (char) 0xDE48;
    private static final String EMOJI = "🙈";
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

    // ── 出参构造与观测面 ──────────────────────────────────────────────────

    private static String body(String systemPrompt, List<Message> messages) throws Exception {
        var api = new MistralConversationsApi(new ApiOptions(
            "http://localhost:1", "test-key", Duration.ofSeconds(5), 0, Map.of()));
        var method = MistralConversationsApi.class.getDeclaredMethod(
            "buildRequestBody", StreamRequest.class);
        method.setAccessible(true);
        var request = new StreamRequest(ModelId.of("mistral", "devstral-medium-latest"),
            systemPrompt, messages, List.of(), 100, 0.5, Map.of());
        return (String) method.invoke(api, request);
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message assistant(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), "stop", null,
            "mistral-conversations", "mistral", "devstral-medium-latest", null, null, null, null);
    }

    private static Message toolResult(String text) {
        return new Message.ToolResultMessage("call_1", "ls",
            List.of(new ContentBlock.TextContent(text)), false);
    }

    private static Message toolUse() {
        return assistant(new ContentBlock.ToolUseContent("call_1", "ls", Map.of()));
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

    private static void assertSanitized(String where, String bodyText, String tag) {
        assertThat(bodyText).as("%s · 净化后文本在场", where).contains(clean(tag));
        assertThat(hasLoneSurrogate(bodyText)).as("%s · 整体无孤对代理", where).isFalse();
    }

    // ── 四个落线点 ────────────────────────────────────────────────────────

    @Test
    void systemMessageIsSanitized() throws Exception {
        assertSanitized("system", body(dirty(SYS), List.of(user("hi"))), SYS);
    }

    @Test
    void userMessageIsSanitized() throws Exception {
        assertSanitized("user", body(null, List.of(user(dirty(USER)))), USER);
    }

    @Test
    void assistantTextIsSanitized() throws Exception {
        assertSanitized("assistant", body(null, List.of(user("hi"),
            assistant(new ContentBlock.TextContent(dirty(ASSIST))))), ASSIST);
    }

    @Test
    void toolResultTextIsSanitized() throws Exception {
        assertSanitized("tool result", body(null,
            List.of(user("hi"), toolUse(), toolResult(dirty(TOOL)))), TOOL);
    }

    /**
     * <b>逐块</b>净化（assistant 与 tool result 两侧同口径：pi 先 {@code map(sanitize)} 再
     * {@code join}）。判别串跨块边界造：块1 尾孤高 ＋ 块2 首孤低 ⇒ 逐块 {@code "AB"}、
     * 先拼接则 {@code "A🙈B"}。
     */
    @Test
    void assistantAndToolTextAreSanitizedPerBlockNotAcrossBlocks() throws Exception {
        var assistantBody = body(null, List.of(user("hi"),
            assistant(new ContentBlock.TextContent("A" + HIGH),
                new ContentBlock.TextContent(LOW + "B"))));
        assertThat(assistantBody).as("assistant 逐块 ⇒ 跨块边界不许成对").contains("AB");
        assertThat(assistantBody).as("拼接后再净化才会出现的形态").doesNotContain("A" + EMOJI + "B");

        var toolBody = body(null, List.of(user("hi"), toolUse(),
            new Message.ToolResultMessage("call_1", "ls", List.of(
                new ContentBlock.TextContent("A" + HIGH),
                new ContentBlock.TextContent(LOW + "B")), false)));
        assertThat(toolBody).as("tool result 逐块 ⇒ 跨块边界不许成对").contains("AB");
        assertThat(toolBody).as("拼接后再净化才会出现的形态").doesNotContain("A" + EMOJI + "B");
    }

    /** 回归门：配对 emoji 在所有落点逐字保留。 */
    @Test
    void pairedEmojiSurvivesEverySite() throws Exception {
        var paired = "Hi " + EMOJI + " there";
        var bodyText = body(paired, List.of(user(paired),
            assistant(new ContentBlock.TextContent(paired)), toolUse(), toolResult(paired)));

        assertThat(bodyText).as("配对 emoji 逐字保留").contains(paired);
        assertThat(hasLoneSurrogate(bodyText)).as("配对 emoji 不算孤对").isFalse();
    }
}