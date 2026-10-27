package com.pijava.tui.component;

import java.util.List;
import java.util.function.Supplier;

import com.pijava.coding.agent.extension.MarkdownTransformContext.MarkdownMessageType;
import com.pijava.coding.agent.extension.MarkdownTransformer;

/**
 * 批 26（docs/37 T11，map/05 R6）：tui 侧的 markdown 变换器静态持有者——
 * {@code PiTuiApp} 在会话 attach/switch 时注入会话级链供应器，渲染臂在
 * {@code MarkdownRenderer.toLogicalLines} 之前应用（pi 的
 * {@code createMarkdownTransform} 缝对位——markdown-transform.ts:1-29 语义逐字：
 * 串行链、异常吞掉续下一个、null 返回丢弃）。
 *
 * <p>静态持有的理由同 {@link ToolRenderers}（MessageBubble 是纯静态投影）。</p>
 */
public final class MarkdownTransformers {

    private static volatile Supplier<List<MarkdownTransformer>> source = List::of;

    private MarkdownTransformers() {}

    /** 注入会话的变换链供应器（现读 ⇒ 会话内后注册的变换器即时生效，pi 同）。 */
    public static void install(Supplier<List<MarkdownTransformer>> value) {
        source = value == null ? List::of : value;
    }

    /**
     * pi {@code applyMarkdownTransformers}（markdown-transform.ts:16-28 逐字语义）：
     * 串行应用；handler 抛异常 ⇒ 保留当前文本继续；返回 null ⇒ 丢弃该次结果。
     *
     * @param messageType pi 的三值；{@code isStreaming} 恒 false、
     *        {@code availableWidth} 恒 -1（裁决④窄差）
     */
    public static String apply(String markdown, MarkdownMessageType messageType) {
        var chain = source.get();
        if (chain.isEmpty()) {
            return markdown;
        }
        var context = new com.pijava.coding.agent.extension.MarkdownTransformContext(
            messageType, false, -1);
        String current = markdown;
        for (var transformer : chain) {
            try {
                var transformed = transformer.transform(current, context);
                if (transformed != null) {
                    current = transformed;
                }
            } catch (RuntimeException e) {
                // pi：catch 静默续下一个（Keep the current Markdown and continue）
            }
        }
        return current;
    }
}
