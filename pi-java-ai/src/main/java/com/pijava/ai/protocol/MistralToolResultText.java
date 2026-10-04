package com.pijava.ai.protocol;

/**
 * pi {@code mistral-conversations.ts:877-897} 的 {@code buildToolResultText} —— **整块逐行照抄**
 * （{@code 原 docs/44} 待裁决 ③）：错误前缀 ＋ trim ＋ 不支持时的图片省略后缀 ＋ 三个占位串。
 *
 * <p>⚠️ 本函数是 java 侧三处**非图片**行为变更的来源：{@code "[tool error] "} 前缀、文本 trim、
 * 空结果的 {@code "(no tool output)"}（旧实现发空串且完全不看 {@code isError}）。</p>
 *
 * <p>从 {@link MistralConversationsApi} 搬出（A1，{@code docs/08} §A1）：新增
 * {@code Message.SystemMessage} 变体后，那个类的 sealed switch 必须补一个分支，而它当时
 * 恰好压在 500 行的上限上。**零行为改动** —— pi 侧本来也是独立函数，本类只是把同一块代码
 * 放回独立文件。</p>
 */
final class MistralToolResultText {

    private MistralToolResultText() {
    }

    /** 工具结果的文本部分（pi {@code :877-897}，逐行照抄）。 */
    static String build(String text, boolean hasImages, boolean supportsImages, boolean isError) {
        var trimmed = text.trim();
        var errorPrefix = isError ? "[tool error] " : "";
        if (!trimmed.isEmpty()) {
            var imageSuffix = hasImages && !supportsImages
                    ? "\n[tool image omitted: model does not support images]" : "";
            return errorPrefix + trimmed + imageSuffix;
        }
        if (hasImages) {
            if (supportsImages) {
                return isError ? "[tool error] (see attached image)" : "(see attached image)";
            }
            return isError
                    ? "[tool error] (image omitted: model does not support images)"
                    : "(image omitted: model does not support images)";
        }
        return isError ? "[tool error] (no tool output)" : "(no tool output)";
    }
}
