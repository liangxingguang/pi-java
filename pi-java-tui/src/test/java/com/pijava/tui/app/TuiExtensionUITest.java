package com.pijava.tui.app;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.ai.AbortSignal;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.extension.ExtensionUiDialogOptions;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.ExtensionInputOverlay;
import com.pijava.tui.screen.ExtensionSelectorOverlay;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.util.TuiEventDispatcher;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 17（T4，docs/28 §7-8）：{@link TuiExtensionUI} 四阻塞方法经假渲染线程驱动
 * （dispatch 直执夹具）出值/取消/abort 三结局；渲染线程自调用守卫；confirm 的
 * Yes/No 映射逐字（pi :2749-2758）。
 *
 * <p>夹具：host 的 opener/dismisser 直接落 {@link #opened} 槽＋直调 apply
 * （≙ PiTuiApp 的帧级收口）；渲染线程标记用一根从未启动的 Thread——当前测试
 * 线程与它恒不等 ⇒ 护栏只验「≠渲染线程」那一半。</p>
 */
class TuiExtensionUITest {

    private TuiEventDispatcher dispatcher;
    private ChatScreen chatScreen;
    private TuiExtensionUiHost host;
    private TuiExtensionUI ui;
    private final AtomicReference<ScreenOverlay> opened = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        dispatcher = new TuiEventDispatcher();
        chatScreen = new ChatScreen();
        host = new TuiExtensionUiHost(dispatcher, chatScreen,
            new KeybindingsManager(), () -> null);
        host.bindApp(opened::set,
            overlay -> overlay.apply(null, s -> { }),     // dismisser＝直调 apply
            (command, content) -> Optional.of("rt:" + content),
            () -> 80, () -> 24);
        host.markRenderLoopActive(new Thread("fake-render"));
        ui = new TuiExtensionUI(host);
    }

    /** 在非渲染线程上跑阻塞调用；回 caller 线程句柄。 */
    private <T> java.util.concurrent.CompletableFuture<T> callOnExtensionThread(
            java.util.function.Supplier<T> call) {
        var future = new CompletableFuture<T>();
        Thread.startVirtualThread(() -> {
            try {
                future.complete(call.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** 等到 overlay 开屏（drain 直执 opener）。 */
    private ScreenOverlay awaitOverlay() throws InterruptedException {
        for (int i = 0; i < 200 && opened.get() == null; i++) {
            dispatcher.drain();
            Thread.sleep(5);
        }
        dispatcher.drain();
        var overlay = opened.get();
        assertThat(overlay).as("opener 入队后 overlay 落槽").isNotNull();
        return overlay;
    }

    /** 模拟应用壳的关屏（帧级收口对位）。 */
    private void closeIfDone(ScreenOverlay overlay) {
        if (overlay.isDone()) {
            opened.set(null);
            overlay.apply(null, s -> { });
        }
    }

    // ── 三结局 ──

    @Test
    void selectResolvesChosenOption() throws Exception {
        var call = callOnExtensionThread(() -> ui.select("Pick", List.of("x", "y"), null));
        var overlay = (ExtensionSelectorOverlay) awaitOverlay();
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.DOWN));
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        closeIfDone(overlay);
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("y");
    }

    @Test
    void selectEscapeResolvesNull() throws Exception {
        var call = callOnExtensionThread(() -> ui.select("Pick", List.of("x"), null));
        var overlay = (ExtensionSelectorOverlay) awaitOverlay();
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));
        closeIfDone(overlay);
        assertThat(call.get(5, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void selectAbortSignalResolvesNull() throws Exception {
        var signal = AbortSignal.create();
        var call = callOnExtensionThread(() -> ui.select("Pick", List.of("x"),
            new ExtensionUiDialogOptions(signal, null)));
        var overlay = awaitOverlay();
        // pi onAbort：隐藏 overlay＋resolve(undefined)（渲染线程收口）。
        signal.abort();
        for (int i = 0; i < 100 && !call.isDone(); i++) {
            dispatcher.drain();
            Thread.sleep(5);
        }
        assertThat(call.get(5, TimeUnit.SECONDS)).isNull();
        assertThat(overlay.isDone()).isTrue();
    }

    @Test
    void preAbortedSignalNeverOpensOverlay() {
        var signal = AbortSignal.create();
        signal.abort();
        assertThat(ui.select("t", List.of("a"), new ExtensionUiDialogOptions(signal, null)))
            .isNull();
        dispatcher.drain();
        assertThat(opened.get()).isNull();
    }

    @Test
    void timeoutExpiresToCancel() throws Exception {
        // 真调度器 1Hz（pi CountdownTimer 逐字周期）：timeout=1ms ⇒ ceil=1s 到期。
        var call = callOnExtensionThread(() -> ui.input("Name", null,
            new ExtensionUiDialogOptions(null, 1L)));
        awaitOverlay();
        for (int i = 0; i < 400 && !call.isDone(); i++) {
            dispatcher.drain();
            Thread.sleep(10);
        }
        assertThat(call.get(5, TimeUnit.SECONDS)).isNull();
    }

    // ── confirm 的 selector 特化（pi :2749-2758 逐字）──

    @Test
    void confirmMapsYesToTrueNoToFalse() throws Exception {
        var call = callOnExtensionThread(() -> ui.confirm("Sure?", "do it", null));
        var overlay = (ExtensionSelectorOverlay) awaitOverlay();
        // 标题＝title\nmessage（pi :2750 逐字）——渲染含两行。
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));   // 首项 Yes
        closeIfDone(overlay);
        assertThat(call.get(5, TimeUnit.SECONDS)).isTrue();

        call = callOnExtensionThread(() -> ui.confirm("Sure?", "do it", null));
        overlay = (ExtensionSelectorOverlay) awaitOverlay();
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.DOWN));    // No
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        closeIfDone(overlay);
        assertThat(call.get(5, TimeUnit.SECONDS)).isFalse();
    }

    // ── 护栏（裁决⑤／窄差 D98）──

    @Test
    void blockingCallBeforeRenderLoopFailsFast() {
        var freshHost = new TuiExtensionUiHost(new TuiEventDispatcher(), new ChatScreen(),
            new KeybindingsManager(), () -> null);
        var freshUi = new TuiExtensionUI(freshHost);
        assertThatThrownBy(() -> freshUi.select("t", List.of("a"), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("render loop");
    }

    @Test
    void blockingCallOnRenderThreadFailsFast() {
        var selfHost = new TuiExtensionUiHost(new TuiEventDispatcher(), new ChatScreen(),
            new KeybindingsManager(), () -> null);
        selfHost.markRenderLoopActive(Thread.currentThread());   // 当前线程＝渲染线程
        var selfUi = new TuiExtensionUI(selfHost);
        assertThatThrownBy(() -> selfUi.select("t", List.of("a"), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("render thread");
    }

    // ── notify 三分支（pi :2954-2962 逐字）──

    @Test
    void notifyMapsToThreeChannels() {
        ui.notify("oops", NotifyType.ERROR);
        ui.notify("careful", NotifyType.WARNING);
        ui.notify("fyi", NotifyType.INFO);
        ui.notify("plain", null);
        dispatcher.drain();
        var messages = chatScreen.messages();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isInstanceOf(ChatMessage.Error.class);
        assertThat(((ChatMessage.Error) messages.get(0)).message()).isEqualTo("Error: oops");
        assertThat(((ChatMessage.System) messages.get(1)).text()).isEqualTo("Warning: careful");
        assertThat(((ChatMessage.System) messages.get(2)).text()).isEqualTo("fyi");
        assertThat(((ChatMessage.System) messages.get(3)).text()).isEqualTo("plain");
    }

    // ── 编辑器面 ──

    @Test
    void editorTextRoundTripsThroughChatScreen() {
        ui.setEditorText("draft text");
        dispatcher.drain();
        assertThat(ui.getEditorText()).isEqualTo("draft text");
        assertThat(chatScreen.inputText()).isEqualTo("draft text");
    }

    @Test
    void inputDialogSubmitsTypedValue() throws Exception {
        var call = callOnExtensionThread(() -> ui.input("Name", "hint", null));
        var overlay = (ExtensionInputOverlay) awaitOverlay();
        overlay.onKeyEvent(KeyEvent.ofChar('o'));
        overlay.onKeyEvent(KeyEvent.ofChar('k'));
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        closeIfDone(overlay);
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
    }

    @Test
    void editorDialogRoundTripsExternalEditor() throws Exception {
        var call = callOnExtensionThread(() -> ui.editor("Compose", "seed"));
        var overlay = awaitOverlay();
        overlay.onKeyEvent(
            KeyEvent.ofChar('g', dev.tamboui.tui.event.KeyModifiers.CTRL));
        overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        closeIfDone(overlay);
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("rt:seed");
    }
}
