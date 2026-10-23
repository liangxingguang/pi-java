package com.pijava.tui.app;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;
import com.pijava.coding.agent.core.ExternalEditorSupport;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionComponentFactory;
import com.pijava.coding.agent.extension.ExtensionCustomOptions;
import com.pijava.coding.agent.extension.ExtensionFooterFactory;
import com.pijava.coding.agent.extension.ExtensionOverlayHandle;
import com.pijava.coding.agent.extension.ExtensionSlotFactory;
import com.pijava.coding.agent.extension.ExtensionUI;
import com.pijava.coding.agent.extension.ExtensionUiDialogOptions;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.ExtensionWidgetContent;
import com.pijava.coding.agent.extension.ExtensionWidgetOptions;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.tui.screen.ExtensionComponentOverlay;
import com.pijava.tui.screen.ExtensionDialog;
import com.pijava.tui.screen.ExtensionEditorOverlay;
import com.pijava.tui.screen.ExtensionInputOverlay;
import com.pijava.tui.screen.ExtensionSelectorOverlay;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.util.TextLayout;

/**
 * 批 17（T4–T6，docs/28）：TUI 模式的 {@link ExtensionUI} 实现——pi
 * {@code interactive-mode.ts#createExtensionUIContext}（:2635-2659）的 java 对位。
 *
 * <p>对话四件套＋custom 是<b>阻塞语义</b>（pi 的 Promise 在 java 收敛为调用线程
 * 等待——扩展跑在非渲染线程）；overlay 开合经 dispatcher 入渲染线程
 * （窄差 D96 族）。护栏：渲染线程自调用／渲染循环未起步 ⇒
 * {@link IllegalStateException}（裁决⑤／窄差 D98——pi 单线程无此形态）。</p>
 */
public final class TuiExtensionUI implements ExtensionUI {

    /** 活动对话框（onSessionInvalidated 复位面；跨线程 ⇒ COW）。 */
    private record ActiveDialog(ExtensionDialog dialog, ScreenOverlay overlay,
                                CompletableFuture<Object> result) {}

    private final TuiExtensionUiHost host;
    private final ExtensionUiHostEnv hostEnv;
    private final List<ActiveDialog> activeDialogs = new CopyOnWriteArrayList<>();

    public TuiExtensionUI(TuiExtensionUiHost host) {
        this.host = host;
        this.hostEnv = new ExtensionUiHostEnv(host.terminalCols(), host.terminalRows(),
            host::requestRender, TuiExtensionUI::colorize);
    }

    /** 扩展组件的主题色槽位投影（pi {@code theme.fg(slot, text)} ⇒ TamboUI markup）。 */
    static String colorize(String slot, String text) {
        if (text == null) {
            return "";
        }
        var tag = switch (slot) {
            case "accent" -> "cyan";
            case "muted" -> "gray";
            case "warning" -> "yellow";
            case "success" -> "green";
            case "error" -> "red";
            case "borderAccent" -> "magenta";
            case "dim" -> "dim";
            default -> null;    // text 等无槽位 ⇒ 原样
        };
        if (tag == null) {
            return text;
        }
        return "[" + tag + "]" + TextLayout.escapeMarkup(text) + "[/]";
    }

    /** 宿主环境（组件工厂消费）。 */
    public ExtensionUiHostEnv hostEnv() {
        return hostEnv;
    }

    // ── 阻塞对话四件套（pi :2690-2865 序对位）────────────────

    @Override
    public @Nullable String select(String title, List<String> options,
                                   @Nullable ExtensionUiDialogOptions opts) {
        guardBlocking();
        AbortSignal signal = opts != null ? opts.signal() : null;
        if (signal != null && signal.isAborted()) {
            return null;    // pi :2694 预检直返
        }
        var result = new CompletableFuture<String>();
        var overlay = new ExtensionSelectorOverlay(title, options, host.keybindings(),
            host.terminalCols(), result);
        return runDialog(overlay, overlay, result, signal,
            opts != null ? opts.timeoutMillis() : null);
    }

    /** pi :2749-2758 逐字——confirm ＝ selector 特化（{@code title\nmessage}，Yes/No）。 */
    @Override
    public boolean confirm(String title, String message, @Nullable ExtensionUiDialogOptions opts) {
        return "Yes".equals(select(title + "\n" + message, List.of("Yes", "No"), opts));
    }

    @Override
    public @Nullable String input(String title, @Nullable String placeholder,
                                  @Nullable ExtensionUiDialogOptions opts) {
        guardBlocking();
        AbortSignal signal = opts != null ? opts.signal() : null;
        if (signal != null && signal.isAborted()) {
            return null;
        }
        var result = new CompletableFuture<String>();
        var overlay = new ExtensionInputOverlay(title, placeholder, host.keybindings(),
            host.terminalCols(), result);
        return runDialog(overlay, overlay, result, signal,
            opts != null ? opts.timeoutMillis() : null);
    }

    @Override
    public @Nullable String editor(String title, @Nullable String prefill) {
        guardBlocking();
        var result = new CompletableFuture<String>();
        // pi extension-editor.ts:55-59 回落链（coding-agent 侧单测面）。
        var command = ExternalEditorSupport.resolveCommand(
            host.externalEditorCommand().get(), System::getenv,
            com.pijava.tui.util.TamboUIAdapter.isWindows());
        var overlay = new ExtensionEditorOverlay(title, prefill, host.keybindings(),
            host.terminalCols(), command, host.externalEditorRoundtrip(), result);
        return runDialog(overlay, overlay, result, null, null);
    }

    // ── custom 挂载面（pi showExtensionCustom :2966-3043 对位）──

    @Override
    @SuppressWarnings("unchecked") // done 闭包的 T⇒Object 装箱（结局 future 擦除存储）
    public <T> @Nullable T custom(ExtensionComponentFactory<T> factory,
                                  @Nullable ExtensionCustomOptions options) {
        guardBlocking();
        var opts = options != null ? options : ExtensionCustomOptions.DEFAULT;
        var result = new CompletableFuture<Object>();
        var closed = new AtomicBoolean();
        var overlayRef = new java.util.concurrent.atomic.AtomicReference<
            ExtensionComponentOverlay>();
        // pi close(result)（:2982-2992 逐字）：幂等闸 ⇒ 关屏 ⇒ resolve ⇒ dispose 包 try。
        java.util.function.Consumer<T> done = value -> {
            if (!closed.compareAndSet(false, true)) {
                return;     // pi closed 闸（幂等）
            }
            var overlay = overlayRef.get();
            if (overlay != null) {
                overlay.markDone();
                host.dismissOverlay(overlay);
            }
            result.complete(value);
            if (overlay != null) {
                safeDispose(overlay.component());     // pi：ignore dispose errors
            }
        };
        // 工厂抛 ⇒ 上抛（pi 的 reject 对位——java 阻塞语义下即抛回调用方）；
        // pi 非 overlay 臂的编辑器保存/恢复不需要（java 编辑器从未离屏，结构差异注记）。
        ExtensionComponent component;
        try {
            component = factory.create(hostEnv, host.keybindings(), done);
        } catch (RuntimeException e) {
            throw e;
        }
        if (component == null) {
            // 工厂未产组件且未 done ⇒ 视同取消（防御；pi 会把 undefined 当组件挂崩）。
            result.complete(null);
            return null;
        }
        var overlay = new ExtensionComponentOverlay(component, host.terminalCols(), result);
        overlayRef.set(overlay);
        if (closed.get()) {
            // pi：resolve 前已 closed ⇒ 组件直接丢弃（:3003 臂）。
            safeDispose(component);
            return (T) result.getNow(null);
        }
        if (opts.onHandle() != null) {
            opts.onHandle().accept(new OverlayHandleNarrowed(overlay));
        }
        // 批 17 评审回补（Minor 1）：结局后清账——活动对话表不滞留死条目。
        var active = new ActiveDialog(overlay, overlay, result);
        activeDialogs.add(active);
        host.openOverlay(overlay);
        try {
            return (T) awaitResult(result);
        } finally {
            activeDialogs.remove(active);
        }
    }

    // ── 非阻塞注入面（窄差 D96：dispatcher 入队、渲染线程生效）──

    /** pi showExtensionNotify（:2954-2962 逐字三分支）。 */
    @Override
    public void notify(String message, @Nullable NotifyType type) {
        host.dispatcher().dispatch(() -> {
            if (type == NotifyType.ERROR) {
                host.chatScreen().showError(message);
            } else if (type == NotifyType.WARNING) {
                host.chatScreen().showWarning(message);
            } else {
                host.chatScreen().appendSystemText(message);    // pi showStatus 对位
            }
        });
    }

    @Override
    public void setStatus(String key, @Nullable String text) {
        host.dispatcher().dispatch(() -> host.chatScreen().setExtensionStatus(key, text));
    }

    @Override
    public void setWidget(String key, @Nullable ExtensionWidgetContent content,
                          @Nullable ExtensionWidgetOptions options) {
        host.dispatcher().dispatch(() ->
            host.chatScreen().setExtensionWidget(key, content, options, hostEnv));
    }

    @Override
    public void setFooter(@Nullable ExtensionFooterFactory factory) {
        host.dispatcher().dispatch(() -> host.chatScreen().setExtensionFooter(factory, hostEnv));
    }

    @Override
    public void setHeader(@Nullable ExtensionSlotFactory factory) {
        host.dispatcher().dispatch(() -> host.chatScreen().setExtensionHeader(factory, hostEnv));
    }

    /** pi getEditorText（TUI 臂）：读主编辑器当前文本。 */
    @Override
    public String getEditorText() {
        return host.chatScreen().inputText();
    }

    /** pi setEditorText（TUI 臂）：替换主编辑器文本。 */
    @Override
    public void setEditorText(String text) {
        host.dispatcher().dispatch(() -> host.chatScreen().setEditorText(text));
    }

    /**
     * pi setTitle：TamboUI Terminal 无窗口标题 API（docs/28 §2）——空操作（记注不登窄差：
     * pi 的能力面在 java 终端栈缺席，docs/28 §4 未列 ⇒ 归 E5 余面挂账）。
     */

    // ── 生命周期（T7 接线：onSessionInvalidated ⇒ resetAll）──

    /**
     * pi {@code resetExtensionUI}（interactive-mode.ts:2452-2483）本批相关段逐字：
     * 关活动对话框（resolve 取消值）→ 清 widget 两区 → footer/header 回内置 → 清扩展
     * 状态段。java 无 terminal input listeners/autocomplete/custom editor/working
     * message 面（随后续批，docs/28 §4）。
     */
    @Override
    public void onSessionInvalidated() {
        host.dispatcher().dispatch(() -> {
            for (var dialog : activeDialogs) {
                dialog.dialog().cancel();
                host.dismissOverlay(dialog.overlay());
                dialog.result().complete(null);     // 取消值兜底（complete 幂等）
            }
            activeDialogs.clear();
            host.chatScreen().resetExtensionUi();
        });
    }

    /** 进程退出清理（倒计时调度器；幂等）。 */
    public void close() {
        host.countdownScheduler().shutdownNow();
    }

    // ── 内部：对话生命周期 ─────────────────────────────────────

    /**
     * 对话公共骨架（pi showExtensionSelector :2690-2743 序对位）：abort 监听
     * （pi {@code signal.addEventListener("abort", …, {once:true})}）⇒ 取消；
     * timeout&gt;0 ⇒ 倒计时（pi CountdownTimer 逐字）；开 overlay；阻塞等结局。
     */
    @SuppressWarnings("unchecked")
    private <T> T runDialog(ExtensionDialog dialog, ScreenOverlay overlay,
                            CompletableFuture<T> result, @Nullable AbortSignal signal,
                            @Nullable Long timeoutMillis) {
        var active = new ActiveDialog(dialog, overlay, (CompletableFuture<Object>) result);
        activeDialogs.add(active);
        Runnable unsubscribeAbort = () -> { };
        if (signal != null) {
            unsubscribeAbort = signal.onAbort(() -> cancelDialog(active));
        }
        ScheduledFuture<?> countdown = null;
        if (timeoutMillis != null && timeoutMillis > 0) {
            countdown = startCountdown(dialog, timeoutMillis);
        }
        try {
            host.openOverlay(overlay);
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } finally {
            unsubscribeAbort.run();
            if (countdown != null) {
                countdown.cancel(false);
            }
            activeDialogs.remove(active);
        }
    }

    /** 活动对话数（评审回补 Minor 1 的测试探针）。 */
    int activeDialogCount() {
        return activeDialogs.size();
    }

    /** abort/到期/复位的公共取消：取消 ⇒ 渲染线程摘 overlay（apply 完成结局）⇒ 兜底完成。 */
    private void cancelDialog(ActiveDialog active) {
        host.dispatcher().dispatch(() -> {
            active.dialog().cancel();
            host.dismissOverlay(active.overlay());
            active.result().complete(null);
        });
    }

    /**
     * pi CountdownTimer（countdown-timer.ts 逐字）：{@code ceil(timeout/1000)} 起、
     * 立即 tick 一次、每 1s 减一 tick＋标脏、&le;0 ⇒ 到期＝取消。
     */
    private ScheduledFuture<?> startCountdown(ExtensionDialog dialog, long timeoutMillis) {
        var remaining = new AtomicInteger((int) Math.ceil(timeoutMillis / 1000.0));
        dialog.countdownTick(remaining.get());
        host.requestRender();
        var expired = new AtomicBoolean();
        return host.countdownScheduler().scheduleAtFixedRate(() -> {
            int seconds = remaining.decrementAndGet();
            dialog.countdownTick(seconds);
            host.requestRender();
            if (seconds <= 0 && expired.compareAndSet(false, true)) {
                dialog.countdownExpired();
                // 到期＝取消 ⇒ 渲染线程摘 overlay（复用公共路径）。
                for (var active : activeDialogs) {
                    if (active.dialog() == dialog) {
                        host.dismissOverlay(active.overlay());
                        active.result().complete(null);
                        break;
                    }
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /** 阻塞等结局（custom 臂——无 abort/timeout 的简化等待）。 */
    private static Object awaitResult(CompletableFuture<Object> result) {
        try {
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

    private static void safeDispose(ExtensionComponent component) {
        try {
            component.dispose();
        } catch (Exception ignored) {
            // pi：ignore dispose errors
        }
    }

    /** pi {@code OverlayHandle} 的窄化三件（docs/28 §4/窄差 D99）。 */
    private final class OverlayHandleNarrowed implements ExtensionOverlayHandle {
        private final ExtensionComponentOverlay overlay;

        OverlayHandleNarrowed(ExtensionComponentOverlay overlay) {
            this.overlay = overlay;
        }

        @Override
        public void hide() {
            host.dismissOverlay(overlay);   // 摘屏；结局仍由 done 闭包决定（pi 同构）
        }

        @Override
        public void setHidden(boolean hidden) {
            host.dispatcher().dispatch(() -> overlay.setHidden(hidden));
        }

        @Override
        public boolean isHidden() {
            return overlay.isHidden();
        }
    }

    private void guardBlocking() {
        if (!host.renderLoopActive()) {
            // 裁决⑤：session_start 期（渲染循环未起步）阻塞对话不可用——明确失败。
            throw new IllegalStateException(
                "Extension UI dialogs require a running render loop");
        }
        if (host.onRenderThread()) {
            // 窄差 D98：渲染线程自调用会死锁（pi 单线程 Promise 无此形态）。
            throw new IllegalStateException(
                "Blocking extension UI dialog called on the render thread");
        }
    }
}
