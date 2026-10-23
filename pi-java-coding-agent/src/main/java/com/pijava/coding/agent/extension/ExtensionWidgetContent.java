package com.pijava.coding.agent.extension;

import java.util.List;

/**
 * 批 17（T1，docs/28）：{@code setWidget} 的内容——pi 的两重载
 * （types.ts:181-187：{@code string[]} | 组件工厂）在 java 收敛为 sealed ADT
 * （变体携带不同载荷 ⇒ sealed interface + record，CLAUDE.md 规范）；
 * {@code null} ≙ pi 的 {@code undefined}（清槽）。
 */
public sealed interface ExtensionWidgetContent {

    /** 文本行臂（pi {@code string[]}；超 {@code MAX_WIDGET_LINES=10} 截断）。 */
    record Lines(List<String> lines) implements ExtensionWidgetContent {
        /** 防御性拷贝。 */
        public Lines {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** 组件工厂臂（pi {@code (tui, theme) => Component}）。 */
    record Factory(ExtensionSlotFactory factory) implements ExtensionWidgetContent {}
}
