package com.pijava.examples;

import com.pijava.coding.agent.extension.ExtensionCompactOptions;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionContextUsage;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/trigger-compact.ts:1-49} 移植（docs/51 §2.4）——
 * {@code turn_end} 读 {@code ctx.getContextUsage()} 的 token 数，上一轮不超过
 * 10 万且本轮超过时自动压缩；{@code /trigger-compact} 可立即压缩（参数作自定义指令）。
 */
public final class TriggerCompactExtension implements PiExtension {

    /** pi {@code COMPACT_THRESHOLD_TOKENS}。 */
    static final int COMPACT_THRESHOLD_TOKENS = 100_000;

    /** pi 的 {@code let previousTokens: number | null | undefined}。 */
    private Integer previousTokens;

    @Override
    public String name() {
        return "trigger-compact";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.TURN_END, (event, c) -> {
            var currentTokens = c.getContextUsage()
                .map(ExtensionContextUsage::tokens).orElse(null);
            if (currentTokens == null) {
                return null;
            }

            var crossedThreshold = crossed(previousTokens, currentTokens);
            previousTokens = currentTokens;
            if (!crossedThreshold) {
                return null;
            }
            triggerCompaction(c, null);
            return null;
        });

        ctx.slashCommands().register(ExampleCommands.command("trigger-compact",
            "Trigger compaction immediately", "",
            args -> triggerCompaction(ctx,
                args.strip().isEmpty() ? null : args.strip())));
    }

    /**
     * pi 的跨线判据（纯函数，供单测）：上一轮有值且未超线、本轮超线 ⇒ true。
     *
     * @param previous 上一轮 token 数（{@code null} ≙ pi 的 undefined/null）
     * @param current  本轮 token 数
     * @return whether the threshold was crossed this turn
     */
    static boolean crossed(Integer previous, int current) {
        return previous != null
            && previous <= COMPACT_THRESHOLD_TOKENS
            && current > COMPACT_THRESHOLD_TOKENS;
    }

    private void triggerCompaction(ExtensionContext c, String customInstructions) {
        if (c.hasUi()) {
            c.ui().notify("Compaction started", NotifyType.INFO);
        }
        c.compact(new ExtensionCompactOptions(customInstructions,
            result -> {
                if (c.hasUi()) {
                    c.ui().notify("Compaction completed", NotifyType.INFO);
                }
            },
            error -> {
                if (c.hasUi()) {
                    c.ui().notify("Compaction failed: " + error.getMessage(),
                        NotifyType.ERROR);
                }
            }));
    }
}
