package com.pijava.ai.protocol;

import java.util.function.Function;
import java.util.function.Supplier;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.HttpResponseFor;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;

import com.pijava.ai.http.ProviderRetry;
import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * 一次 HTTP 发送 ＋ threads 的一次性重发（{@code docs/12} S8，
 * pi {@code sendThreadRequest :401-443}）。从 {@link AnthropicMessagesApi} 抽出，
 * 守「文件 ≤ 500 行」。
 *
 * <p>分流矩阵本身在 {@link AnthropicThreadFallback}；这里只负责三件事：</p>
 * <ol>
 * <li>中止（{@code Thread.currentThread().isInterrupted()}）与「本轮没发 thread」都**不重发**，
 *     与 pi 的 {@code signal?.aborted} 早退同式；</li>
 * <li>诊断在重发<b>之前</b>追加 —— 重发失败也留痕（pi {@code :425-431}）；</li>
 * <li>{@code off} 只在「去 thread 的重发**成功**」之后置（pi {@code :440-442}）；
 *     重发失败时状态不动，异常原样上抛（外层 strict 判还会再看一次）。</li>
 * </ol>
 */
final class AnthropicRequestSender {

    private AnthropicRequestSender() {
    }

    /** 一次成功发送的结果 ＋ 本轮**是否真带着线程**（去 thread 的那次重发成功 ⇒ false）。 */
    record Sent(HttpResponseFor<StreamResponse<RawMessageStreamEvent>> response,
                boolean threaded) {
    }

    /**
     * 发请求，并在 threads 语义上做**至多一次**重发。
     *
     * @param client         SDK 客户端（{@code withRawResponse()} 才能拿到状态码与头）
     * @param retry          初始请求的重试选项；threads 的回退是**请求级重发**，不消耗
     *                       {@code maxRetries}（pi 直接 {@code send} 第二次）
     * @param first          第一次要发的载荷（已过 payload hook）
     * @param rebuilt        {@code create} 重发用：以线程三态重建一次（含重新过 hook）
     * @param withoutThread  去 thread 重发用：**完整**请求（probe）＋ hook
     * @param sentThread     本轮准备发出的线程三态；{@code null} ≙ 本轮没发 thread
     * @param threadSessionId 线程会话号（置 {@code off} 用；{@code null} 时不会走到那支）
     * @param builder        诊断汇聚点（{@code AssistantMessage.diagnostics}）
     */
    static Sent sendWithThreadFallback(AnthropicClient client, ProviderRetry.Options retry,
                                       Supplier<MessageCreateParams> first,
                                       Function<AnthropicThreadWire.ThreadRequest,
                                           MessageCreateParams> rebuilt,
                                       Supplier<MessageCreateParams> withoutThread,
                                       AnthropicThreadWire.ThreadRequest sentThread,
                                       String threadSessionId,
                                       StreamPartialBuilder builder) {
        try {
            return new Sent(send(client, retry, first.get()), true);
        } catch (AnthropicServiceException error) {
            if (sentThread == null || Thread.currentThread().isInterrupted()) {
                throw error;
            }
            var decision = AnthropicThreadFallback.classify(error);
            if (decision.none()) {
                throw error;
            }
            // pi :425-431 —— 先记后重发：重发失败也留痕。
            builder.appendDiagnostic(AnthropicThreadFallback.diagnostic(decision, error));
            if (decision.kind() == AnthropicThreadFallback.Kind.NEW_THREAD) {
                return new Sent(send(client, retry,
                    rebuilt.apply(AnthropicThreadWire.ThreadRequest.create())), true);
            }
            var response = send(client, retry, withoutThread.get());
            // pi :440-442 —— 端点不认 threads：整会话不再发 thread；只有本次成功才置 off。
            AnthropicThreadState.off(threadSessionId);
            return new Sent(response, false);
        }
    }

    /** 一次 HTTP 发送（含 {@code ProviderRetry}）。 */
    static HttpResponseFor<StreamResponse<RawMessageStreamEvent>> send(
            AnthropicClient client, ProviderRetry.Options retry, MessageCreateParams params) {
        return ProviderRetry.retry(
            () -> client.withRawResponse().messages().createStreaming(params),
            ProviderRetry::ofAnthropic, retry);
    }
}
