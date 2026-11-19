package com.pijava.examples;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/hidden-thinking-label.ts:1-53} 移植（docs/27 W5）——
 * {@code ctx.ui.setHiddenThinkingLabel} 定制思考块折叠时的标签。
 *
 * <p>逐字对位：{@code session_start} 施加默认标签 {@code "Pondering..."}；
 * {@code /thinking-label <text>} 设标签，无参数则回默认（先 {@code setHiddenThinkingLabel()}
 * 复位 ⇒ 再通知，文案逐字）。复位臂的 {@code undefined} 参数在 java 是 {@code null}。</p>
 */
public final class HiddenThinkingLabelExtension implements PiExtension {

    /** pi L23：默认标签（逐字）。 */
    static final String DEFAULT_LABEL = "Pondering...";

    private String label = DEFAULT_LABEL;

    @Override
    public String name() {
        return "hidden-thinking-label";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            applyLabel(c);
            return null;
        });
        ctx.slashCommands().register(ExampleCommands.command("thinking-label",
            "Set the hidden thinking label. Use without args to reset.",
            args -> setLabel(ctx, args)));
    }

    /** pi L28-30：把当前标签写给宿主。 */
    void applyLabel(ExtensionContext ctx) {
        ctx.ui().setHiddenThinkingLabel(label);
    }

    /** pi L36-52：命令处理器（返回文本断言用）。 */
    void setLabel(ExtensionContext ctx, String args) {
        var nextLabel = args == null ? "" : args.trim();
        if (nextLabel.isEmpty()) {
            label = DEFAULT_LABEL;
            ctx.ui().setHiddenThinkingLabel(null);
            ctx.ui().notify("Hidden thinking label reset to: " + DEFAULT_LABEL, null);
            return;
        }
        label = nextLabel;
        ctx.ui().setHiddenThinkingLabel(label);
        ctx.ui().notify("Hidden thinking label set to: " + label, null);
    }

    /** 当前标签（测试探针）。 */
    String label() {
        return label;
    }
}
