package com.pijava.tui.app;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionCustomOptions;
import com.pijava.coding.agent.extension.ExtensionOverlayHandle;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.ExtensionComponentOverlay;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.util.TuiEventDispatcher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 17（T5，docs/28 §7-9）：{@code ctx.ui.custom} 挂载面——pi
 * showExtensionCustom（interactive-mode.ts:2966-3043）对拍：done 幂等闸、
 * dispose 吞异常（pi「ignore dispose errors」逐字）、工厂抛上抛（reject 对位）、
 * overlay:true 同路径＋handle 窄化三件（hide/setHidden/isHidden）。
 */
class ExtensionCustomOverlayTest {

    /** 最小组件：录按键、可抛 dispose、渲染固定行。 */
    private static final class FakeComponent implements ExtensionComponent {
        final List<String> keys = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger disposeCalls = new AtomicInteger();
        volatile boolean throwOnDispose;

        @Override
        public List<String> render(int width) {
            return List.of("[accent]custom row[/]");
        }

        @Override
        public void handleInput(KeybindingsManager.KeyStroke key) {
            keys.add(key.key());
        }

        @Override
        public void dispose() {
            disposeCalls.incrementAndGet();
            if (throwOnDispose) {
                throw new IllegalStateException("dispose boom");
            }
        }
    }

    private TuiEventDispatcher dispatcher;
    private TuiExtensionUI ui;
    private final AtomicReference<ScreenOverlay> opened = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        dispatcher = new TuiEventDispatcher();
        var host = new TuiExtensionUiHost(dispatcher, new ChatScreen(),
            new KeybindingsManager(), () -> null);
        host.bindApp(opened::set, overlay -> overlay.apply(null, s -> { }),
            (command, content) -> Optional.empty(), () -> 80, () -> 24);
        host.markRenderLoopActive(new Thread("fake-render"));
        ui = new TuiExtensionUI(host);
    }

    private <T> CompletableFuture<T> callCustom(
            com.pijava.coding.agent.extension.ExtensionComponentFactory<T> factory,
            ExtensionCustomOptions options) {
        var future = new CompletableFuture<T>();
        Thread.startVirtualThread(() -> {
            try {
                future.complete(ui.custom(factory, options));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private ScreenOverlay awaitOverlay() throws InterruptedException {
        for (int i = 0; i < 200 && opened.get() == null; i++) {
            dispatcher.drain();
            Thread.sleep(5);
        }
        dispatcher.drain();
        return opened.get();
    }

    @Test
    void doneDeliversResultAndDisposesComponent() throws Exception {
        var component = new FakeComponent();
        var call = callCustom((env, keys, done) -> {
            // 模拟用户操作：组件内部决定结束（pi 的 done 是唯一正常出口）。
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) { }
                done.accept("verdict");
            });
            return component;
        }, null);
        var overlay = awaitOverlay();
        assertThat(overlay).isInstanceOf(ExtensionComponentOverlay.class);
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("verdict");
        for (int i = 0; i < 100 && component.disposeCalls.get() == 0; i++) {
            dispatcher.drain();
            Thread.sleep(5);
        }
        assertThat(component.disposeCalls.get()).isEqualTo(1);
    }

    @Test
    void doneIsIdempotent() throws Exception {
        var call = callCustom((env, keys, done) -> {
            done.accept("first");
            done.accept("second");   // pi closed 闸：二次调用零效果
            return new FakeComponent();
        }, null);
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("first");
    }

    @Test
    void disposeErrorsAreSwallowed() throws Exception {
        var component = new FakeComponent();
        component.throwOnDispose = true;
        var call = callCustom((env, keys, done) -> {
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) { }
                done.accept("ok");
            });
            return component;
        }, null);
        awaitOverlay();
        // pi 逐字「ignore dispose errors」——结局照常回送。
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
    }

    @Test
    void factoryThrowPropagatesToCaller() {
        assertThatThrownBy(() -> ui.<Object>custom((env, keys, done) -> {
            throw new IllegalStateException("factory boom");
        }, null)).isInstanceOf(IllegalStateException.class).hasMessage("factory boom");
        assertThat(opened.get()).isNull();   // pi：factory 拒 ⇒ 不挂屏
    }

    @Test
    void keyEventsReachComponentAsNeutralStrokes() throws Exception {
        var component = new FakeComponent();
        var call = callCustom((env, keys, done) -> component, null);
        var overlay = (ExtensionComponentOverlay) awaitOverlay();
        overlay.onKeyEvent(dev.tamboui.tui.event.KeyEvent.ofChar('x'));
        assertThat(component.keys).containsExactly("x");
        // 收尾（取消路径＝cancel + 结局 future 补 null——宿主 cancelDialog 的对位）。
        overlay.cancel();
        overlay.resultFuture().complete(null);
        dispatcher.drain();
        overlay.apply(null, s -> { });
        call.get(5, TimeUnit.SECONDS);
    }

    @Test
    void overlayOptionUsesSamePathWithNarrowedHandle() throws Exception {
        var handleRef = new AtomicReference<ExtensionOverlayHandle>();
        var component = new FakeComponent();
        var call = callCustom((env, keys, done) -> {
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) { }
                done.accept("done-via-overlay");
            });
            return component;
        }, new ExtensionCustomOptions(true, handleRef::set));
        var overlay = (ExtensionComponentOverlay) awaitOverlay();

        // 窄化三件（D99）：setHidden/isHidden 现读，hide 摘屏。
        var handle = handleRef.get();
        assertThat(handle).isNotNull();
        assertThat(handle.isHidden()).isFalse();
        handle.setHidden(true);
        dispatcher.drain();
        assertThat(handle.isHidden()).isTrue();
        assertThat(overlay.render().toString()).isNotNull();   // 摘态渲染零行不崩

        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("done-via-overlay");
    }

    /** 批 17 评审回补（Minor 1）：custom 完成后活动对话表清账（不再滞留死条目）。 */
    @Test
    void completedCustomLeavesNoActiveDialog() throws Exception {
        var call = callCustom((env, keys, done) -> {
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) { }
                done.accept("ok");
            });
            return new FakeComponent();
        }, null);
        awaitOverlay();
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
        for (int i = 0; i < 100; i++) {
            dispatcher.drain();
            if (ui.activeDialogCount() == 0) {
                break;
            }
            Thread.sleep(5);
        }
        assertThat(ui.activeDialogCount()).isEqualTo(0);
        // 复位不再触碰已完成对话（幂等、无残留）。
        ui.onSessionInvalidated();
        dispatcher.drain();
        assertThat(ui.activeDialogCount()).isEqualTo(0);
    }

    @Test
    void doneBeforeMountDiscardsComponent() throws Exception {
        // pi :3003 臂：resolve 前已 closed ⇒ 组件直接丢弃（不开屏）。
        var component = new FakeComponent();
        var call = callCustom((env, keys, done) -> {
            done.accept("early");
            return component;
        }, null);
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("early");
        dispatcher.drain();
        assertThat(opened.get()).isNull();
        assertThat(component.disposeCalls.get()).isEqualTo(1);
    }
}
