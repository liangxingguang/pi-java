package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.pijava.agent.harness.LaneState;
import com.pijava.agent.hook.HookSystem;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.bridge.EngineHookBridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 11 T6（docs/22 §3.11/§3.12，pi sdk.ts:296-329 逐字）：{@code blockImages}
 * 主循环过滤——user 与 toolResult 两角色的 {@code ImageContent} 换
 * "Image reading is disabled." 文本块、连续占位去重；assistant 不动；
 * URL 图片不过滤；桥尾部动态现读（mid-session changes take effect）。
 */
class ImageBlockFilterTest {

    private static ContentBlock.ImageContent image() {
        return new ContentBlock.ImageContent("image/png", "aGVsbG8=");
    }

    private static Message.UserMessage user(ContentBlock... blocks) {
        return new Message.UserMessage(List.of(blocks));
    }

    private static Message.AssistantMessage assistant(String text) {
        return new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static ContentBlock.TextContent textOf(Message m, int index) {
        return (ContentBlock.TextContent) m.content().get(index);
    }

    /** user 臂：image ⇒ 占位文本逐字替换。 */
    @Test
    void userImageBecomesPlaceholder() {
        var out = ImageBlockFilter.apply(List.of(user(image())));

        assertThat(out).hasSize(1);
        assertThat(textOf(out.get(0), 0).text()).isEqualTo(ImageBlockFilter.PLACEHOLDER);
    }

    /** toolResult 臂同样过滤（pi 的 {@code role === "toolResult"}）。 */
    @Test
    void toolResultImageBecomesPlaceholder() {
        var toolResult = new Message.ToolResultMessage("tu1", "read",
            List.of(image()), false);

        var out = ImageBlockFilter.apply(List.of(toolResult));

        assertThat(textOf(out.get(0), 0).text()).isEqualTo(ImageBlockFilter.PLACEHOLDER);
    }

    /** 连续 image ⇒ 单个占位（pi 的连续占位文本去重）。 */
    @Test
    void consecutiveImagesDedupeToSinglePlaceholder() {
        var out = ImageBlockFilter.apply(List.of(user(image(), image(), image())));

        assertThat(out.get(0).content()).hasSize(1);
        assertThat(textOf(out.get(0), 0).text()).isEqualTo(ImageBlockFilter.PLACEHOLDER);
    }

    /** image 贴着既有占位文本 ⇒ 也去重（pi 检查映射后数组的前一元素）。 */
    @Test
    void imageAdjacentToExistingPlaceholderDedupes() {
        var out = ImageBlockFilter.apply(List.of(
            user(new ContentBlock.TextContent(ImageBlockFilter.PLACEHOLDER), image())));

        assertThat(out.get(0).content()).hasSize(1);
    }

    /** 非连续 image 各自成占位；普通文本原样保留。 */
    @Test
    void separatedImagesKeepTheirOwnPlaceholders() {
        var out = ImageBlockFilter.apply(List.of(
            user(image(), new ContentBlock.TextContent("note"), image())));

        assertThat(out.get(0).content()).hasSize(3);
        assertThat(textOf(out.get(0), 0).text()).isEqualTo(ImageBlockFilter.PLACEHOLDER);
        assertThat(textOf(out.get(0), 1).text()).isEqualTo("note");
        assertThat(textOf(out.get(0), 2).text()).isEqualTo(ImageBlockFilter.PLACEHOLDER);
    }

    /** assistant 不动；URL 图片不过滤（pi 转换层无此变体，注释钉死）。 */
    @Test
    void assistantAndUrlImagesAreUntouched() {
        var assistant = assistant("see");
        var withUrl = user(new ContentBlock.UrlImageContent("https://x/img.png"));

        var out = ImageBlockFilter.apply(List.of(assistant, withUrl));

        assertThat(out.get(0)).isSameAs(assistant);
        assertThat(out.get(1).content().get(0))
            .isInstanceOf(ContentBlock.UrlImageContent.class);
    }

    /** 无图片 ⇒ 消息引用不动。 */
    @Test
    void noImagesKeepsMessageInstances() {
        var plain = user(new ContentBlock.TextContent("hi"));

        var out = ImageBlockFilter.apply(List.of(plain));

        assertThat(out.get(0)).isSameAs(plain);
    }

    /** {@code getBlockImages}：缺席/false ⇒ false；true ⇒ true（pi :1311-1313）。 */
    @Test
    void getBlockImagesDefaultsFalse(@TempDir Path dir) throws Exception {
        var storage = new FileSettingsStorage(dir, dir);
        assertThat(SettingsManager.withStorage(storage).accessors().getBlockImages())
            .isFalse();

        Files.writeString(dir.resolve("settings.json"), "{\"images\":{\"blockImages\":true}}");
        assertThat(SettingsManager.withStorage(storage).accessors().getBlockImages())
            .isTrue();
    }

    /** 桥尾部动态现读：关 ⇒ 不过滤（且无处理器时保持 null 快走）；开 ⇒ 过滤。 */
    @Test
    void bridgeTailReadsSettingDynamically() {
        var enabled = new AtomicBoolean(false);
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", new ExtensionEventBus(), null,
            enabled::get);
        var messages = List.<Message>of(user(image()));

        var off = hooks.fireTransformContext("default", messages);
        assertThat(off.get(0).content().get(0))
            .isInstanceOf(ContentBlock.ImageContent.class);

        enabled.set(true);
        var on = hooks.fireTransformContext("default", messages);
        assertThat(textOf(on.get(0), 0).text()).isEqualTo(ImageBlockFilter.PLACEHOLDER);
    }
}
