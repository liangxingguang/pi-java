package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 H2（{@code docs/44}）步 4：Mistral 车道的图片落点 ——
 * pi {@code mistral-conversations.ts:792-815}（user）与 {@code :851-897}（toolResult ＋ {@code buildToolResultText}）。
 *
 * <p>本车道的 toolResult 是**整块照抄**（{@code docs/44} 待裁决 ③ 采纳）：pi 那是一个函数
 * （{@code buildToolResultText}），拆一半等于自己发明第三种文案。于是本步顺带带来三处
 * **非图片**的行为变更，逐条有用例：</p>
 * <ul>
 *   <li>{@code name} 字段（pi {@code :870}）—— java 旧实现不发；</li>
 *   <li>{@code "[tool error] "} 前缀（pi {@code :879}）—— java 旧实现完全不看 {@code isError}；</li>
 *   <li>文本 **trim**（pi {@code :878}）与各文本块 {@code join("\n")}（{@code :853}，
 *       java 旧实现是无分隔符拼接）。</li>
 * </ul>
 * <p>另：content 从**字符串**变成**块数组**（图片要地方放）。</p>
 *
 * <p>观测面＝{@code buildRequestBody} 的出参 JSON 串（反射私有方法，先例
 * {@code MistralSurrogateSanitizeTest}），用 Jackson 解成树再断言。</p>
 *
 * <p><b>实测：`Tests run: 13, Failures: 10, Errors: 1`</b>（本步实现前）—— 11 条行为红灯、2 条回归门。</p>
 */
class MistralImageContentTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PNG_B64 = "aGVsbG8=";
    private static final String DATA_URL = "data:image/png;base64," + PNG_B64;
    private static final ModelId<?> TARGET = ModelId.of("mistral", "mistral-medium-latest");

    private static final ModelInfo VISION = new ModelInfo(TARGET, "Mistral Medium",
            Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT),
            128_000, 8_192, false, PricingInfo.UNKNOWN);
    private static final ModelInfo NON_VISION = new ModelInfo(TARGET, "Mistral Medium",
            Set.of(ModelCapability.TEXT, ModelCapability.TOOL_USE),
            128_000, 8_192, false, PricingInfo.UNKNOWN);

    private static ContentBlock image() {
        return new ContentBlock.ImageContent("image/png", PNG_B64);
    }

    private static String body(ModelInfo model, List<Message> messages) throws Exception {
        var api = new MistralConversationsApi(new ApiOptions(
            "http://localhost:1", "test-key", Duration.ofSeconds(5), 0, Map.of()));
        var method = MistralConversationsApi.class.getDeclaredMethod(
            "buildRequestBody", StreamRequest.class);
        method.setAccessible(true);
        var request = new StreamRequest(model, null, messages, List.of(), -1, -1, Map.of());
        return (String) method.invoke(api, request);
    }

    /** 每条消息压成 {@code role:…}；content 是数组时展开成 {@code text:…} ／ {@code image:…}。 */
    private static List<String> render(ModelInfo model, List<Message> messages) throws Exception {
        var out = new ArrayList<String>();
        for (var node : JSON.readTree(body(model, messages)).get("messages")) {
            var role = node.get("role").asText();
            var content = node.get("content");
            if (content.isTextual()) {
                out.add(role + ":" + content.asText());
                continue;
            }
            for (var chunk : content) {
                if ("text".equals(chunk.get("type").asText())) {
                    out.add(role + "_text:" + chunk.get("text").asText());
                } else if ("image_url".equals(chunk.get("type").asText())) {
                    out.add(role + "_image:" + chunk.get("image_url").asText());
                } else {
                    out.add(role + "_other:" + chunk.get("type").asText());
                }
            }
        }
        return out;
    }

    private static List<Message> user(ContentBlock... blocks) {
        return List.of(new Message.UserMessage(List.of(blocks)));
    }

    private static Message.ToolResultMessage tool(boolean isError, ContentBlock... blocks) {
        return new Message.ToolResultMessage("call_1", "ls", List.of(blocks), isError);
    }

    // ------------------------------------------------------------------ RED

    /** <b>红</b> pi {@code :803}：user 的图片落成 {@code {type:"image_url", imageUrl:"data:…"}}。 */
    @Test
    void userImagesBecomeImageUrlChunks() throws Exception {
        assertThat(render(VISION, user(new ContentBlock.TextContent("look at this"), image())))
                .containsExactly("user_text:look at this", "user_image:" + DATA_URL);
    }

    /** <b>红</b> {@code docs/44 D4 选项 A}：URL 图片按线格本名下发。 */
    @Test
    void urlImagesBecomeImageUrlChunks() throws Exception {
        assertThat(render(VISION, user(new ContentBlock.UrlImageContent("https://example.test/a.png"))))
                .containsExactly("user_image:https://example.test/a.png");
    }

    /**
     * <b>红</b> pi {@code :856-874}：工具结果的 content 从**字符串**变成**块数组**
     * （文本块在前，图片块按序在后），且带上 {@code name}（pi {@code :870}）。
     */
    @Test
    void toolResultImagesBecomeImageUrlChunks() throws Exception {
        assertThat(render(VISION, List.of(tool(false,
                new ContentBlock.TextContent("ok"), image()))))
                .containsExactly("tool_text:ok", "tool_image:" + DATA_URL);
    }

    /** <b>红</b> pi {@code :870}：{@code name} 字段（java 旧实现不发）。 */
    @Test
    void toolResultCarriesTheToolName() throws Exception {
        var messages = JSON.readTree(body(VISION, List.of(tool(false,
            new ContentBlock.TextContent("ok"))))).get("messages");

        assertThat(messages.get(0).get("name").asText()).isEqualTo("ls");
    }

    /** <b>红</b> pi {@code :879}：{@code isError} ⇒ 文本带 {@code "[tool error] "} 前缀（旧实现完全不看它）。 */
    @Test
    void toolResultErrorGetsTheErrorPrefix() throws Exception {
        assertThat(render(VISION, List.of(tool(true, new ContentBlock.TextContent("boom")))))
                .containsExactly("tool_text:[tool error] boom");
    }

    /** <b>红</b> pi {@code :878}：文本 **trim**（旧实现原样发）。 */
    @Test
    void toolResultTextIsTrimmed() throws Exception {
        assertThat(render(VISION, List.of(tool(false, new ContentBlock.TextContent("  ok  ")))))
                .containsExactly("tool_text:ok");
    }

    /**
     * <b>红</b> pi {@code :886-890}：无文本但有图 ⇒ {@code "(see attached image)"}
     * （错误时带前缀），图片另起一块。
     */
    @Test
    void toolResultWithoutTextGetsSeeAttachedImage() throws Exception {
        assertThat(render(VISION, List.of(tool(false, image()))))
                .containsExactly("tool_text:(see attached image)", "tool_image:" + DATA_URL);
        assertThat(render(VISION, List.of(tool(true, image()))))
                .containsExactly("tool_text:[tool error] (see attached image)",
                    "tool_image:" + DATA_URL);
    }

    /** <b>红</b> pi {@code :897}：无文本也无图 ⇒ {@code "(no tool output)"}（旧实现发空串）。 */
    @Test
    void emptyToolResultGetsNoToolOutput() throws Exception {
        assertThat(render(VISION, List.of(tool(false)))).containsExactly("tool_text:(no tool output)");
        assertThat(render(VISION, List.of(tool(true))))
                .containsExactly("tool_text:[tool error] (no tool output)");
    }

    /** <b>红</b> pi {@code :853}：各文本块先各自净化、再 {@code join("\n")}（旧实现无分隔符拼接）。 */
    @Test
    void toolResultTextBlocksAreJoinedWithNewline() throws Exception {
        assertThat(render(VISION, List.of(tool(false,
                new ContentBlock.TextContent("a"), new ContentBlock.TextContent("b")))))
                .containsExactly("tool_text:a\nb");
    }

    /**
     * <b>红</b> 非视觉模型：共享闸把图片换成占位文本块 ⇒ tool 正文是那句占位文案，
     * **没有**图片块。⚠️ 车道侧那个 {@code supportsImages} 门与
     * {@code "[tool image omitted: …]"} 后缀因此在两侧都不可达（同 completions 车道，
     * {@code docs/44 §9}）。
     */
    @Test
    void nonVisionToolResultGetsTheDowngradedText() throws Exception {
        assertThat(render(NON_VISION, List.of(tool(false,
                new ContentBlock.TextContent("ok"), image()))))
                .containsExactly("tool_text:ok\n(tool image omitted: model does not support images)");
    }

    /**
     * <b>红</b> 非视觉模型的 user 图片只剩闸的占位文本，且**退回串形态** ——
     * 闸把图片换成了**文本块** ⇒ 车道侧看不到图片了（{@code hadImages} 为假）⇒ 走串分支。
     * 这正是 pi 两层叠加后的形状（闸先改内容，车道再按新内容选分支）。
     */
    @Test
    void nonVisionUserImageGetsTheDowngradedText() throws Exception {
        assertThat(render(NON_VISION, user(image())))
                .containsExactly("user:(image omitted: model does not support images)");
    }

    // ----------------------------------------------------------- 回归门（今天就绿）

    /** <b>回归门</b> 无图片的 user 消息仍是**串形态**（pi-java 的既有形状，见 {@code docs/44 §6}）。 */
    @Test
    void textOnlyUserStaysAString() throws Exception {
        assertThat(render(VISION, user(new ContentBlock.TextContent("hi"))))
                .containsExactly("user:hi");
    }

    /** <b>回归门</b> 助手消息仍是**串形态**（pi 的助手分支是块数组 —— 既有偏差，本包不动）。 */
    @Test
    void assistantMessageStaysAString() throws Exception {
        var assistant = new Message.AssistantMessage(List.of(new ContentBlock.TextContent("hi")),
            "stop", null, "mistral-conversations", "mistral", TARGET.modelName(),
            null, null, null, null);

        assertThat(render(VISION, List.of(assistant))).containsExactly("assistant:hi");
    }
}
