package com.pijava.ai.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 H2（{@code docs/44}）步 1：{@link TransformMessages} 的**图片降级闸**
 * —— pi {@code transform-messages.ts:12-57}。
 *
 * <p>pi 的闸在**共享预通道**里，且**早于**逐条变换与车道映射跑（{@code :73-74}）。
 * 本文件直接断言 {@code apply()} 的返回值，使「规则住在哪一层」成为可测事实
 * （与 {@link TransformMessagesTest} 同一套理由）。</p>
 *
 * <p><b>为什么这一条必须与「车道开始发图」同包</b>：闸缺席时，非视觉模型会收到真图片 ⇒
 * 从「静默丢图」换成「provider 400」，是拿一个硬故障换另一个。</p>
 *
 * <p>⚠️ 标 <b>回归门</b> 的是**今天**（闸缺席时）就绿的：它们钉的是「不该降级的时候别降级」，
 * 不计入红灯数。</p>
 *
 * <p><b>实测：`Tests run: 11, Failures: 6`</b>（闸体缺席态）—— 6 条行为红灯（见各 javadoc 的「红」标记），
 * 5 条回归门。三处去重/边界（连续图片、占位符文本后跟图片、URL 图片）都在红灯集里。</p>
 */
class TransformMessagesImageDowngradeTest {

    private static final ModelId<?> TARGET = ModelId.of("anthropic", "claude-sonnet-5");
    private static final String API = "anthropic-messages";

    /** pi 逐字（{@code transform-messages.ts:12}）。 */
    private static final String USER_PLACEHOLDER =
            "(image omitted: model does not support images)";

    /** pi 逐字（{@code transform-messages.ts:13}）—— 比 user 版多一个 {@code tool }。 */
    private static final String TOOL_PLACEHOLDER =
            "(tool image omitted: model does not support images)";

    private static final ModelInfo VISION = model(Set.of(
            ModelCapability.TEXT, ModelCapability.IMAGE_INPUT));
    private static final ModelInfo NON_VISION = model(Set.of(
            ModelCapability.TEXT, ModelCapability.TOOL_USE));

    /** 目录未命中（capabilities 为空）—— 未知态，见 {@code docs/44 D2}。 */
    private static final ModelInfo UNKNOWN = ModelInfo.minimal(TARGET);

    private static ModelInfo model(Set<ModelCapability> caps) {
        return new ModelInfo(TARGET, "Claude Sonnet 5", caps,
                200_000, 8_192, false, PricingInfo.UNKNOWN);
    }

    private static ContentBlock image() {
        return new ContentBlock.ImageContent("image/png", "aGVsbG8=");
    }

    private static List<String> applied(ModelInfo model, Message... messages) {
        var out = new ArrayList<String>();
        for (var msg : TransformMessages.apply(List.of(messages), TARGET, API, model)) {
            for (var block : msg.content()) {
                if (block instanceof ContentBlock.TextContent tc) {
                    out.add("text:" + tc.text());
                } else if (block instanceof ContentBlock.ImageContent img) {
                    out.add("image:" + img.mediaType());
                } else if (block instanceof ContentBlock.UrlImageContent url) {
                    out.add("url:" + url.url());
                } else {
                    out.add("other");
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ RED

    /**
     * <b>红</b> pi {@code :19-30} 的核心：非视觉模型的 user 图片换成占位文本，
     * 且**连续的图片只出一个占位符**（{@code previousWasPlaceholder} 去重）。
     */
    @Test
    void userImagesCollapseIntoOnePlaceholder() {
        var user = new Message.UserMessage(List.of(
                new ContentBlock.TextContent("look at these"),
                image(), image(),
                new ContentBlock.TextContent("thanks")));

        assertThat(applied(NON_VISION, user))
                .containsExactly("text:look at these", "text:" + USER_PLACEHOLDER, "text:thanks");
    }

    /** <b>红</b> 只有图片、没有文本 ⇒ 仍然出一个占位符（pi {@code :21-24} 的分支）。 */
    @Test
    void imageOnlyUserMessageBecomesAPlaceholder() {
        assertThat(applied(NON_VISION, new Message.UserMessage(List.of(image()))))
                .containsExactly("text:" + USER_PLACEHOLDER);
    }

    /**
     * <b>红</b> toolResult 走**另一条**文案（{@code :13}，多一个 {@code tool }）——
     * 这条同时钉住「两个常量别写成同一个」。
     */
    @Test
    void toolResultImagesUseTheToolPlaceholder() {
        var tool = new Message.ToolResultMessage("toolu_1", "read",
                List.of(new ContentBlock.TextContent("Read image file [image/png]"), image()),
                false);

        assertThat(applied(NON_VISION, tool))
                .containsExactly("text:Read image file [image/png]", "text:" + TOOL_PLACEHOLDER);
    }

    /**
     * <b>红</b> pi {@code :29} 的第二处去重：一条**自带**占位文案的文本块，其后紧跟的图片
     * **不再补**占位符 —— 否则会出两个一模一样的占位符。这是最容易漏掉的一处。
     */
    @Test
    void textEqualToPlaceholderSuppressesTheNextPlaceholder() {
        var user = new Message.UserMessage(List.of(
                new ContentBlock.TextContent(USER_PLACEHOLDER), image()));

        assertThat(applied(NON_VISION, user)).containsExactly("text:" + USER_PLACEHOLDER);
    }

    /**
     * <b>红</b> {@code docs/44 D4} 的 java 扩展：{@link ContentBlock.UrlImageContent} 在 pi 里
     * 没有对应类型，但对非视觉模型同样不可用 ⇒ 与 {@code ImageContent} 同等降级。
     */
    @Test
    void urlImagesAreDowngradedToo() {
        var user = new Message.UserMessage(List.of(
                new ContentBlock.UrlImageContent("https://example.test/a.png")));

        assertThat(applied(NON_VISION, user)).containsExactly("text:" + USER_PLACEHOLDER);
    }

    /**
     * <b>红</b> 图片在 user 与 toolResult 里都降级，**文本块逐字保留**（含空文本块 ——
     * 降级这一步不做任何 trim/过滤，pi 也不做）。
     */
    @Test
    void nonImageBlocksSurviveVerbatim() {
        var user = new Message.UserMessage(List.of(
                new ContentBlock.TextContent(""), image(),
                new ContentBlock.TextContent("tail")));

        assertThat(applied(NON_VISION, user))
                .containsExactly("text:", "text:" + USER_PLACEHOLDER, "text:tail");
    }

    // ----------------------------------------------------------- 回归门（今天就绿）

    /** <b>回归门</b> 视觉模型：整条列表原样（pi {@code :36-38} 直接 return）。 */
    @Test
    void visionModelKeepsImages() {
        var user = new Message.UserMessage(List.of(
                new ContentBlock.TextContent("look"), image()));

        assertThat(applied(VISION, user)).containsExactly("text:look", "image:image/png");
    }

    /**
     * <b>回归门</b> {@code docs/44 D2} 的刻意偏离：目录未命中（capabilities 为空）时
     * **按支持处理** —— 图片照留，宁可让 provider 响亮报错，也不静默丢用户读的图。
     */
    @Test
    void unknownCapabilitiesKeepImages() {
        var user = new Message.UserMessage(List.of(image()));

        assertThat(applied(UNKNOWN, user)).containsExactly("image:image/png");
    }

    /** <b>回归门</b> 目标模型未知（{@code null}）⇒ 同「未知」处理。 */
    @Test
    void nullModelKeepsImages() {
        var user = new Message.UserMessage(List.of(image()));

        assertThat(applied(null, user)).containsExactly("image:image/png");
    }

    /**
     * <b>回归门</b> pi 只降级 **user 与 toolResult**（{@code :41-53} 两个分支）——
     * assistant 消息里的图片块（java 的 {@code AssistantMessage.content} 是无限定的
     * {@code List<ContentBlock>}，类型上允许）**不在此处动**。
     */
    @Test
    void assistantMessagesAreNotDowngraded() {
        var assistant = new Message.AssistantMessage(List.of(image()), "stop", null,
                API, TARGET.provider(), TARGET.modelName(), null, null, null, null);

        assertThat(applied(NON_VISION, assistant)).containsExactly("image:image/png");
    }

    /**
     * <b>回归门</b> 无改动时**返回原对象**（pi 恒造新对象，但对象身份不是可观察行为 ——
     * 与 {@code gateAssistant} 同一取舍）；有改动时**其余字段全带**。
     */
    @Test
    void unchangedMessagesKeepTheirIdentityAndChangedOnesKeepTheirFields() {
        var untouched = new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")));
        var changed = new Message.ToolResultMessage("toolu_1", "read",
                List.of(image()), "details-payload", null, List.of(), true);

        var out = TransformMessages.apply(List.of(untouched, changed), TARGET, API, NON_VISION);

        assertThat(out.get(0)).isSameAs(untouched);
        assertThat(out.get(1)).isInstanceOfSatisfying(Message.ToolResultMessage.class, tool -> {
            assertThat(tool.details()).isEqualTo("details-payload");
            assertThat(tool.isError()).isTrue();
            assertThat(tool.toolName()).isEqualTo("read");
        });
    }
}
