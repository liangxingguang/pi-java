package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

/** docs/27 W5：working-indicator 验收——五种模式、帧表着色、命令四臂。 */
class WorkingIndicatorExtensionTest {

    @Test
    void framesAndPaletteArePiLiterals() {
        assertThat(WorkingIndicatorExtension.SPINNER_FRAMES)
            .containsExactly("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏");
        assertThat(WorkingIndicatorExtension.PASTEL_RAINBOW).hasSize(6);
        assertThat(WorkingIndicatorExtension.RESET_FG).isEqualTo("\u001b[39m");
    }

    @Test
    void indicatorsPerMode() {
        var dot = WorkingIndicatorExtension.indicator(WorkingIndicatorExtension.Mode.DOT);
        assertThat(dot.frames()).containsExactly("\u001b[38;2;255;179;186m●\u001b[39m");
        assertThat(dot.intervalMs()).isNull();

        var none = WorkingIndicatorExtension.indicator(WorkingIndicatorExtension.Mode.NONE);
        assertThat(none.frames()).isEmpty();

        var pulse = WorkingIndicatorExtension.indicator(WorkingIndicatorExtension.Mode.PULSE);
        assertThat(pulse.frames()).hasSize(4);
        assertThat(pulse.intervalMs()).isEqualTo(120L);

        var spinner = WorkingIndicatorExtension.indicator(
            WorkingIndicatorExtension.Mode.SPINNER);
        assertThat(spinner.frames()).hasSize(10);
        assertThat(spinner.intervalMs()).isEqualTo(80L);
        // 逐帧轮转六色（i % 6）
        assertThat(spinner.frames().get(0)).startsWith("\u001b[38;2;255;179;186m⠋");
        assertThat(spinner.frames().get(6)).startsWith("\u001b[38;2;255;179;186m⠦");
        assertThat(spinner.frames().get(7)).startsWith("\u001b[38;2;255;223;186m⠧");
        assertThat(spinner.frames().get(8)).startsWith("\u001b[38;2;255;255;186m⠇");

        assertThat(WorkingIndicatorExtension.indicator(
            WorkingIndicatorExtension.Mode.DEFAULT)).isNull();
    }

    @Test
    void modeDescriptions() {
        assertThat(WorkingIndicatorExtension.describeMode(
            WorkingIndicatorExtension.Mode.DOT)).isEqualTo("static dot");
        assertThat(WorkingIndicatorExtension.describeMode(
            WorkingIndicatorExtension.Mode.NONE)).isEqualTo("hidden");
        assertThat(WorkingIndicatorExtension.describeMode(
            WorkingIndicatorExtension.Mode.PULSE)).isEqualTo("custom pulse");
        assertThat(WorkingIndicatorExtension.describeMode(
            WorkingIndicatorExtension.Mode.SPINNER)).isEqualTo("custom spinner");
        assertThat(WorkingIndicatorExtension.describeMode(
            WorkingIndicatorExtension.Mode.DEFAULT)).isEqualTo("pi default spinner");
    }

    @Test
    void sessionStartAppliesSpinnerAndStatus() {
        var w = new W3Context();
        var ext = new WorkingIndicatorExtension();
        ext.register(w.ctx);

        w.fire(ExtensionEventSpec.SESSION_START, null);

        assertThat(ext.mode()).isEqualTo(WorkingIndicatorExtension.Mode.SPINNER);
        assertThat(w.ui.workingIndicators()).hasSize(1);
        assertThat(w.ui.workingIndicators().get(0).intervalMs()).isEqualTo(80L);
        assertThat(w.ui.statuses()).containsEntry("working-indicator",
            "Indicator: custom spinner");
    }

    @Test
    void commandArms() {
        var w = new W3Context();
        var ext = new WorkingIndicatorExtension();
        ext.register(w.ctx);

        // 无参数 ⇒ 报当前模式
        w.command("working-indicator", "");
        assertThat(lastNotice(w)).isEqualTo("Working indicator: custom spinner");

        // 非法参数 ⇒ 用法（error）
        w.command("working-indicator", "wobble");
        assertThat(lastNotice(w))
            .isEqualTo("Usage: /working-indicator [dot|pulse|none|spinner|reset]");
        assertThat(lastType(w)).isEqualTo(NotifyType.ERROR);
        assertThat(ext.mode()).isEqualTo(WorkingIndicatorExtension.Mode.SPINNER);

        // 合法参数 ⇒ 施加并通知
        w.command("working-indicator", " PULSE ");
        assertThat(ext.mode()).isEqualTo(WorkingIndicatorExtension.Mode.PULSE);
        assertThat(w.ui.statuses()).containsEntry("working-indicator",
            "Indicator: custom pulse");
        assertThat(lastNotice(w)).isEqualTo("Working indicator set to: custom pulse");

        // reset ⇒ 回默认（options 传 null）
        w.command("working-indicator", "reset");
        assertThat(ext.mode()).isEqualTo(WorkingIndicatorExtension.Mode.DEFAULT);
        assertThat(w.ui.workingIndicators()).last().isNull();
        assertThat(w.ui.statuses()).containsEntry("working-indicator",
            "Indicator: pi default spinner");
    }

    private static String lastNotice(W3Context w) {
        var notices = w.ui.notices();
        return notices.isEmpty() ? null : notices.get(notices.size() - 1).message();
    }

    private static NotifyType lastType(W3Context w) {
        var notices = w.ui.notices();
        return notices.isEmpty() ? null : notices.get(notices.size() - 1).type();
    }

    @Test
    void colorizeWrapsWithReset() {
        assertThat(WorkingIndicatorExtension.colorize("x", "\u001b[38;2;1;2;3m"))
            .isEqualTo("\u001b[38;2;1;2;3mx\u001b[39m");
        assertThat(WorkingIndicatorExtension.spinnerFrames()).hasSize(
            WorkingIndicatorExtension.SPINNER_FRAMES.size());
    }
}
