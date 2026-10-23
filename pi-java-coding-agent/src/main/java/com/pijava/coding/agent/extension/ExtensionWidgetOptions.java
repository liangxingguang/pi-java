package com.pijava.coding.agent.extension;

import org.jspecify.annotations.Nullable;

/**
 * 批 17（T1，docs/28）：pi {@code ExtensionWidgetOptions}（types.ts:133-140 逐字）——
 * 扩展 widget 槽位选项。
 *
 * @param placement {@link #ABOVE_EDITOR}（默认）| {@link #BELOW_EDITOR}；null ≙ 键省略
 */
public record ExtensionWidgetOptions(@Nullable String placement) {

    /** pi {@code WidgetPlacement = "aboveEditor"}（types.ts:132）。 */
    public static final String ABOVE_EDITOR = "aboveEditor";

    /** pi {@code WidgetPlacement = "belowEditor"}（types.ts:132）。 */
    public static final String BELOW_EDITOR = "belowEditor";

    /** 默认（placement 省略 ⇒ aboveEditor，pi types.ts:137 注释逐字）。 */
    public static final ExtensionWidgetOptions DEFAULT = new ExtensionWidgetOptions(null);
}
