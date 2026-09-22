package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.openai.models.chat.completions.ChatCompletionCreateParams;

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
 * 包 H2（{@code docs/44}）步 3：OpenAI-completions 车道的图片落点 ——
 * pi {@code openai-completions.ts:1255-1277}（user）与 {@code :1396-1459}（toolResult）。
 *
 * <p>本车道的两处形状特点（与 Anthropic 车道的三处不对称**不同**）：</p>
 * <ul>
 *   <li>user 有图分支**不过滤**空文本块（pi {@code :1259-1272} 只判 {@code content.length === 0}）；</li>
 *   <li>toolResult 的图片**不留在 tool 消息里**，而是补一条**合成的 user 消息**
 *       {@code "Attached image(s) from tool result:"} ＋ 图片块（pi {@code :1440-1456}），
 *       且**连续的 toolResult 合并收集**（{@code :1398-1438} 的内层循环）。</li>
 * </ul>
 *
 * <p>观测面＝{@code buildParams}（同包 package-private static，无需反射）。</p>
 *
 * <p><b>实测：`Tests run: 13, Failures: 9, Errors: 1`</b>（本步实现前）—— 10 条行为红灯、3 条回归门。</p>
 */
class OpenAIImageContentTest {

    private static final String PNG_B64 = "aGVsbG8=";
    private static final String DATA_URL = "data:image/png;base64," + PNG_B64;
    private static final ModelId<?> TARGET = ModelId.of("openai", "gpt-5");

    private static final ModelInfo VISION = new ModelInfo(TARGET, "GPT-5",
            Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT),
            200_000, 16_384, false, PricingInfo.UNKNOWN);
    private static final ModelInfo NON_VISION = new ModelInfo(TARGET, "GPT-5",
            Set.of(ModelCapability.TEXT, ModelCapability.TOOL_USE),
            200_000, 16_384, false, PricingInfo.UNKNOWN);

    private static ContentBlock image() {
        return new ContentBlock.ImageContent("image/png", PNG_B64);
    }

    private static ChatCompletionCreateParams build(ModelInfo model, List<Message> messages) {
        var request = new StreamRequest(model, null, messages, List.of(), -1, -1, Map.of());
        return OpenAICompletionsApi.buildParams(request, "openai-completions", null);
    }

    /** 全部消息压成 {@code role:…}，user 的数组形态展开成 {@code text:…} ／ {@code image:…}。 */
    private static List<String> render(ChatCompletionCreateParams params) {
        var out = new ArrayList<String>();
        for (var m : params.messages()) {
            if (m.isUser()) {
                var content = m.asUser().content();
                if (content.isText()) {
                    out.add("user:" + content.asText());
                } else {
                    for (var part : content.asArrayOfContentParts()) {
                        if (part.isText()) {
                            out.add("user_text:" + part.asText().text());
                        } else if (part.isImageUrl()) {
                            out.add("user_image:" + part.asImageUrl().imageUrl().url());
                        }
                    }
                }
            } else if (m.isTool()) {
                var content = m.asTool().content();
                out.add("tool:" + (content.isText() ? content.asText() : "<parts>"));
            } else if (m.isAssistant()) {
                out.add("assistant");
            } else {
                out.add("other");
            }
        }
        return out;
    }

    private static List<Message> user(ContentBlock... blocks) {
        return List.of(new Message.UserMessage(List.of(blocks)));
    }

    private static Message.ToolResultMessage tool(String id, ContentBlock... blocks) {
        return new Message.ToolResultMessage(id, "read", List.of(blocks), false);
    }

    // ------------------------------------------------------------------ RED

    /** <b>红</b> pi {@code :1264-1272}：user 的图片落成 {@code image_url} 的 data URL。 */
    @Test
    void userImagesBecomeImageUrlParts() {
        assertThat(render(build(VISION, user(new ContentBlock.TextContent("look at this"), image()))))
                .containsExactly("user_text:look at this", "user_image:" + DATA_URL);
    }

    /**
     * <b>红</b> pi {@code :1267}：user 有图分支只判 {@code content.length === 0}，
     * **不过滤**空文本块 —— 与 Anthropic 的 user 分支（过滤）刻意不同。
     */
    @Test
    void userBlankTextPartsAreNotFiltered() {
        assertThat(render(build(VISION, user(new ContentBlock.TextContent("   "), image()))))
                .containsExactly("user_text:   ", "user_image:" + DATA_URL);
    }

    /**
     * <b>红</b> pi {@code :1440-1456}：toolResult 的图片**移出** tool 消息，补一条合成的
     * user 消息（文案逐字 {@code "Attached image(s) from tool result:"}）。
     */
    @Test
    void toolResultImagesBecomeASyntheticUserMessage() {
        assertThat(render(build(VISION, List.of(
                tool("toolu_1", new ContentBlock.TextContent("Read image file [image/png]"), image())))))
                .containsExactly("tool:Read image file [image/png]",
                    "user_text:Attached image(s) from tool result:",
                    "user_image:" + DATA_URL);
    }

    /**
     * <b>红</b> pi {@code :1398-1438}：**连续的** toolResult 先各自落一条 tool 消息，
     * 但图片**合并收集**进**同一条**合成 user 消息（不是一条工具结果配一条）。
     */
    @Test
    void consecutiveToolResultsShareOneSyntheticUserMessage() {
        assertThat(render(build(VISION, List.of(
                tool("toolu_1", image()), tool("toolu_2", image())))))
                .containsExactly("tool:(see attached image)",
                    "tool:(see attached image)",
                    "user_text:Attached image(s) from tool result:",
                    "user_image:" + DATA_URL,
                    "user_image:" + DATA_URL);
    }

    /**
     * <b>红</b> pi {@code :1412}：无文本但有图 ⇒ tool 消息落 {@code "(see attached image)"}
     * （图片本身另走合成 user 消息）。
     */
    @Test
    void toolResultWithoutTextGetsSeeAttachedImage() {
        assertThat(render(build(VISION, List.of(tool("toolu_1", image())))))
                .containsExactly("tool:(see attached image)",
                    "user_text:Attached image(s) from tool result:",
                    "user_image:" + DATA_URL);
    }

    /**
     * <b>红</b> pi {@code :1412} 的第三个分支：无文本也无图 ⇒ {@code "(no tool output)"}
     * （pi-java 旧实现发的是**空串**）。
     */
    @Test
    void emptyToolResultGetsNoToolOutput() {
        assertThat(render(build(VISION, List.of(tool("toolu_1")))))
                .containsExactly("tool:(no tool output)");
    }

    /**
     * <b>红</b> pi {@code :1405-1407}：tool 消息的文本是各文本块 {@code join("\n")} 之后
     * 再净化（pi-java 旧实现是**无分隔符拼接**）。
     */
    @Test
    void toolResultTextBlocksAreJoinedWithNewline() {
        assertThat(render(build(VISION, List.of(
                tool("toolu_1", new ContentBlock.TextContent("a"), new ContentBlock.TextContent("b"))))))
                .containsExactly("tool:a\nb");
    }

    /**
     * <b>红</b> pi {@code :1424}：图片收集**另有**一道能力门（与共享闸冗余，pi 两处都写）
     * —— 非视觉模型下 tool 消息照发、**没有**合成 user 消息。
     *
     * <p>⚠️ 注意 tool 正文：共享闸把图片换成了**文本块**，它随后**参与 join** ⇒
     * 正文是 {@code "ok\n(tool image omitted: …)"}（不是 {@code "ok"}）——
     * 这正是 pi 两层叠加后的形状（闸先改内容，车道再 join）。</p>
     */
    @Test
    void nonVisionModelDropsToolResultImages() {
        assertThat(render(build(NON_VISION, List.of(
                tool("toolu_1", new ContentBlock.TextContent("ok"), image())))))
                .containsExactly("tool:ok\n(tool image omitted: model does not support images)");
    }

    /**
     * <b>红</b> 能力门管的是**收集**，不是占位文案：非视觉模型 + 只有图片的工具结果 ⇒
     * 共享闸换成 {@code "(tool image omitted: …)"} 文本 ⇒ 落成 tool 文本（不是
     * {@code "(see attached image)"}）。
     */
    @Test
    void nonVisionModelGetsTheDowngradedToolText() {
        assertThat(render(build(NON_VISION, List.of(tool("toolu_1", image())))))
                .containsExactly("tool:(tool image omitted: model does not support images)");
    }

    /**
     * <b>红</b> {@code docs/44 D4 选项 A}：{@link ContentBlock.UrlImageContent}（java 扩展）
     * 在本车道按**线格本名**下发 —— {@code image_url} 本来就收 URL。
     */
    @Test
    void urlImagesBecomeImageUrlParts() {
        assertThat(render(build(VISION, user(
                new ContentBlock.UrlImageContent("https://example.test/a.png")))))
                .containsExactly("user_image:https://example.test/a.png");
    }

    /** <b>红</b> 同上，工具结果里的 URL 图片也进合成 user 消息。 */
    @Test
    void toolResultUrlImagesJoinTheSyntheticUserMessage() {
        assertThat(render(build(VISION, List.of(tool("toolu_1",
                new ContentBlock.UrlImageContent("https://example.test/a.png"))))))
                .containsExactly("tool:(see attached image)",
                    "user_text:Attached image(s) from tool result:",
                    "user_image:https://example.test/a.png");
    }

    // ----------------------------------------------------------- 回归门（今天就绿）

    /**
     * <b>回归门</b> 无图片的 user 消息仍是**串形态**（pi-java 的既有形状；pi 的串分支
     * 在 pi-java 结构上不可达，见 {@code docs/44 §6} 的登记）。
     */
    @Test
    void textOnlyUserStaysAString() {
        assertThat(render(build(VISION, user(new ContentBlock.TextContent("hi")))))
                .containsExactly("user:hi");
    }

    /** <b>回归门</b> 无图片的 toolResult 仍是单条 tool 消息，不产生合成 user 消息。 */
    @Test
    void textOnlyToolResultStaysASingleToolMessage() {
        assertThat(render(build(VISION, List.of(tool("toolu_1", new ContentBlock.TextContent("ok"))))))
                .containsExactly("tool:ok");
    }
}
