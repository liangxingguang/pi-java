package com.pijava.coding.agent.extension.mcp;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

import com.pijava.ai.AbortSignal;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.mcp.McpManagerUi.McpMenu;
import com.pijava.coding.agent.extension.mcp.McpManagerUi.McpMenuItem;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 24（docs/35 §6）：管理器视图的文本级行为（pi ui.ts McpManagerView 对位）——
 * 菜单渲染/光标移动/订阅重建保持选中/状态屏/redirect 屏输入与取消。
 */
class McpManagerViewTest {

    private static final BiFunction<String, String, String> COLORIZE =
        (slot, text) -> "[" + slot + "]" + text + "[/]";

    private static McpManagerView view() {
        return new McpManagerView(
            new ExtensionUiHostEnv(() -> 80, () -> 24, () -> { }, COLORIZE),
            new KeybindingsManager(), () -> { });
    }

    private static McpMenu menu(List<McpMenuItem> items) {
        return new McpMenu("MCP servers", null, null, items, "empty…", null,
            "manage", "close");
    }

    private static KeybindingsManager.KeyStroke key(String name) {
        return new KeybindingsManager.KeyStroke(name, false, false, false);
    }

    @Test
    void menuRendersItemsWithSelectedPrefix() {
        var view = view();
        var future = view.menu(() -> menu(List.of(
            new McpMenuItem("a", "alpha", "first"),
            new McpMenuItem("b", "beta", "second"))), null);
        var lines = view.render(80);
        assertThat(lines).anyMatch(l -> l.contains("MCP servers"));
        assertThat(lines).anyMatch(l -> l.contains("→ alpha") && l.contains("first"));
        assertThat(lines).anyMatch(l -> l.contains("  beta"));
        assertThat(lines).anyMatch(l -> l.contains("enter manage • esc close"));
        view.handleInput(key("esc"));
        assertThat(future).isCompletedWithValue(null);
    }

    @Test
    void arrowKeysMoveSelectionAndEnterConfirms() {
        var view = view();
        var future = view.menu(() -> menu(List.of(
            new McpMenuItem("a", "alpha", null),
            new McpMenuItem("b", "beta", null))), null);
        view.render(80); // 落初始选中
        view.handleInput(key("down"));
        assertThat(view.render(80)).anyMatch(l -> l.contains("→ beta"));
        view.handleInput(key("enter"));
        assertThat(future).succeedsWithin(1, TimeUnit.SECONDS).isEqualTo("b");
    }

    @Test
    void subscriptionRebuildKeepsSelectedValue() {
        var view = view();
        var items = new java.util.ArrayList<>(List.of(
            new McpMenuItem("a", "alpha", null),
            new McpMenuItem("b", "beta", null)));
        var listener = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        var future = view.menu(() -> menu(items), incoming -> {
            listener.set(incoming);
            return () -> { };
        });
        view.render(80);
        view.handleInput(key("down")); // 选中 beta
        // 服务器状态变化 ⇒ 描述变、条目集同 ⇒ 重建保持选中 beta
        items.set(1, new McpMenuItem("b", "beta", "connected · 1 tool"));
        listener.get().run();
        var lines = view.render(80);
        assertThat(lines).anyMatch(l -> l.contains("→ beta") && l.contains("connected"));
        view.handleInput(key("enter"));
        assertThat(future).succeedsWithin(1, TimeUnit.SECONDS).isEqualTo("b");
    }

    @Test
    void scrollWindowShowsPositionInfo() {
        var view = view();
        var items = new java.util.ArrayList<McpMenuItem>();
        for (var i = 0; i < 15; i++) {
            items.add(new McpMenuItem("s" + i, "server-" + i, null));
        }
        view.menu(() -> menu(items), null);
        view.render(80);
        // 15 项 > 12 窗口 ⇒ 有滚动信息行
        assertThat(view.render(80)).anyMatch(l -> l.contains("(1/15)"));
    }

    @Test
    void statusScreenCancelFiresCallback() {
        var view = view();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        view.status("Sign in to docs", "Contacting the authorization server…",
            () -> cancelled.set(true));
        assertThat(view.render(80)).anyMatch(l -> l.contains("Contacting"));
        view.handleInput(key("esc"));
        assertThat(cancelled).isTrue();
    }

    @Test
    void redirectScreenCollectsPastedUrl() {
        var view = view();
        var future = view.redirectUrl("Sign in to docs", "https://as.example/authorize",
            AbortSignal.create());
        assertThat(view.render(80)).anyMatch(l -> l.contains("https://as.example/authorize"));
        for (var c : "http://127.0.0.1:9999/callback?code=abc".toCharArray()) {
            view.handleInput(new KeybindingsManager.KeyStroke(
                String.valueOf(Character.toLowerCase(c)), false, false,
                Character.isUpperCase(c)));
        }
        view.handleInput(key("enter"));
        assertThat(future).succeedsWithin(1, TimeUnit.SECONDS)
            .isEqualTo("http://127.0.0.1:9999/callback?code=abc");
    }

    @Test
    void redirectScreenAbortsWithSignal() {
        var view = view();
        var signal = AbortSignal.create();
        var future = view.redirectUrl("Sign in", "https://as.example/x", signal);
        signal.abort(); // 浏览器抵达回调 ⇒ 屏自闭（pi signal abort 对位）
        assertThat(future).succeedsWithin(1, TimeUnit.SECONDS).isNull();
    }

    @Test
    void redirectScreenEscCancels() {
        var view = view();
        var future = view.redirectUrl("Sign in", "https://as.example/x",
            AbortSignal.create());
        view.handleInput(key("esc"));
        assertThat(future).succeedsWithin(1, TimeUnit.SECONDS).isNull();
    }
}
