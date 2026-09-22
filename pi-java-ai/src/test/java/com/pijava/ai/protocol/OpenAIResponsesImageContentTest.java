package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionCallOutputItem;

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
 * 包 H2（{@code docs/44}）步 5：OpenAI-responses 车道的**工具结果**图片落点 ——
 * pi {@code openai-responses-shared.ts:78-110} 的 {@code convertToolResultOutput}。
 *
 * <p>本车道的 user 图片路径**早就通了**（{@code toUserItem}，包② 落的），本步只补工具结果那半边：
 * 输出从**字符串**变成**块数组** {@code [{input_text?},{input_image(detail:"auto")}…]}。</p>
 *
 * <p>⚠️ 两处细节：① {@code hasText} 为假时**不**推 {@code input_text} 项（pi {@code :97-99}，
 * 与 Anthropic 的占位补块**不同**）；② 只有图片且不支持时落 {@code "(see attached image)"}
 * —— 共享闸先剥 ⇒ 该分支不可达，照抄保留。</p>
 *
 * <p>观测面＝反射 {@code buildParams}（先例 {@code OpenAIResponsesSurrogateSanitizeTest}）。</p>
 *
 * <p><b>实测：`Tests run: 10, Failures: 5`</b>（本步实现前）—— 5 条行为红灯、5 条回归门。
 * ⚠️ 其中 {@code nonVisionToolResultStaysAString} 的红来自 **join 分隔符**（旧实现无分隔符拼接），
 * 不是能力门 —— 能力门与 {@code "(see attached image)"} 分支在两侧都不可达（{@code docs/44 §9}）。</p>
 */
class OpenAIResponsesImageContentTest {

    private static final String PNG_B64 = "aGVsbG8=";
    private static final String DATA_URL = "data:image/png;base64," + PNG_B64;
    private static final ModelId<?> TARGET = ModelId.of("openai", "gpt-5-mini");

    private static final ModelInfo VISION = new ModelInfo(TARGET, "GPT-5 mini",
            Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT),
            400_000, 128_000, false, PricingInfo.UNKNOWN);
    private static final ModelInfo NON_VISION = new ModelInfo(TARGET, "GPT-5 mini",
            Set.of(ModelCapability.TEXT, ModelCapability.TOOL_USE),
            400_000, 128_000, false, PricingInfo.UNKNOWN);

    private static ContentBlock image() {
        return new ContentBlock.ImageContent("image/png", PNG_B64);
    }

    private static ResponseCreateParams build(ModelInfo model, List<Message> messages)
            throws Exception {
        var request = new StreamRequest(model, null, messages, List.of(), 100, 0.5, Map.of());
        var method = ResponsesMessageConverter.class.getDeclaredMethod(
            "buildParams", StreamRequest.class, ResponsesOptions.class, String.class, String.class);
        method.setAccessible(true);
        return (ResponseCreateParams) method.invoke(null, request,
            ResponsesOptions.from(ApiOptions.defaults()), "gpt-5-mini", "openai-responses");
    }

    /** 工具结果那一项的 output 压成 {@code text:…} ／ {@code image:url:detail} ／ {@code string:…}。 */
    private static List<String> toolOutput(ModelInfo model, List<Message> messages) throws Exception {
        var out = new ArrayList<String>();
        for (var item : build(model, messages).input().orElseThrow().asResponse()) {
            if (!item.isFunctionCallOutput()) {
                continue;
            }
            var output = item.asFunctionCallOutput().output();
            if (output.isString()) {
                out.add("string:" + output.asString());
                continue;
            }
            for (ResponseFunctionCallOutputItem part : output.asResponseFunctionCallOutputItemList()) {
                if (part.isInputText()) {
                    out.add("text:" + part.asInputText().text());
                } else if (part.isInputImage()) {
                    var img = part.asInputImage();
                    out.add("image:" + img.imageUrl().orElseThrow() + ":"
                        + img.detail().orElseThrow().asString());
                } else {
                    out.add("other");
                }
            }
        }
        return out;
    }

    private static List<Message> tool(ContentBlock... blocks) {
        return List.of(new Message.ToolResultMessage("call_1", "read", List.of(blocks), false));
    }

    // ------------------------------------------------------------------ RED

    /** <b>红</b> pi {@code :96-105}：有图 ⇒ 块数组（{@code input_text} ＋ {@code input_image}）。 */
    @Test
    void toolResultImagesBecomeInputImageItems() throws Exception {
        assertThat(toolOutput(VISION, tool(new ContentBlock.TextContent("ok"), image())))
                .containsExactly("text:ok", "image:" + DATA_URL + ":auto");
    }

    /**
     * <b>红</b> pi {@code :97-99}：{@code hasText} 为假时**不推** {@code input_text} ——
     * 与 Anthropic 的 toolResult（补 {@code "(see attached image)"} 文本块）**刻意不同**。
     */
    @Test
    void imageOnlyToolResultHasNoTextItem() throws Exception {
        assertThat(toolOutput(VISION, tool(image())))
                .containsExactly("image:" + DATA_URL + ":auto");
    }

    /** <b>红</b> pi {@code :86-88}：文本块 {@code join("\n")}（旧实现是无分隔符 concat）。 */
    @Test
    void toolResultTextBlocksAreJoinedWithNewline() throws Exception {
        assertThat(toolOutput(VISION, tool(
                new ContentBlock.TextContent("a"), new ContentBlock.TextContent("b"))))
                .containsExactly("string:a\nb");
    }

    /**
     * <b>红</b> {@code docs/44 D4 选项 A}：{@link ContentBlock.UrlImageContent}（java 扩展）
     * 同样进块数组，URL 原样下发。
     */
    @Test
    void toolResultUrlImagesBecomeInputImageItems() throws Exception {
        assertThat(toolOutput(VISION, tool(new ContentBlock.UrlImageContent("https://example.test/a.png"))))
                .containsExactly("image:https://example.test/a.png:auto");
    }

    // ----------------------------------------------------------- 回归门（今天就绿）

    /** <b>回归门</b> 无图片的文本工具结果仍是**字符串**形态（pi {@code :91-93} 的第一分支）。 */
    @Test
    void textOnlyToolResultStaysAString() throws Exception {
        assertThat(toolOutput(VISION, tool(new ContentBlock.TextContent("ok"))))
                .containsExactly("string:ok");
    }

    /** <b>回归门</b> 空结果 ⇒ {@code "(no tool output)"}（java 早已对齐）。 */
    @Test
    void emptyToolResultGetsNoToolOutput() throws Exception {
        assertThat(toolOutput(VISION, tool())).containsExactly("string:(no tool output)");
    }

    /**
     * <b>回归门</b> 非视觉模型：共享闸把图片换成文本块 ⇒ 走字符串分支，
     * 正文是那句占位文案（车道侧的能力门与 {@code "(see attached image)"} 分支因此不可达，
     * {@code docs/44 §9}）。
     */
    @Test
    void nonVisionToolResultStaysAString() throws Exception {
        assertThat(toolOutput(NON_VISION, tool(new ContentBlock.TextContent("ok"), image())))
                .containsExactly("string:ok\n(tool image omitted: model does not support images)");
    }

    /** <b>回归门</b> 文本仍按 pi 的口径净化（拼完再净化）。 */
    @Test
    void toolResultTextIsSanitized() throws Exception {
        assertThat(toolOutput(VISION, tool(new ContentBlock.TextContent("Text " + (char) 0xD83D + " here"))))
                .containsExactly("string:Text  here");
    }

    /** <b>回归门</b> user 图片路径不受本步影响（包② 已落）。 */
    @Test
    void userImagesStillUseTheImageBranch() throws Exception {
        var params = build(VISION, List.of(new Message.UserMessage(List.of(image()))));
        var user = params.input().orElseThrow().asResponse().get(0).asEasyInputMessage();
        var parts = user.content().asResponseInputMessageContentList();

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).asInputImage().imageUrl()).contains(DATA_URL);
    }

    /** <b>回归门</b> 无图片的 user 消息仍是串形态。 */
    @Test
    void textOnlyUserStaysAString() throws Exception {
        var params = build(VISION, List.of(new Message.UserMessage(
            List.of(new ContentBlock.TextContent("hi")))));
        var user = params.input().orElseThrow().asResponse().get(0).asEasyInputMessage();

        assertThat(user.content().asTextInput()).isEqualTo("hi");
    }
}
