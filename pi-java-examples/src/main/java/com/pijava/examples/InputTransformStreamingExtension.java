package com.pijava.examples;

import java.util.List;
import java.util.regex.Pattern;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.InputEventResult;

/**
 * pi {@code examples/extensions/input-transform-streaming.ts:16-39} 移植（docs/40 P8）——
 * 命中触发词（changes/diff/modified）且 {@code git diff --stat} 有输出 ⇒ transform 拼上
 * 未提交变更摘要；{@code streamingBehavior=steer} ⇒ continue（跳过 exec，纠偏要快）。
 */
public final class InputTransformStreamingExtension implements PiExtension {

    /** pi 的 {@code /\b(changes?|diff|modified)\b/i} 逐字。 */
    static final Pattern TRIGGER =
        Pattern.compile("\\b(changes?|diff|modified)\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "input-transform-streaming";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.INPUT, (event, c) -> {
            // During steering, skip the exec call — corrections should be fast
            if ("steer".equals(event.streamingBehavior())) {
                return new InputEventResult.Continue();
            }

            if (!TRIGGER.matcher(event.text()).find()) {
                return new InputEventResult.Continue();
            }

            var result = c.exec("git", List.of("diff", "--stat"), null);
            var stdout = result.stdout().trim();
            if (result.code() != 0 || stdout.isEmpty()) {
                return new InputEventResult.Continue();
            }

            return new InputEventResult.Transform(
                event.text() + "\n\nCurrent uncommitted changes:\n```\n" + stdout + "\n```",
                null);
        });
    }
}
