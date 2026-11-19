package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;

/** docs/27 W6：timed-confirm 验收——三命令的超时选项与 AbortSignal 三结局。 */
class TimedConfirmExtensionTest {

    /** 捕获式定时器（记录延时与任务，手动触发）。 */
    private static final class RecordingScheduler
            implements TimedConfirmExtension.TimeoutScheduler {

        private final List<Long> delays = new ArrayList<>();
        private Runnable task;
        private boolean cancelled;

        @Override
        public Runnable schedule(long delayMillis, Runnable runnable) {
            delays.add(delayMillis);
            task = runnable;
            return () -> cancelled = true;
        }

        void fire() {
            task.run();
        }
    }

    @Test
    void constantsArePiLiterals() {
        assertThat(TimedConfirmExtension.CONFIRM_TITLE).isEqualTo("Timed Confirmation");
        assertThat(TimedConfirmExtension.CONFIRM_BODY)
            .isEqualTo("This dialog will auto-cancel in 5 seconds. Confirm?");
        assertThat(TimedConfirmExtension.CONFIRM_TIMEOUT_MS).isEqualTo(5000L);
        assertThat(TimedConfirmExtension.SELECT_TIMEOUT_MS).isEqualTo(10000L);
        assertThat(TimedConfirmExtension.SELECT_OPTIONS)
            .containsExactly("Option A", "Option B", "Option C");
    }

    @Test
    void timedConfirmCarriesTimeoutOption() {
        var w = new W3Context();
        var ext = new TimedConfirmExtension();
        ext.register(w.ctx);

        w.ui.confirmAnswer(true);
        w.command("timed", "");

        assertThat(w.ui.lastDialogOptions().timeoutMillis()).isEqualTo(5000L);
        assertThat(w.ui.lastDialogOptions().signal()).isNull();
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Confirmed by user!");
        assertThat(w.ui.notices().get(0).type()).isEqualTo(NotifyType.INFO);
    }

    @Test
    void timedConfirmTimeoutText() {
        var w = new W3Context();
        new TimedConfirmExtension().register(w.ctx);

        w.ui.confirmAnswer(false);
        w.command("timed", "");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Cancelled or timed out");
    }

    @Test
    void timedSelectBothArms() {
        var w = new W3Context();
        new TimedConfirmExtension().register(w.ctx);

        w.ui.answer("Option B");
        w.command("timed-select", "");
        assertThat(w.ui.lastDialogOptions().timeoutMillis()).isEqualTo(10000L);
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Selected: Option B");

        w.ui.answer(null);
        w.command("timed-select", "");
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .last().isEqualTo("Selection cancelled or timed out");
    }

    @Test
    void timedSignalConfirmedClearsTimer() {
        var w = new W3Context();
        var ext = new TimedConfirmExtension();
        var scheduler = new RecordingScheduler();
        ext.scheduler(scheduler);
        ext.register(w.ctx);

        w.ui.confirmAnswer(true);
        w.command("timed-signal", "");

        assertThat(scheduler.delays).containsExactly(5000L);
        assertThat(scheduler.cancelled).isTrue();
        assertThat(w.ui.lastDialogOptions().signal()).isNotNull();
        assertThat(w.ui.lastDialogOptions().timeoutMillis()).isNull();
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Dialog will auto-cancel in 5 seconds...",
                "Confirmed by user!");
    }

    @Test
    void timedSignalFiresAbortBeforeAnswer() {
        var w = new W3Context();
        var ext = new TimedConfirmExtension();
        // 5 秒先到：排程即刻执行（模拟倒计时耗尽后才拿到 false）
        ext.scheduler((delayMillis, task) -> {
            task.run();
            return () -> { };
        });
        ext.register(w.ctx);

        w.ui.confirmAnswer(false);
        w.command("timed-signal", "");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Dialog will auto-cancel in 5 seconds...",
                "Dialog timed out (auto-cancelled)");
        assertThat(w.ui.notices().get(1).type()).isEqualTo(NotifyType.WARNING);
    }

    @Test
    void timedSignalUserCancelledBeforeTimer() {
        var w = new W3Context();
        var ext = new TimedConfirmExtension();
        var scheduler = new RecordingScheduler();
        ext.scheduler(scheduler);
        ext.register(w.ctx);

        w.ui.confirmAnswer(false);
        w.command("timed-signal", "");

        assertThat(scheduler.cancelled).isTrue();
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Dialog will auto-cancel in 5 seconds...", "Cancelled by user");
        assertThat(w.ui.notices().get(1).type()).isEqualTo(NotifyType.INFO);
    }
}
