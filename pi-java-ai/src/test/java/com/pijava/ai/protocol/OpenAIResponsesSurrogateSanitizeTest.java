package com.pijava.ai.protocol;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A0 步3（{@code docs/43 D4}）：OpenAI-responses 车道的孤对代理净化落点 ——
 * pi {@code openai-responses-shared.ts} 的 7 处调用在 pi-java 收敛为 <b>5 个文本落线点</b>。
 *
 * <table>
 *   <caption>pi → java 落点对照</caption>
 *   <tr><th>pi</th><th>语义</th><th>java</th><th>口径</th></tr>
 *   <tr><td>{@code :224}</td><td>instruction/system 文本</td><td>{@code convertMessages} 的 system item</td><td>整串</td></tr>
 *   <tr><td>{@code :231}/{@code :238}</td><td>user 串 ／ user 文本块</td><td>{@code toUserItem} 无图分支 ／ 有图分支</td><td>整串 ／ 逐项</td></tr>
 *   <tr><td>{@code :283}</td><td>assistant 文本块</td><td>{@code addAssistantItems} 的累加</td><td><b>逐块</b>（pi 先净化后拼）</td></tr>
 *   <tr><td>{@code :92}/{@code :97}</td><td>tool result 输出（含占位串）</td><td>{@code FunctionCallOutput.Output}</td><td>选中串整体</td></tr>
 *   <tr><td>{@code :312}</td><td>grammar（custom_tool_call）入参</td><td>—（D2：java 无 grammar 面）</td><td>不落</td></tr>
 * </table>
 *
 * <p>⚠️ <b>照缝（P5 家族）</b>：pi {@code :322} 的 {@code function_call.arguments =
 * JSON.stringify(toolCall.arguments)} **不净化** ⇒ java {@code toArgumentsJson} 同样不净化，
 * 由 {@link #functionCallArgumentsKeepLoneSurrogate} 钉住（登记，不是期望行为）。</p>
 */
class OpenAIResponsesSurrogateSanitizeTest {

    private static final char HIGH = (char) 0xD83D;
    private static final char LOW = (char) 0xDE48;
    private static final String EMOJI = "🙈";
    private static final String DIRTY = "Text " + HIGH + " here";
    private static final String CLEAN = "Text  here";
    private static final String PAIRED = "Hi " + EMOJI + " there";
    private static final ModelId<?> TARGET = ModelId.of("openai", "gpt-5-mini");

    // ── 出参构造与观测面 ──────────────────────────────────────────────────

    private static ResponseCreateParams buildParams(String systemPrompt, List<Message> messages)
            throws Exception {
        var request = new StreamRequest(TARGET, systemPrompt, messages, List.of(), 100, 0.5, Map.of());
        var method = ResponsesMessageConverter.class.getDeclaredMethod(
            "buildParams", StreamRequest.class, ResponsesOptions.class, String.class, String.class);
        method.setAccessible(true);
        return (ResponseCreateParams) method.invoke(null, request, ResponsesOptions.from(ApiOptions.defaults()),
            "gpt-5-mini", "openai-responses");
    }

    /** 出参里**全部**文本载荷（system ／ user ／ assistant output_text ／ tool result 输出）。 */
    private static List<String> payloads(ResponseCreateParams params) {
        var out = new ArrayList<String>();
        for (var item : params.input().orElseThrow().asResponse()) {
            if (item.isEasyInputMessage()) {
                var content = item.asEasyInputMessage().content();
                if (content.isTextInput()) {
                    out.add(content.asTextInput());
                } else {
                    content.asResponseInputMessageContentList().forEach(c -> {
                        if (c.isInputText()) out.add(c.asInputText().text());
                    });
                }
            } else if (item.isResponseOutputMessage()) {
                for (var c : item.asResponseOutputMessage().content()) {
                    if (c.isOutputText()) out.add(c.asOutputText().text());
                }
            } else if (item.isFunctionCallOutput()) {
                var output = item.asFunctionCallOutput().output();
                if (output.isString()) out.add(output.asString());
            }
        }
        return out;
    }

    private static void assertSanitized(String where, ResponseCreateParams params) {
        var actual = payloads(params);
        assertThat(actual).as("%s · 期望见到净化后文本", where).contains(CLEAN);
        assertThat(actual).as("%s · 不许有孤对代理原样出站", where)
            .noneMatch(OpenAIResponsesSurrogateSanitizeTest::hasLoneSurrogate);
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

    private static Message userWithImage(String text) {
        return new Message.UserMessage(List.of(
            new ContentBlock.TextContent(text),
            new ContentBlock.ImageContent("image/png", "AAAA")));
    }

    private static Message assistant(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), "stop", null,
            "openai-responses", TARGET.provider(), TARGET.modelName(), null, null, null, null);
    }

    private static Message toolResult(String text) {
        return new Message.ToolResultMessage("call_1", "ls",
            List.of(new ContentBlock.TextContent(text)), false);
    }

    private static Message toolUse(Map<String, Object> arguments) {
        return assistant(new ContentBlock.ToolUseContent("call_1", "ls", arguments));
    }

    // ── 五个落线点 ────────────────────────────────────────────────────────

    @Test
    void systemMessageIsSanitized() throws Exception {
        assertSanitized("system", buildParams(DIRTY, List.of(user("hi"))));
    }

    @Test
    void userMessageIsSanitized() throws Exception {
        assertSanitized("user（无图分支）", buildParams(null, List.of(user(DIRTY))));
    }

    @Test
    void userTextPartWithImageIsSanitized() throws Exception {
        assertSanitized("user（有图分支）", buildParams(null, List.of(userWithImage(DIRTY))));
    }

    @Test
    void toolResultOutputIsSanitized() throws Exception {
        assertSanitized("tool result", buildParams(null,
            List.of(user("hi"), toolUse(Map.of()), toolResult(DIRTY))));
    }

    @Test
    void assistantTextIsSanitized() throws Exception {
        assertSanitized("assistant", buildParams(null, List.of(user("hi"),
            assistant(new ContentBlock.TextContent(DIRTY)))));
    }

    /**
     * <b>逐块</b>净化（pi {@code :283} 对每个文本块各净化一次后再推 output_text）。
     *
     * <p>判别串跨块边界造：块1 以孤高收尾、块2 以孤低开头。逐块 ⇒ {@code "AB"}；
     * 先拼接再净化 ⇒ 孤对**配对**成活 emoji ⇒ {@code "A🙈B"}。</p>
     */
    @Test
    void assistantTextIsSanitizedPerBlockNotAcrossBlocks() throws Exception {
        var params = buildParams(null, List.of(user("hi"),
            assistant(new ContentBlock.TextContent("A" + HIGH),
                new ContentBlock.TextContent(LOW + "B"))));

        assertThat(payloads(params)).as("逐块净化 ⇒ 跨块边界不许成对").contains("AB");
        assertThat(payloads(params)).as("拼接后再净化才会出现的形态").doesNotContain("A" + EMOJI + "B");
    }

    // ── 照缝（登记）与回归门 ──────────────────────────────────────────────

    /**
     * <b>照缝登记</b>（P5 家族）：tool 入参**不净化** —— pi {@code :322}
     * {@code arguments: JSON.stringify(toolCall.arguments)}；java {@code toArgumentsJson} 同形
     * （{@code openai-completions.ts:1366} 是同一族的另一处）。本条件钉住这个缝不被顺手补掉：
     * 补了才是行为偏离（{@code docs/43 D1}）。</p>
     *
     * <p>⚠️ 实测口径（{@code docs/43 §10}）：{@code toArgumentsJson} 走的是 Jackson 的
     * <b>char 型</b>生成器（{@code writeValueAsString}），孤高代理在其中**原样留在字符串里**
     * （不像 SDK 的 UTF-8 字节生成器会写成反斜杠-u 转义）⇒ 断言用 code-unit 探测。</p>
     */
    @Test
    void functionCallArgumentsKeepLoneSurrogate() throws Exception {
        var params = buildParams(null, List.of(user("hi"),
            toolUse(Map.of("text", DIRTY)),
            assistant(new ContentBlock.TextContent("visible"))));

        var args = params.input().orElseThrow().asResponse().stream()
            .filter(ResponseInputItem::isFunctionCall).findFirst().orElseThrow()
            .asFunctionCall().arguments();

        assertThat(args).as("照缝：tool 入参未净化（净化后会变成双空格形态）").doesNotContain(CLEAN);
        assertThat(hasLoneSurrogate(args)).as("未净化的孤对代理确实还在").isTrue();
    }

    /** 回归门：配对 emoji 在所有落点逐字保留。 */
    @Test
    void pairedEmojiSurvivesEverySite() throws Exception {
        var params = buildParams(PAIRED, List.of(user(PAIRED), userWithImage(PAIRED),
            assistant(new ContentBlock.TextContent(PAIRED)), toolUse(Map.of()), toolResult(PAIRED)));

        assertThat(payloads(params)).as("配对 emoji 逐字保留").containsOnly(PAIRED);
    }

    // ── 线格：出站请求体 ──────────────────────────────────────────────────

    /** 端到端：脏串经车道后，出站体里必须是净化后文本，且无孤对代理的任何形态。 */
    @Test
    void capturedRequestBodyHasNoLoneSurrogate() throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAIResponsesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            var request = new StreamRequest(TARGET, DIRTY,
                List.of(user(DIRTY), toolUse(Map.of()), assistant(new ContentBlock.TextContent(DIRTY)),
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