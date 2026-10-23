package com.pijava.coding.agent.rpc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;
import com.pijava.coding.agent.extension.ExtensionComponentFactory;
import com.pijava.coding.agent.extension.ExtensionCustomOptions;
import com.pijava.coding.agent.extension.ExtensionFooterFactory;
import com.pijava.coding.agent.extension.ExtensionSlotFactory;
import com.pijava.coding.agent.extension.ExtensionUI;
import com.pijava.coding.agent.extension.ExtensionUiDialogOptions;
import com.pijava.coding.agent.extension.ExtensionWidgetContent;
import com.pijava.coding.agent.extension.ExtensionWidgetOptions;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.TerminalInputHandler;

/**
 * 批 17（T3，docs/28 §1.7/§3-T3）：RPC 模式的 {@link ExtensionUI} typed 实现——
 * pi {@code createExtensionUIContext}（rpc-mode.ts:136-260）＋{@code createDialogPromise}
 * （:91-121）的逐字对位。九方法线格式见 {@link RpcExtensionUIRequest}。
 *
 * <p>语义要点（pi 逐字）：</p>
 * <ul>
 *   <li>select/confirm/input：{@code signal.aborted} 预检直返默认值；请求写出后阻塞
 *       等应答；<b>signal abort 或本地 timeout 到点都本地解除</b>（返默认值，迟到的
 *       应答由 dispatcher 队列按 id 失配丢弃——裁决⑦）；timeout 同时写进请求让宿主
 *       显示倒计时（pi :105-109）。</li>
 *   <li>editor：无 signal/timeout 面（pi :239-260 的裸 Promise 臂逐字）。</li>
 *   <li>notify/setStatus/setTitle/set_editor_text：fire-and-forget（随机 UUID，
 *       不等应答）。</li>
 *   <li>setWidget：只支持文本行臂；组件工厂臂<b>静默忽略</b>（pi :206-208 注释逐字：
 *       「Component factories are not supported in RPC mode」）。</li>
 *   <li>setFooter/setHeader/onTerminalInput：空操作；custom ⇒ null；getEditorText ⇒
 *       {@code ""}（pi 注释：同步方法等不了应答）；pasteToEditor ⇒ 回落 setEditorText。</li>
 * </ul>
 */
public final class RpcExtensionUI implements ExtensionUI {

    /** 阻塞等待的轮询步长（signal/timeout 检查粒度）。 */
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final Function<RpcExtensionUIRequest, RpcExtensionUIResponse> channel;
    private final Consumer<RpcExtensionUIRequest> fireAndForget;

    /**
     * @param channel       阻塞请求通道（{@code RpcDispatcher::extensionUiRequest}——
     *                      写请求并 poll 等应答）
     * @param fireAndForget 只写不等应答（notify/setStatus/setTitle/set_editor_text/setWidget）
     */
    public RpcExtensionUI(Function<RpcExtensionUIRequest, RpcExtensionUIResponse> channel,
                          Consumer<RpcExtensionUIRequest> fireAndForget) {
        this.channel = channel;
        this.fireAndForget = fireAndForget;
    }

    // ── 对话四件（createDialogPromise 对位）──

    @Override
    public @Nullable String select(String title, List<String> options,
                                   @Nullable ExtensionUiDialogOptions opts) {
        var params = params("title", title, "options", options);
        putTimeout(params, opts);
        return dialog("select", opts, null, params,
            // pi parseResponse（:140-141）：cancelled ⇒ undefined；否则 value。
            r -> Boolean.TRUE.equals(r.cancelled()) ? null : (String) r.value());
    }

    @Override
    public boolean confirm(String title, String message, @Nullable ExtensionUiDialogOptions opts) {
        var params = params("title", title, "message", message);
        putTimeout(params, opts);
        return dialog("confirm", opts, false, params,
            // pi（:144-145）：cancelled ⇒ false；否则 confirmed ?? false。
            r -> Boolean.TRUE.equals(r.cancelled())
                ? false : r.confirmed() != null && r.confirmed());
    }

    @Override
    public @Nullable String input(String title, @Nullable String placeholder,
                                  @Nullable ExtensionUiDialogOptions opts) {
        var params = params("title", title, "placeholder", placeholder);
        putTimeout(params, opts);
        return dialog("input", opts, null, params,
            r -> Boolean.TRUE.equals(r.cancelled()) ? null : (String) r.value());
    }

    /** pi editor（:239-260）：无 signal/timeout 面的裸请求-应答。 */
    @Override
    public @Nullable String editor(String title, @Nullable String prefill) {
        var params = params("title", title, "prefill", prefill);
        var response = channel.apply(
            new RpcExtensionUIRequest(UUID.randomUUID().toString(), "editor", params));
        return Boolean.TRUE.equals(response.cancelled()) ? null : (String) response.value();
    }

    // ── fire-and-forget 四件＋setWidget ──

    @Override
    public void notify(String message, @Nullable NotifyType type) {
        fireAndForget.accept(new RpcExtensionUIRequest(UUID.randomUUID().toString(),
            "notify", params("message", message,
                "notifyType", type == null ? null : type.wire())));
    }

    @Override
    public void setStatus(String key, @Nullable String text) {
        fireAndForget.accept(new RpcExtensionUIRequest(UUID.randomUUID().toString(),
            "setStatus", params("statusKey", key, "statusText", text)));
    }

    /**
     * pi setWidget（:201-211）：{@code undefined}（清槽）与文本行臂上线；
     * 组件工厂臂静默忽略（注释逐字）。
     */
    @Override
    public void setWidget(String key, @Nullable ExtensionWidgetContent content,
                          @Nullable ExtensionWidgetOptions options) {
        if (content != null && !(content instanceof ExtensionWidgetContent.Lines)) {
            return; // Component factories are not supported in RPC mode
        }
        var params = params("widgetKey", key, "widgetLines",
            content == null ? null : ((ExtensionWidgetContent.Lines) content).lines());
        if (options != null && options.placement() != null) {
            params.put("widgetPlacement", options.placement());
        }
        fireAndForget.accept(
            new RpcExtensionUIRequest(UUID.randomUUID().toString(), "setWidget", params));
    }

    @Override
    public void setTitle(String title) {
        fireAndForget.accept(new RpcExtensionUIRequest(UUID.randomUUID().toString(),
            "setTitle", params("title", title)));
    }

    @Override
    public void setEditorText(String text) {
        fireAndForget.accept(new RpcExtensionUIRequest(UUID.randomUUID().toString(),
            "set_editor_text", params("text", text)));
    }

    /** pi（:225-228）：paste 处理在 RPC 模式不支持——回落 setEditorText（逐字）。 */
    @Override
    public void pasteToEditor(String text) {
        setEditorText(text);
    }

    // ── 空操作臂（pi 逐字：需 TUI 访问／不支持）──

    @Override
    public void setFooter(@Nullable ExtensionFooterFactory factory) { }

    @Override
    public void setHeader(@Nullable ExtensionSlotFactory factory) { }

    @Override
    public Runnable onTerminalInput(TerminalInputHandler handler) {
        return () -> { };
    }

    /** pi custom（:223）：RPC 模式不支持 ⇒ null（pi 的 {@code undefined as never}）。 */
    @Override
    public <T> @Nullable T custom(ExtensionComponentFactory<T> factory,
                                  @Nullable ExtensionCustomOptions options) {
        return null;
    }

    /** pi getEditorText（:233-236）：同步方法等不了应答 ⇒ {@code ""}。 */
    @Override
    public String getEditorText() {
        return "";
    }

    /** 原始请求口（{@code ExtensionUI.request} 的 RPC 臂——直走通道）。 */
    @Override
    public RpcExtensionUIResponse request(RpcExtensionUIRequest request) {
        return channel.apply(request);
    }

    // ── createDialogPromise（pi :91-121 逐字语义）──

    /**
     * 对话请求：{@code signal.aborted} 预检 ⇒ 默认值；虚拟线程跑阻塞通道，调用线程
     * 轮询三方竞速（应答／signal abort／本地 timeout）。abort/timeout ⇒ 默认值，
     * 迟到的应答由 dispatcher 按 id 失配丢弃（裁决⑦）。
     */
    private <T> T dialog(String method, @Nullable ExtensionUiDialogOptions opts,
                         T defaultValue, Map<String, Object> params,
                         Function<RpcExtensionUIResponse, T> parser) {
        AbortSignal signal = opts == null ? null : opts.signal();
        if (signal != null && signal.isAborted()) {
            return defaultValue;
        }
        var request = new RpcExtensionUIRequest(UUID.randomUUID().toString(), method, params);
        var future = new CompletableFuture<RpcExtensionUIResponse>();
        Thread.ofVirtual().start(() -> {
            try {
                future.complete(channel.apply(request));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        Long timeout = opts == null ? null : opts.timeoutMillis();
        long deadline = timeout != null && timeout > 0
            ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
            : Long.MAX_VALUE;
        try {
            while (true) {
                if (signal != null && signal.isAborted()) {
                    return defaultValue;   // pi onAbort：cleanup + resolve(default)
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return defaultValue;   // pi setTimeout：cleanup + resolve(default)
                }
                try {
                    return parser.apply(
                        future.get(Math.min(remaining, POLL_NANOS), TimeUnit.NANOSECONDS));
                } catch (TimeoutException ignored) {
                    // 轮询下一拍
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Extension UI interrupted", e);
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

    /** pi 请求载荷的逐字键序（null 值省略——pi 的 undefined 键被 JSON.stringify 丢弃）。 */
    private static Map<String, Object> params(Object... alternatingKeysValues) {
        var params = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < alternatingKeysValues.length; i += 2) {
            Object value = alternatingKeysValues[i + 1];
            if (value != null) {
                params.put((String) alternatingKeysValues[i], value);
            }
        }
        return params;
    }

    /** pi（:105-109）：timeout 写进请求（宿主显示倒计时）；null/&le;0 不写。 */
    private static void putTimeout(Map<String, Object> params,
                                   @Nullable ExtensionUiDialogOptions opts) {
        if (opts != null && opts.timeoutMillis() != null) {
            params.put("timeout", opts.timeoutMillis());
        }
    }
}
