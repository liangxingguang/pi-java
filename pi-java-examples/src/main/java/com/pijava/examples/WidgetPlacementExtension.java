package com.pijava.examples;

import java.util.List;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionWidgetContent;
import com.pijava.coding.agent.extension.ExtensionWidgetOptions;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/widget-placement.ts:1-9} 移植（docs/27 W5）——
 * {@code setWidget} 的 aboveEditor／belowEditor 两区放置。
 *
 * <p>逐字对位：{@code session_start} 且 {@code ctx.hasUI} 时写两个键；{@code widget-above}
 * 省略 placement（宿主默认 aboveEditor），{@code widget-below} 显式
 * {@code "belowEditor"}。无 UI 会话（print/json）不写。</p>
 */
public final class WidgetPlacementExtension implements PiExtension {

    /** pi L6：上区键（逐字）。 */
    static final String ABOVE_KEY = "widget-above";

    /** pi L7：下区键（逐字）。 */
    static final String BELOW_KEY = "widget-below";

    /** pi L6：上区文本行（逐字）。 */
    static final List<String> ABOVE_LINES = List.of("Above editor widget");

    /** pi L7：下区文本行（逐字）。 */
    static final List<String> BELOW_LINES = List.of("Below editor widget");

    @Override
    public String name() {
        return "widget-placement";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            if (!c.hasUi()) {
                return null;
            }
            c.ui().setWidget(ABOVE_KEY, new ExtensionWidgetContent.Lines(ABOVE_LINES),
                ExtensionWidgetOptions.DEFAULT);
            c.ui().setWidget(BELOW_KEY, new ExtensionWidgetContent.Lines(BELOW_LINES),
                new ExtensionWidgetOptions(ExtensionWidgetOptions.BELOW_EDITOR));
            return null;
        });
    }
}
