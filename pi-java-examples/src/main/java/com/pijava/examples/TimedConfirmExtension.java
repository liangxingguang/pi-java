package com.pijava.examples;

import com.pijava.ai.AbortSignal;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionUiDialogOptions;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * pi {@code examples/extensions/timed-confirm.ts:1-70} 移植（docs/27 W6）——
 * 带超时/倒计时的确认与选择框。
 *
 * <p>逐字对位三件命令：{@code /timed}（{@code confirm} 携 5000ms 超时）、
 * {@code /timed-select}（{@code select} 携 10000ms 超时）、{@code /timed-signal}
 * （手写 {@link AbortSignal}＋5 秒后中止，二分支通知含 {@code warning}）。
 * 超时本身由宿主 {@link ExtensionUiDialogOptions#timeoutMillis()} 承担（TUI 倒计时），
 * 示例不再自建计时。</p>
 *
 * <p>唯一窄差沿 **D246**：java 扩展无注入式 {@code setTimeout}，{@code /timed-signal}
 * 的 5 秒中止经可注入 {@link TimeoutScheduler} 表达；默认实现不排程（不建真定时器），
 * 测试用捕获实现手动触发，从而三条分支都可断言。</p>
 */
public final class TimedConfirmExtension implements PiExtension {

    /** pi L19：确认框标题（逐字）。 */
    static final String CONFIRM_TITLE = "Timed Confirmation";

    /** pi L19：确认框正文（逐字）。 */
    static final String CONFIRM_BODY = "This dialog will auto-cancel in 5 seconds. Confirm?";

    /** pi L20：确认超时毫秒（逐字）。 */
    static final long CONFIRM_TIMEOUT_MS = 5000L;

    /** pi L34：选择框标题（逐字）。 */
    static final String SELECT_TITLE = "Pick an option";

    /** pi L34：选择项（逐字）。 */
    static final java.util.List<String> SELECT_OPTIONS =
        java.util.List.of("Option A", "Option B", "Option C");

    /** pi L34：选择超时毫秒（逐字）。 */
    static final long SELECT_TIMEOUT_MS = 10000L;

    /** 注入式单次定时器（pi {@code setTimeout}/{@code clearTimeout} 对位；D246）。 */
    @FunctionalInterface
    interface TimeoutScheduler {

        /**
         * 排一次延时任务。
         *
         * @param delayMillis 延时毫秒
         * @param task        到期执行的任务
         * @return 取消柄（pi {@code clearTimeout}）
         */
        Runnable schedule(long delayMillis, Runnable task);
    }

    private TimeoutScheduler scheduler = (delayMillis, task) -> () -> { };

    @Override
    public String name() {
        return "timed-confirm";
    }

    /** 定时器注入（测试手动触发）。 */
    void scheduler(TimeoutScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.slashCommands().register(ExampleCommands.command("timed",
            "Show a timed confirmation dialog (auto-cancels in 5s with countdown)",
            args -> timedConfirm(ctx)));
        ctx.slashCommands().register(ExampleCommands.command("timed-select",
            "Show a timed select dialog (auto-cancels in 10s with countdown)",
            args -> timedSelect(ctx)));
        ctx.slashCommands().register(ExampleCommands.command("timed-signal",
            "Show a timed confirm using AbortSignal (manual approach)",
            args -> timedSignal(ctx)));
    }

    /** pi L16-28：超时选项臂。 */
    void timedConfirm(ExtensionContext ctx) {
        var confirmed = ctx.ui().confirm(CONFIRM_TITLE, CONFIRM_BODY,
            new ExtensionUiDialogOptions(null, CONFIRM_TIMEOUT_MS));
        ctx.ui().notify(confirmed ? "Confirmed by user!" : "Cancelled or timed out",
            NotifyType.INFO);
    }

    /** pi L33-41：超时选择臂。 */
    void timedSelect(ExtensionContext ctx) {
        var choice = ctx.ui().select(SELECT_TITLE, SELECT_OPTIONS,
            new ExtensionUiDialogOptions(null, SELECT_TIMEOUT_MS));
        ctx.ui().notify(choice == null ? "Selection cancelled or timed out"
            : "Selected: " + choice, NotifyType.INFO);
    }

    /** pi L47-68：手写 AbortSignal 臂（三结局）。 */
    void timedSignal(ExtensionContext ctx) {
        var controller = AbortSignal.create();
        var cancel = scheduler.schedule(CONFIRM_TIMEOUT_MS, controller::abort);
        ctx.ui().notify("Dialog will auto-cancel in 5 seconds...", NotifyType.INFO);

        var confirmed = ctx.ui().confirm(CONFIRM_TITLE, CONFIRM_BODY,
            new ExtensionUiDialogOptions(controller, null));

        cancel.run();

        if (confirmed) {
            ctx.ui().notify("Confirmed by user!", NotifyType.INFO);
        } else if (controller.isAborted()) {
            ctx.ui().notify("Dialog timed out (auto-cancelled)", NotifyType.WARNING);
        } else {
            ctx.ui().notify("Cancelled by user", NotifyType.INFO);
        }
    }
}
