package com.pijava.coding.agent.extension.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.mcp.McpManagerUi.McpMenu;
import com.pijava.coding.agent.extension.mcp.McpManagerUi.McpMenuItem;

/**
 * 批 24（docs/35 T10，裁决⑨）：/mcp 管理器视图（pi {@code McpManagerView}，
 * {@code ui.ts:73-244}）——文本级 {@link ExtensionComponent} 实现（coding-agent
 * 不依赖 TamboUI，批 16 裁决⑦/批 17 裁决③先例）。
 *
 * <p>pi 的 SelectList 文本级复刻：选中前缀 {@code "→ "}（选中行整行 accent，
 * 非选中描述 muted——getSelectListTheme 对位）、{@code MAX_VISIBLE_ITEMS = 12}
 * 滚动窗＋{@code "  (n/total)"} 滚动信息行、订阅重建保持选中值。
 * AuthUrlComponent 无对位 ⇒ redirect 屏为文本行＋简易输入行（窄差 D-new2）。</p>
 */
final class McpManagerView implements ExtensionComponent, McpManagerUi {

    /** pi {@code ui.ts:51}。 */
    private static final int MAX_VISIBLE_ITEMS = 12;

    private final ExtensionUiHostEnv env;
    private final KeybindingsManager keybindings;
    private final Runnable onClose;

    /** 当前屏（菜单/状态/redirect 三选一）。 */
    private @Nullable Screen screen;

    McpManagerView(ExtensionUiHostEnv env, KeybindingsManager keybindings, Runnable onClose) {
        this.env = env;
        this.keybindings = keybindings;
        this.onClose = onClose;
    }

    /** 管理循环结束 ⇒ 关闭（pi 的 {@code done()}）。 */
    void finish() {
        onClose.run();
    }

    // ── McpManagerUi ──

    @Override
    public CompletableFuture<String> menu(Supplier<McpMenu> build,
                                          @Nullable Function<Runnable, Runnable> subscribe) {
        var future = new CompletableFuture<String>();
        var menuScreen = new MenuScreen(build, future);
        screen = menuScreen;
        if (subscribe != null) {
            menuScreen.unsubscribe = subscribe.apply(() -> {
                if (!future.isDone()) {
                    env.requestRender().run();
                }
            });
        }
        env.requestRender().run();
        return future;
    }

    @Override
    public void status(String title, String message, @Nullable Runnable onCancel) {
        screen = new StatusScreen(title, message, onCancel);
        env.requestRender().run();
    }

    @Override
    public CompletableFuture<String> redirectUrl(String title, String authorizationUrl,
                                                 AbortSignal signal) {
        var future = new CompletableFuture<String>();
        var redirectScreen = new RedirectScreen(title, authorizationUrl, future);
        screen = redirectScreen;
        redirectScreen.unsubscribeAbort = signal.onAbort(() -> future.complete(null));
        env.requestRender().run();
        return future;
    }

    // ── ExtensionComponent ──

    @Override
    public List<String> render(int width) {
        var current = screen;
        if (current == null) {
            return frame("MCP servers", List.of(colorize("muted", " Loading…")), null, width);
        }
        return current.render(width);
    }

    @Override
    public void handleInput(KeybindingsManager.KeyStroke key) {
        var current = screen;
        if (current != null) {
            current.handleInput(key);
        }
    }

    @Override
    public void dispose() {
        if (screen instanceof MenuScreen menuScreen && menuScreen.unsubscribe != null) {
            menuScreen.unsubscribe.run();
        }
        if (screen instanceof RedirectScreen redirectScreen
            && redirectScreen.unsubscribeAbort != null) {
            redirectScreen.unsubscribeAbort.run();
        }
    }

    // ── 屏族 ──

    /** pi {@code frame}（ui.ts:53-64）：DynamicBorder 上下框＋标题＋体＋脚注。 */
    private List<String> frame(String title, List<String> body, @Nullable String footer,
                               int width) {
        var lines = new ArrayList<String>();
        var border = colorize("accent", "─".repeat(Math.max(1, width)));
        lines.add(border);
        lines.add(" " + colorize("accent", title));
        lines.addAll(body);
        if (footer != null) {
            lines.add("");
            lines.add(" " + colorize("dim", footer));
        }
        lines.add(border);
        return lines;
    }

    private abstract sealed class Screen {
        abstract List<String> render(int width);

        abstract void handleInput(KeybindingsManager.KeyStroke key);
    }

    /** 菜单屏（pi {@code McpManagerView.menu} :103-151 对位）。 */
    private final class MenuScreen extends Screen {
        private final Supplier<McpMenu> build;
        private final CompletableFuture<String> future;
        private @Nullable String selected;
        private @Nullable Runnable unsubscribe;

        MenuScreen(Supplier<McpMenu> build, CompletableFuture<String> future) {
            this.build = build;
            this.future = future;
        }

        private void finish(@Nullable String value) {
            if (future.isDone()) {
                return;
            }
            if (unsubscribe != null) {
                unsubscribe.run();
                unsubscribe = null;
            }
            future.complete(value);
        }

        @Override
        List<String> render(int width) {
            var menu = build.get();
            var body = new ArrayList<String>();
            if (menu.details() != null) {
                for (var line : menu.details().split("\n")) {
                    body.add(" " + colorize("muted", line));
                }
            }
            if (menu.error() != null) {
                for (var line : menu.error().split("\n")) {
                    body.add(" " + colorize("error", line));
                }
            }
            body.add("");
            if (menu.items().isEmpty()) {
                body.add(" " + colorize("muted",
                    menu.empty() != null ? menu.empty() : "Nothing to show."));
                return frame(menu.title(), body, "esc " + menu.cancelLabel(), width);
            }
            // 选中值保持（pi selected ?? menu.selected）
            var wanted = selected != null ? selected : menu.selected();
            var index = 0;
            if (wanted != null) {
                for (var i = 0; i < menu.items().size(); i++) {
                    if (menu.items().get(i).value().equals(wanted)) {
                        index = i;
                        break;
                    }
                }
            }
            selected = menu.items().get(index).value();
            var window = visibleRange(menu.items().size(), index);
            for (var i = window[0]; i < window[1]; i++) {
                body.add(renderItem(menu.items().get(i), i == index, width));
            }
            if (window[0] > 0 || window[1] < menu.items().size()) {
                body.add(colorize("muted", "  (" + (index + 1) + "/" + menu.items().size() + ")"));
            }
            return frame(menu.title(), body,
                "enter " + menu.confirmLabel() + " • esc " + menu.cancelLabel(), width);
        }

        private String renderItem(McpMenuItem item, boolean isSelected, int width) {
            var prefix = isSelected ? "→ " : "  ";
            var line = prefix + item.label();
            var description = item.description() != null
                ? item.description().replaceAll("[\\r\\n]+", " ").trim() : "";
            if (!description.isEmpty() && width > 40) {
                var remaining = width - line.length() - 2;
                if (remaining > 10) {
                    var truncated = description.length() <= remaining
                        ? description : description.substring(0, remaining);
                    if (isSelected) {
                        return colorize("accent", line + "  " + truncated);
                    }
                    return line + colorize("muted", "  " + truncated);
                }
            }
            return isSelected ? colorize("accent", line) : line;
        }

        /** 含选中项的最小滚动窗（pi getVisibleRange 净效果）。 */
        private int[] visibleRange(int size, int index) {
            var start = Math.max(0, Math.min(index, size - MAX_VISIBLE_ITEMS));
            // 选中项贴近窗口上沿时尽量向下滚动（pi 行为：保持选中可见即可）
            if (index >= start + MAX_VISIBLE_ITEMS) {
                start = index - MAX_VISIBLE_ITEMS + 1;
            }
            return new int[] {start, Math.min(size, start + MAX_VISIBLE_ITEMS)};
        }

        @Override
        void handleInput(KeybindingsManager.KeyStroke key) {
            var menu = build.get();
            if (menu.items().isEmpty()) {
                if (keybindings.matches(KeybindingsManager.SELECT_CANCEL, key)) {
                    finish(null);
                }
                return;
            }
            var index = indexOf(menu);
            if (keybindings.matches(KeybindingsManager.SELECT_UP, key)) {
                selected = menu.items().get(Math.max(0, index - 1)).value();
            } else if (keybindings.matches(KeybindingsManager.SELECT_DOWN, key)) {
                selected = menu.items().get(Math.min(menu.items().size() - 1, index + 1)).value();
            } else if (keybindings.matches(KeybindingsManager.SELECT_CONFIRM, key)) {
                finish(selected);
            } else if (keybindings.matches(KeybindingsManager.SELECT_CANCEL, key)) {
                finish(null);
            }
        }

        private int indexOf(McpMenu menu) {
            for (var i = 0; i < menu.items().size(); i++) {
                if (menu.items().get(i).value().equals(selected)) {
                    return i;
                }
            }
            return 0;
        }
    }

    /** 状态屏（pi {@code status} :153-163 对位）。 */
    private final class StatusScreen extends Screen {
        private final String title;
        private final String message;
        private final @Nullable Runnable onCancel;

        StatusScreen(String title, String message, @Nullable Runnable onCancel) {
            this.title = title;
            this.message = message;
            this.onCancel = onCancel;
        }

        @Override
        List<String> render(int width) {
            var body = new ArrayList<String>();
            body.add("");
            body.add(" " + colorize("muted", message));
            return frame(title, body, onCancel != null ? "esc cancel" : null, width);
        }

        @Override
        void handleInput(KeybindingsManager.KeyStroke key) {
            if (onCancel != null && keybindings.matches(KeybindingsManager.SELECT_CANCEL, key)) {
                onCancel.run();
            }
        }
    }

    /** redirect URL 粘贴屏（pi {@code redirectUrl} :165-224 对位，文本级收窄）。 */
    private final class RedirectScreen extends Screen {
        private final String title;
        private final String authorizationUrl;
        private final CompletableFuture<String> future;
        private final StringBuilder input = new StringBuilder();
        private @Nullable Runnable unsubscribeAbort;

        RedirectScreen(String title, String authorizationUrl, CompletableFuture<String> future) {
            this.title = title;
            this.authorizationUrl = authorizationUrl;
            this.future = future;
        }

        private void finish(@Nullable String value) {
            if (future.isDone()) {
                return;
            }
            if (unsubscribeAbort != null) {
                unsubscribeAbort.run();
                unsubscribeAbort = null;
            }
            future.complete(value);
        }

        @Override
        List<String> render(int width) {
            var body = new ArrayList<String>();
            body.add("");
            body.add(" " + colorize("muted",
                "Approve access in your browser. If it did not open, visit:"));
            body.add(" " + authorizationUrl);
            body.add("");
            body.add(" " + colorize("muted",
                "If the browser runs on another machine, paste the URL it was redirected to:"));
            body.add(" " + input + "█");
            return frame(title, body, "enter submit • esc cancel", width);
        }

        @Override
        void handleInput(KeybindingsManager.KeyStroke key) {
            if (keybindings.matches(KeybindingsManager.SELECT_CONFIRM, key)) {
                var value = input.toString().trim();
                if (!value.isEmpty()) {
                    finish(value);
                }
                return;
            }
            if (keybindings.matches(KeybindingsManager.SELECT_CANCEL, key)) {
                finish(null);
                return;
            }
            if ("backspace".equals(key.key())) {
                if (!input.isEmpty()) {
                    input.setLength(input.length() - 1);
                }
                return;
            }
            // 文本级输入行：可打印字符累积（窄差 D-new2——无 pi Input 组件对位）
            if (!key.ctrl() && !key.alt() && key.key().length() == 1) {
                var c = key.key().charAt(0);
                input.append(key.shift() && Character.isLetter(c)
                    ? Character.toUpperCase(c) : c);
            } else if ("space".equals(key.key())) {
                input.append(' ');
            }
        }
    }

    private String colorize(String slot, String text) {
        return env.colorize().apply(slot, text);
    }
}
