package com.pijava.coding.agent.rpc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 扩展 UI 应答通道（批 29＝docs/41 结构债清空批，自 {@link RpcDispatcher} 纯移动拆出；
 * 行级注释逐字随迁）。
 *
 * <p>挂起对话表与阻塞等应答语义与通道同生共死（构造期经方法引用挂进
 * {@link RpcExtensionUI}），故整簇成类。</p>
 */
final class RpcExtensionUiChannel {

    /**
     * 批 17 评审回补（Important #1）：扩展 UI 应答<b>按 id 路由</b>——pi
     * {@code pendingExtensionRequests} map（rpc-mode.ts:80-84）对位。旧广播队列
     * 失配即弃 ⇒ 超时/abort 的孤儿通道线程会偷走后发对话的应答。
     */
    private final ConcurrentMap<String, CompletableFuture<RpcExtensionUIResponse>>
        pendingUiRequests = new ConcurrentHashMap<>();

    private final JsonlWriter out;

    RpcExtensionUiChannel(JsonlWriter out) {
        this.out = out;
    }

    /** UI 响应 → 按 id 路由到挂起对话（pi pendingExtensionRequests 对位；
     *  无 waiter 的迟到应答丢弃——裁决⑦）。 */
    void routeResponse(String line) {
        try {
            var response =
                RpcExportSupport.mapper().readValue(line, RpcExtensionUIResponse.class);
            var pending = pendingUiRequests.remove(response.id());
            if (pending != null) {
                pending.complete(response);
            }
        } catch (Exception ignored) {
            // 畸形响应忽略
        }
    }

    /** 批 17（T3）：fire-and-forget 写出（pi notify/setStatus/setTitle/set_editor_text/setWidget 臂——不等应答）。 */
    void write(RpcExtensionUIRequest request) {
        try {
            out.write(request);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 扩展 UI 请求：写 extension_ui_request 到 stdout，阻塞等 extension_ui_response。 */
    RpcExtensionUIResponse request(RpcExtensionUIRequest request) {
        var pending = new CompletableFuture<RpcExtensionUIResponse>();
        pendingUiRequests.put(request.id(), pending);
        try {
            out.write(request);
        } catch (IOException e) {
            pendingUiRequests.remove(request.id());
            throw new UncheckedIOException(e);
        }
        try {
            return pending.get(5, TimeUnit.MINUTES);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("Extension UI request timed out: " + request.method());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Extension UI interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } finally {
            // 孤儿防护：超时/中断后摘除挂起项——迟到的应答自此无 waiter 可偷
            // （pi cleanup() 的 pendingExtensionRequests.delete(id) 对位）。
            pendingUiRequests.remove(request.id());
        }
    }
}
