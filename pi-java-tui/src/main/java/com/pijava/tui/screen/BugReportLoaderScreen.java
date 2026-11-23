package com.pijava.tui.screen;

import java.util.function.Consumer;
import java.util.function.IntSupplier;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.component.LoaderSpinner;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * {@code /bug} 的等待屏（批 15 T7，pi {@code BorderedLoader} 对位——摘要生成
 * 与上传两处的 loader，interactive-mode bug-report.ts:275 的 showLoader）。
 *
 * <p>L7 W1（docs/22 §4.5，C7/C8）：纯文本 → 共享 {@link BorderedLoaderFrame}
 * （上下 {@code ─} 框线＋80ms braille spinner＋cancel hint）；Esc ⇒ 置中止旗＋回调
 * （flow 用来 interrupt worker 线程；pi 的 {@code signal.abort()} 对位——
 * {@code CancellableLoader} 语义）。worker 完成由 flow 经 {@code openOverlay(null)}
 * 关屏，不经 {@link #isDone()}。</p>
 */
public final class BugReportLoaderScreen implements ScreenOverlay {

    private final String message;
    private final IntSupplier width;
    private final long startNanos = System.nanoTime();
    private volatile Runnable onAbort;
    private volatile boolean aborted;
    // volatile：写在键处理线程、读在渲染帧（isDone）——spotbugs
    // AT_STALE_THREAD_WRITE_OF_PRIMITIVE（ChatScreen indicator 同手法）。
    private volatile boolean done;

    /**
     * @param message 等待文案（pi 逐字）
     * @param onAbort Esc 回调（flow 的 worker interrupt）
     * @param width   终端列数（框线宽度；flow 经 {@code ChatScreen.terminalCols()} 注入）
     */
    public BugReportLoaderScreen(String message, Runnable onAbort, IntSupplier width) {
        this.message = message;
        this.onAbort = onAbort;
        this.width = width;
    }

    /** 晚绑定 Esc 回调（flow 在 worker 线程起来后换上有意义的 interrupt）。 */
    public void setOnAbort(Runnable abort) {
        this.onAbort = abort == null ? () -> { } : abort;
    }

    /** Handle keys: Esc aborts; everything else is swallowed (modal). */
    public boolean onKeyEvent(KeyEvent event) {
        if (event.isCancel()) {
            aborted = true;
            done = true;
            onAbort.run();
        }
        return true; // modal：其余键吞掉
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        // pi 的 restoreEditor：无后续（flow 轮 aborted 旗收尾）。
    }

    /** pi {@code loader.signal.aborted} 对位（worker 收尾判定）。 */
    public boolean aborted() {
        return aborted;
    }

    @Override
    public Element render() {
        return BorderedLoaderFrame.render(
            LoaderSpinner.frameAt(LoaderSpinner.DEFAULT_FRAMES,
                LoaderSpinner.DEFAULT_INTERVAL_MS, startNanos, System.nanoTime()),
            message, true, width.getAsInt());
    }
}
