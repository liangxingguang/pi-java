package com.pijava.coding.agent.extension;

/**
 * 批 26（docs/37 T11，E5-9）：pi {@code MarkdownTransformer}（types.ts:1519）——
 * 渲染前变换 user/assistant/thinking 的 markdown 源。
 */
@FunctionalInterface
public interface MarkdownTransformer {

    /**
     * pi {@code (markdown, context) => string}（markdown-transform.ts:18-28 语义）：
     * 链式串行（上出下进）；返回 {@code null} ≙ pi 的非 string 返回 ⇒ 丢弃该次结果；
     * 抛异常 ⇒ 保留当前文本继续下一个（静默吞）。
     */
    String transform(String markdown, MarkdownTransformContext context);

    /**
     * L5 W6（pi {@code MarkdownTransformerOptions.messageTypes}，loader.ts:253）：
     * 生效面；缺省三值——链内按 {@code context.messageType()} 过滤，不命中即原样透传。
     */
    default java.util.Set<MarkdownTransformContext.MarkdownMessageType> messageTypes() {
        return java.util.Set.of(
            MarkdownTransformContext.MarkdownMessageType.USER,
            MarkdownTransformContext.MarkdownMessageType.ASSISTANT,
            MarkdownTransformContext.MarkdownMessageType.ASSISTANT_THINKING);
    }
}
