package com.pijava.examples;

import java.util.List;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.WorkingIndicatorOptions;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/working-message-test.ts:1-25} 移植（docs/27 W5）——
 * 自定义 working 文案与指示帧在 loader 重建（回合之间）后仍然保持。
 *
 * <p>逐字对位：{@code session_start} 写 {@code setWorkingMessage} 与
 * {@code setWorkingIndicator}。两者都用<b>裸 24bit ANSI</b>——pi 注释明确「颜色须扩展
 * 自带」，宿主只逐字渲染（{@code WorkingIndicatorOptions} 的同条纪律）。</p>
 */
public final class WorkingMessageTestExtension implements PiExtension {

    /** pi L17：{@code "Working... (custom)"}（褐 {@code 0x9B563F}，逐字）。 */
    static final String CUSTOM_MESSAGE = "\u001b[38;2;155;86;63mWorking... (custom)\u001b[39m";

    /** pi L18：帧表（单帧褐点）。 */
    static final WorkingIndicatorOptions CUSTOM_INDICATOR =
        new WorkingIndicatorOptions(List.of("\u001b[38;2;155;86;63m●\u001b[39m"), null);

    @Override
    public String name() {
        return "working-message-test";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            c.ui().setWorkingMessage(CUSTOM_MESSAGE);
            c.ui().setWorkingIndicator(CUSTOM_INDICATOR);
            return null;
        });
    }
}
