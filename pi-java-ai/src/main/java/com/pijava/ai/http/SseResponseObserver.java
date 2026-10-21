package com.pijava.ai.http;

import java.net.http.HttpHeaders;

/**
 * 终态 SSE 响应观察 —— docs/17（B248，pi fetch 车道 {@code onResponse} 的 firing 位
 * 对位：重试环退出后、{@code !ok} 判定前，<b>含错误状态</b>；pi-messages.ts:404 在
 * {@code !response.ok} 检查之前）。
 *
 * <p>http 层只外露 {@code (status, HttpHeaders)}；摊平成 {@code Map<String,String>}
 * 与模型归属是车道的事（{@code ProviderHooks.flattenHeaders}）。</p>
 */
@FunctionalInterface
public interface SseResponseObserver {

    /** 终态响应到达（重试环已退出，status 可能是错误码）。 */
    void onResponse(int status, HttpHeaders headers);
}
