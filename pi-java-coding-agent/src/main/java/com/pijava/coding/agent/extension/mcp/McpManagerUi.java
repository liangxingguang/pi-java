package com.pijava.coding.agent.extension.mcp;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;

/**
 * 批 24（docs/35 T10）：/mcp 管理器视图的 UI 面（pi {@code McpUi}/{@code McpMenu}，
 * {@code ui.ts:24-47} 逐字对位）。
 */
interface McpManagerUi {

    /**
     * 显示一个菜单，选定即 resolve（取消 ⇒ null）。{@code subscribe} 非空时
     * 每次变化重建菜单并保持选中项（pi 的 {@code subscribe} 参数对位：
     * 入 listener，出退订闭包）。
     */
    CompletableFuture<String> menu(Supplier<McpMenu> build,
                                   @Nullable Function<Runnable, Runnable> subscribe);

    /** 操作进行中显示消息；{@code onCancel} 非空时取消键触发它。 */
    void status(String title, String message, @Nullable Runnable onCancel);

    /**
     * 显示授权 URL 并等粘贴的 redirect URL；取消或 {@code signal} abort
     * （浏览器抵达回调）⇒ resolve null。
     */
    CompletableFuture<String> redirectUrl(String title, String authorizationUrl,
                                          AbortSignal signal);

    /** 一个菜单（pi {@code McpMenu}）。 */
    record McpMenu(
            String title,
            @Nullable String details,
            @Nullable String error,
            List<McpMenuItem> items,
            @Nullable String empty,
            @Nullable String selected,
            String confirmLabel,
            String cancelLabel) {
    }

    /** 一个菜单项（pi {@code SelectItem} 的文本级投影）。 */
    record McpMenuItem(String value, String label, @Nullable String description) {
    }
}
