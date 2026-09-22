package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包B84 步4：Google 车道的<b>工具结果路径</b>（pi {@code google-shared.ts:284-339}）。
 *
 * <p>本类此前不存在，而缺陷是实测过的：java 把工具结果发成
 * {@code {"role":"model","parts":[{"text":"file-a.txt"}]}}，pi 发
 * {@code {"role":"user","parts":[{"functionResponse":{"name":"ls","response":{"output":"file-a.txt"}}}]}}
 * ⇒ <b>Gemini 的多轮工具调用今天是坏的</b>（模型看不到结果对应哪次调用）。</p>
 *
 * <p>骨架<b>镜像 pi 自己的</b> {@code packages/ai/test/google-shared-image-tool-result-routing.test.ts}
 * —— 同一份输入（1 user ＋ 1 assistant 带 3 个 toolCall ＋ 3 条 toolResult：text／image／text）、
 * 两套期望（gemini-2.5 ⇒ 5 条 contents；gemini-3 ⇒ 3 条且中间那条内嵌图片）⇒
 * <b>跨实现 oracle</b>，比自造夹具强一档。</p>
 *
 * <p>观测面＝<b>真出站请求体</b>（{@link RecordingHttpServer} ＋ SDK 的 {@code baseUrl} 覆盖）：
 * 序列化在 {@code com.google.genai} 内部，没有可反射的出参构造器。桩回 400 让车道尽快收场，
 * 体已录到。</p>
 */
class GoogleToolResultRoutingTest {

    private static final ModelId<?> GEMINI_25 = ModelId.of("google", "gemini-2.5-flash");
    private static final ModelId<?> GEMINI_3 = ModelId.of("google", "gemini-3-pro-preview");

    /** pi 夹具的 {@code input: ["text","image"]}。 */
    private static ModelInfo vision(ModelId<?> id) {
        return new ModelInfo(id, id.modelName(), Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT),
            128000, 8192, false, PricingInfo.UNKNOWN);
    }

    /** 真非视觉：capabilities **非空**且不含 IMAGE_INPUT（空集会被判成「未知 ⇒ 支持」）。 */
    private static ModelInfo textOnly(ModelId<?> id) {
        return new ModelInfo(id, id.modelName(), Set.of(ModelCapability.TEXT),
            128000, 8192, false, PricingInfo.UNKNOWN);
    }

    private static final String IMAGE_B64 = "YWxwaGE="; // "alpha"
    private static final String IMAGE_MIME = "image/png";

    // ── 镜像夹具（pi 的那一份输入）─────────────────────────────────────

    /** pi 的 5 条消息：user ＋ assistant(3 toolCall) ＋ toolResult ×3。 */
    private static List<Message> mirroredConversation() {
        return List.of(
            new Message.UserMessage(List.of(new ContentBlock.TextContent("read the files"))),
            toolCalls(),
            toolResult("call_a", text("alpha text")),
            toolResult("call_img", List.of(new ContentBlock.ImageContent(IMAGE_MIME, IMAGE_B64))),
            toolResult("call_b", text("beta text")));
    }

    private static Message toolCalls() {
        return new Message.AssistantMessage(List.of(
            new ContentBlock.ToolUseContent("call_a", "read", Map.of("path", "a.txt")),
            new ContentBlock.ToolUseContent("call_img", "read", Map.of("path", "image.png")),
            new ContentBlock.ToolUseContent("call_b", "read", Map.of("path", "b.txt"))));
    }

    private static List<ContentBlock> text(String value) {
        return List.of(new ContentBlock.TextContent(value));
    }

    private static Message toolResult(String callId, List<ContentBlock> content) {
        return new Message.ToolResultMessage(callId, "read", content, false);
    }

    // ── A3／A4：合并、独立图片回合、内嵌 ────────────────────────────────

    /**
     * gemini-2.5：<b>5</b> 条 contents，且形状与 pi 逐格一致。
     *
     * <p>{@code [2]} 是合并后的 2 个 functionResponse（a 与 img）—— 图片不内嵌（2.5 不支持
     * 多模态函数响应），故 {@code [3]} 是**另起**的 {@code "Tool result image:"} 回合
     * ⇒ 它打断了合并 ⇒ {@code [4]} 是 b 的**新** functionResponse 回合。</p>
     */
    @Test
    void gemini2KeepsASeparateSyntheticImageTurn() throws Exception {
        var contents = contentsOf(GEMINI_25, vision(GEMINI_25), mirroredConversation());

        assertThat(contents).as("pi 夹具钉的是 5 条").hasSize(5);
        assertThat(parts(contents, 2)).as("合并后的 user 回合全是 functionResponse")
            .allSatisfy(p -> assertThat(p.has("functionResponse")).isTrue());
        assertThat(parts(contents, 2)).hasSize(2);
        assertThat(parts(contents, 3).get(0).path("text").asText()).isEqualTo("Tool result image:");
        assertThat(parts(contents, 3).get(1).path("inlineData").path("mimeType").asText())
            .as("图片回合里是图").isEqualTo(IMAGE_MIME);
        assertThat(parts(contents, 4).get(0).path("functionResponse").path("name").asText())
            .as("被打断后 b 新起一回合").isEqualTo("read");
    }

    /**
     * gemini-3：<b>3</b> 条 contents，图片<b>内嵌</b>进 {@code functionResponse.parts}。
     *
     * <p>三条 toolResult 全合并进同一个 user 回合（图片不再另起回合）⇒ {@code [2].parts}
     * 有 3 个，中间那个的 {@code functionResponse.parts} 有 1 张图。</p>
     */
    @Test
    void gemini3NestsImagesInsideFunctionResponse() throws Exception {
        var contents = contentsOf(GEMINI_3, vision(GEMINI_3), mirroredConversation());

        assertThat(contents).as("pi 夹具钉的是 3 条").hasSize(3);
        var turn = parts(contents, 2);
        assertThat(turn).hasSize(3);
        var nested = turn.get(1).path("functionResponse").path("parts");
        assertThat(nested.isArray()).as("内嵌 parts 在场").isTrue();
        assertThat(nested).hasSize(1);
        assertThat(nested.get(0).path("inlineData").path("mimeType").asText()).isEqualTo(IMAGE_MIME);
    }

    // ── A1／A2：role、name、response 键 ────────────────────────────────

    /** 工具结果落成 **user** 回合的 {@code functionResponse}（不是 model 轮的文本）。 */
    @Test
    void toolResultBecomesAUserFunctionResponse() throws Exception {
        var contents = contentsOf(GEMINI_25, vision(GEMINI_25),
            List.of(toolResult("call_a", text("file-a.txt"))));

        assertThat(contents).hasSize(1);
        assertThat(contents.get(0).path("role").asText()).isEqualTo("user");
        var fn = parts(contents, 0).get(0).path("functionResponse");
        assertThat(fn.path("name").asText()).as("name 是**工具名**").isEqualTo("read");
        assertThat(fn.path("response").path("output").asText()).isEqualTo("file-a.txt");
    }

    /** {@code isError} ⇒ 键换成 {@code error}（pi {@code :314}）。 */
    @Test
    void failedToolResultUsesTheErrorKey() throws Exception {
        var contents = contentsOf(GEMINI_25, vision(GEMINI_25),
            List.of(new Message.ToolResultMessage("call_a", "read", text("boom"), true)));

        var response = parts(contents, 0).get(0).path("functionResponse").path("response");
        assertThat(response.has("error")).as("错误走 error 键").isTrue();
        assertThat(response.has("output")).as("不是 output 键").isFalse();
        assertThat(response.path("error").asText()).isEqualTo("boom");
    }

    // ── A6：responseValue 三选一（pi :301）─────────────────────────────

    /** 无文本、无图 ⇒ 空串（**不是**缺席 —— 键在场、值为 ""）。 */
    @Test
    void emptyToolResultYieldsAnEmptyResponseValue() throws Exception {
        var contents = contentsOf(GEMINI_25, vision(GEMINI_25),
            List.of(toolResult("call_a", List.of())));

        var response = parts(contents, 0).get(0).path("functionResponse").path("response");
        assertThat(response.path("output").asText()).isEmpty();
    }

    /** 无文本、**有图**且图片不内嵌（gemini-2.5）⇒ {@code "(see attached image)"}。 */
    @Test
    void imageOnlyToolResultYieldsTheAttachedImagePlaceholder() throws Exception {
        var contents = contentsOf(GEMINI_25, vision(GEMINI_25),
            List.of(toolResult("call_a", List.of(new ContentBlock.ImageContent(IMAGE_MIME, IMAGE_B64)))));

        assertThat(parts(contents, 0).get(0).path("functionResponse").path("response").path("output").asText())
            .isEqualTo("(see attached image)");
    }

    /** 多个文本块 ⇒ {@code join("\n")}（pi {@code :287}）。 */
    @Test
    void multipleTextBlocksAreJoinedWithNewlines() throws Exception {
        var contents = contentsOf(GEMINI_25, vision(GEMINI_25),
            List.of(toolResult("call_a", List.of(
                new ContentBlock.TextContent("first"),
                new ContentBlock.TextContent("second")))));

        assertThat(parts(contents, 0).get(0).path("functionResponse").path("response").path("output").asText())
            .isEqualTo("first\nsecond");
    }

    // ── A5：id 门（两侧）───────────────────────────────────────────────

    /** gemini-2.5 ⇒ {@code functionResponse} **不带** {@code id}（{@code requiresToolCallId} 假）。 */
    @Test
    void gemini2OmitsFunctionResponseId() throws Exception {
        var contents = contentsOf(GEMINI_25, vision(GEMINI_25),
            List.of(toolResult("call_a", text("alpha"))));

        var fn = parts(contents, 0).get(0).path("functionResponse");
        // ⚠️ 先钉「确实是个 functionResponse」——否则下面那条缺席断言在**修复前**
        // 也恒绿（老实现发的是 {"text":…}，压根没有 functionResponse 可谈 id）。
        assertThat(fn.path("name").asText()).isEqualTo("read");
        assertThat(fn.has("id")).as("2.5 不发 id").isFalse();
    }

    /** gemini-3 ⇒ 带 {@code id}，取值是 {@code toolUseId}（pi {@code :316}）。 */
    @Test
    void gemini3IncludesFunctionResponseId() throws Exception {
        var contents = contentsOf(GEMINI_3, vision(GEMINI_3),
            List.of(toolResult("call_a", text("alpha"))));

        assertThat(parts(contents, 0).get(0).path("functionResponse").path("id").asText())
            .isEqualTo("call_a");
    }

    // ── A7：与共享闸的叠加（先闸后车道，docs/44 §10-4）──────────────────

    /**
     * 非视觉模型：共享闸先把图片换成占位文本 ⇒ 车道**收到的已经是文本块** ⇒
     * 既没有图、也没有独立图片回合，{@code responseValue} 就是那句占位文案。
     *
     * <p>钉住两层的**顺序**（闸先改内容、车道再选分支）—— 反过来的话车道会先看到图、
     * 按 2.5 的规则另起一条 user 回合，闸再把它换成文本 ⇒ 多出一条回合。</p>
     */
    @Test
    void nonVisionModelSeesTheGatePlaceholderInsteadOfAnImage() throws Exception {
        var contents = contentsOf(GEMINI_25, textOnly(GEMINI_25),
            List.of(toolResult("call_a", List.of(new ContentBlock.ImageContent(IMAGE_MIME, IMAGE_B64)))));

        assertThat(contents).as("闸换掉了图 ⇒ 无独立图片回合").hasSize(1);
        assertThat(parts(contents, 0).get(0).path("functionResponse").path("response").path("output").asText())
            .isEqualTo("(tool image omitted: model does not support images)");
    }

    // ── 脚手架 ──────────────────────────────────────────────────────────

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 真出站请求体的 {@code contents} 数组。 */
    private static List<JsonNode> contentsOf(ModelId<?> id, ModelInfo model,
                                             List<Message> messages) throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new GoogleGenerativeAiApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()));
            var request = new StreamRequest(model, null, messages, List.of(), -1, -1, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                var events = new ArrayList<StreamEvent>();
                while (iter.hasNext() && events.size() < 100) {
                    events.add(iter.next());
                }
            } catch (Exception ignored) {
                // 桩回 400，请求体已录到
            }
            var root = MAPPER.readTree(server.body());
            assertThat(root.has("contents")).as("请求体里有 contents（线格：" + server.body() + "）").isTrue();
            var contents = new ArrayList<JsonNode>();
            root.path("contents").forEach(contents::add);
            return contents;
        }
    }

    private static List<JsonNode> parts(List<JsonNode> contents, int index) {
        var parts = new ArrayList<JsonNode>();
        contents.get(index).path("parts").forEach(parts::add);
        return parts;
    }
}
