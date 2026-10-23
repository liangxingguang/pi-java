package com.pijava.tui.app;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionWidgetContent;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.ExtensionSelectorOverlay;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.util.TuiEventDispatcher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 17（T7，docs/28 §7-11）：{@code onSessionInvalidated} 全清——pi
 * {@code resetExtensionUI}（interactive-mode.ts:2452-2483）本批相关段逐字：
 * 活动对话框 resolve 取消值＋widget/footer/header/扩展状态段复位。
 */
class ExtensionUiResetTest {

    private TuiEventDispatcher dispatcher;
    private ChatScreen chatScreen;
    private TuiExtensionUI ui;
    private final AtomicReference<ScreenOverlay> opened = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        dispatcher = new TuiEventDispatcher();
        chatScreen = new ChatScreen();
        var host = new TuiExtensionUiHost(dispatcher, chatScreen,
            new KeybindingsManager(), () -> null);
        host.bindApp(opened::set, overlay -> overlay.apply(null, s -> { }),
            (command, content) -> Optional.empty(), () -> 80, () -> 24);
        host.markRenderLoopActive(new Thread("fake-render"));
        ui = new TuiExtensionUI(host);
    }

    @Test
    void invalidationResolvesActiveDialogWithCancelValue() throws Exception {
        var call = new CompletableFuture<String>();
        Thread.startVirtualThread(() -> {
            try {
                call.complete(ui.select("Pick", List.of("a"), null));
            } catch (Throwable t) {
                call.completeExceptionally(t);
            }
        });
        for (int i = 0; i < 200 && opened.get() == null; i++) {
            dispatcher.drain();
            Thread.sleep(5);
        }
        assertThat(opened.get()).isInstanceOf(ExtensionSelectorOverlay.class);

        ui.onSessionInvalidated();
        for (int i = 0; i < 100 && !call.isDone(); i++) {
            dispatcher.drain();
            Thread.sleep(5);
        }
        assertThat(call.get(5, TimeUnit.SECONDS)).isNull();   // 取消值
    }

    @Test
    void invalidationClearsWidgetsFooterHeaderAndStatuses() {
        ui.setWidget("w", new ExtensionWidgetContent.Lines(List.of("row")), null);
        ui.setStatus("k", "v");
        ui.setFooter((env, data) -> null);   // null 组件 ⇒ 有 custom 槽位态?——见下
        dispatcher.drain();
        assertThat(chatScreen.widgetsForProbe(true)).containsKey("w");
        assertThat(chatScreen.extensionStatusesView()).containsEntry("k", "v");

        ui.onSessionInvalidated();
        dispatcher.drain();
        assertThat(chatScreen.widgetsForProbe(true)).isEmpty();
        assertThat(chatScreen.widgetsForProbe(false)).isEmpty();
        assertThat(chatScreen.extensionStatusesView()).isEmpty();
    }

    @Test
    void invalidationClearsCustomHeaderAndFooter() {
        // 工厂产非 null 组件 ⇒ custom 槽位在册；复位 ⇒ 回内置/清槽。
        ui.setHeader(env -> new ExtensionComponent() {
            @Override public List<String> render(int width) { return List.of("h"); }
            @Override public void handleInput(KeybindingsManager.KeyStroke key) { }
        });
        ui.setFooter((env, data) -> new ExtensionComponent() {
            @Override public List<String> render(int width) { return List.of("f"); }
            @Override public void handleInput(KeybindingsManager.KeyStroke key) { }
        });
        dispatcher.drain();
        assertThat(chatScreen.hasCustomHeader()).isTrue();
        assertThat(chatScreen.hasCustomFooter()).isTrue();

        ui.onSessionInvalidated();
        dispatcher.drain();
        assertThat(chatScreen.hasCustomHeader()).isFalse();
        assertThat(chatScreen.hasCustomFooter()).isFalse();
    }
}
