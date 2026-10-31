package com.pijava.tui.component;

import java.util.function.BooleanSupplier;

/** assistant 消息投影的现读设置（thinking 显隐＋工具输出展开）。 */
public final class MessageBubbleVisibility {

    private static volatile BooleanSupplier hideThinking = () -> false;
    private static volatile BooleanSupplier toolsExpansion = () -> false;

    private MessageBubbleVisibility() {}

    /** Install the current session's hideThinkingBlock supplier. */
    public static void install(BooleanSupplier supplier) {
        hideThinking = supplier == null ? () -> false : supplier;
    }

    /** 批 34（K8；pi {@code toolOutputExpanded} :4561）：全局工具输出展开态现读缝。 */
    public static void installToolsExpanded(BooleanSupplier supplier) {
        toolsExpansion = supplier == null ? () -> false : supplier;
    }

    /** Whether thinking blocks should be omitted at render time. */
    static boolean hiddenThinking() {
        return hideThinking.getAsBoolean();
    }

    /** 批 34（K8）：工具输出投影是否按展开臂渲染。 */
    static boolean toolsExpanded() {
        return toolsExpansion.getAsBoolean();
    }
}
