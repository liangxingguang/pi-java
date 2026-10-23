package com.pijava.tui.app;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.util.TuiEventDispatcher;

/**
 * 批 17（T7，docs/28 §3-T7.1）：{@link TuiExtensionUI} 的宿主缝——持渲染线程
 * 基础设施（dispatcher/ChatScreen/键位表/倒计时调度器），overlay 开合与终端
 * 几何由 {@link PiTuiApp} 构造后<b>回填</b>（晚绑定 holder：host 必须先于
 * {@code AgentSession.create} 存在——扩展在 create 内加载，pi 的
 * {@code bindings.uiContext} 构造期传入对位，agent-session.ts:3260）。
 */
public final class TuiExtensionUiHost {

    private final TuiEventDispatcher dispatcher;
    private final ChatScreen chatScreen;
    private final KeybindingsManager keybindings;
    /** settings 的 externalEditor 现读源（pi getExternalEditorCommand 对位）。 */
    private final Supplier<String> externalEditorCommand;
    /** 共享倒计时调度器（daemon；pi CountdownTimer 的 setInterval 对位）。 */
    private final ScheduledExecutorService countdownScheduler =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "pi-tui-extension-countdown");
            thread.setDaemon(true);
            return thread;
        });

    // ── PiTuiApp 回填（bindApp）──
    private volatile Consumer<ScreenOverlay> overlayOpener = overlay -> { };
    private volatile Consumer<ScreenOverlay> overlayDismisser = overlay -> { };
    private volatile BiFunction<String, String, Optional<String>> externalEditorRoundtrip =
        (command, content) -> Optional.empty();
    private volatile IntSupplier terminalCols = () -> 80;
    private volatile IntSupplier terminalRows = () -> 24;

    // ── 渲染线程护栏（裁决⑤/窄差 D98）──
    private volatile Thread renderThread;
    private volatile boolean renderLoopActive;

    public TuiExtensionUiHost(TuiEventDispatcher dispatcher, ChatScreen chatScreen,
                              KeybindingsManager keybindings,
                              Supplier<String> externalEditorCommand) {
        this.dispatcher = dispatcher;
        this.chatScreen = chatScreen;
        this.keybindings = keybindings;
        this.externalEditorCommand = externalEditorCommand;
    }

    /** PiTuiApp 构造后回填（晚绑定）：overlay 开合＋外部编辑器往返＋终端几何。 */
    public void bindApp(Consumer<ScreenOverlay> opener, Consumer<ScreenOverlay> dismisser,
                        BiFunction<String, String, Optional<String>> editorRoundtrip,
                        IntSupplier cols, IntSupplier rows) {
        this.overlayOpener = opener;
        this.overlayDismisser = dismisser;
        this.externalEditorRoundtrip = editorRoundtrip;
        this.terminalCols = cols;
        this.terminalRows = rows;
    }

    /** 渲染循环起步（PiTuiApp.start/startInline 对位——此后阻塞对话可用）。 */
    public void markRenderLoopActive(Thread thread) {
        this.renderThread = thread;
        this.renderLoopActive = true;
    }

    /** 开 overlay（渲染线程生效——窄差 D96 族的时序）。 */
    public void openOverlay(ScreenOverlay overlay) {
        dispatcher.dispatch(() -> overlayOpener.accept(overlay));
    }

    /** 摘掉 overlay（渲染线程生效；仅当仍是当前 overlay——{@code apply} 由摘路径负责）。 */
    public void dismissOverlay(ScreenOverlay overlay) {
        dispatcher.dispatch(() -> overlayDismisser.accept(overlay));
    }

    /** 标脏/请求重绘（pi {@code tui.requestRender()} 对位——空任务经 wake 标脏）。 */
    public void requestRender() {
        dispatcher.dispatch(() -> { });
    }

    /** 渲染线程自调用检测（D98）。 */
    public boolean onRenderThread() {
        return Thread.currentThread() == renderThread;
    }

    /** 渲染循环已起步（裁决⑤：未起步时阻塞对话不可用）。 */
    public boolean renderLoopActive() {
        return renderLoopActive;
    }

    public TuiEventDispatcher dispatcher() {
        return dispatcher;
    }

    public ChatScreen chatScreen() {
        return chatScreen;
    }

    public KeybindingsManager keybindings() {
        return keybindings;
    }

    public Supplier<String> externalEditorCommand() {
        return externalEditorCommand;
    }

    public ScheduledExecutorService countdownScheduler() {
        return countdownScheduler;
    }

    public IntSupplier terminalCols() {
        return terminalCols;
    }

    public IntSupplier terminalRows() {
        return terminalRows;
    }

    /** 外部编辑器往返（命令, 内容) ⇒ 读回内容；空 ⇒ 不动（pi 非 complete 臂）。 */
    public BiFunction<String, String, Optional<String>> externalEditorRoundtrip() {
        return externalEditorRoundtrip;
    }
}
