package com.pijava.examples;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/status-line.ts:1-32} 移植（docs/27 W5）——
 * {@code ctx.ui.setStatus} 在 footer 扩展状态段显示回合进度。
 *
 * <p>逐字对位：{@code session_start} ⇒ {@code "Ready"}；{@code turn_start} ⇒
 * 计数 +1 并写 {@code "● Turn N..."}；{@code turn_end} ⇒ {@code "✓ Turn N complete"}。
 * 键恒为 {@code status-demo}（pi 逐字）。</p>
 *
 * <p>窄差见 {@link ThemeText}（docs/27 §3.4）：三处 {@code theme.fg} 只剩文本。</p>
 */
public final class StatusLineExtension implements PiExtension {

    /** pi L15：状态键（逐字）。 */
    static final String STATUS_KEY = "status-demo";

    private int turnCount;

    @Override
    public String name() {
        return "status-line";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            c.ui().setStatus(STATUS_KEY, readyText());
            return null;
        });
        ctx.on(ExtensionEventSpec.TURN_START, (event, c) -> {
            turnCount++;
            c.ui().setStatus(STATUS_KEY, runningText(turnCount));
            return null;
        });
        ctx.on(ExtensionEventSpec.TURN_END, (event, c) -> {
            c.ui().setStatus(STATUS_KEY, completeText(turnCount));
            return null;
        });
    }

    /** pi L15：{@code theme.fg("dim", "Ready")}。 */
    static String readyText() {
        return ThemeText.fg("dim", "Ready");
    }

    /** pi L21-22：{@code spinner + text}。 */
    static String runningText(int turns) {
        return ThemeText.fg("accent", "●") + ThemeText.fg("dim", " Turn " + turns + "...");
    }

    /** pi L28-29：{@code check + text}。 */
    static String completeText(int turns) {
        return ThemeText.fg("success", "✓") + ThemeText.fg("dim", " Turn " + turns + " complete");
    }

    /** 当前回合计数（测试探针）。 */
    int turnCount() {
        return turnCount;
    }
}
