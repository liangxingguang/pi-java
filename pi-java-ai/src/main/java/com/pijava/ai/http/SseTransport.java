package com.pijava.ai.http;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.ai.AbortSignal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * B229（pi {@code 6b07b4e57}）的增量 SSE 传输：超时只盖<b>等响应头</b>。
 *
 * <p>关键机制：自定义 {@link HttpResponse.BodyHandler} 的 {@code apply(ResponseInfo)}
 * 在头到达时被回调，用它完成 {@code infoFuture}，{@code get(headerTimeout)} 只等头；
 * 超时就 cancel exchange（JDK 25 实测对端收 EOF）。body 订阅者取
 * {@code BodySubscribers.ofInputStream}，交给 {@link PiHttpClient.SseIterator}
 * <b>增量</b>逐行消费，头之后不再有固定截止时间（body 停顿的 idle 超时 JDK HttpClient
 * 无对应机制，残留见 docs/52 §5）。从 {@link PiHttpClient} 抽出以守住 500 行上限；
 * 重试/错误体规则与其 legacy 路径相同（错误响应先把流收干再判）。</p>
 */
final class SseTransport {

    private static final Logger LOG = LoggerFactory.getLogger(SseTransport.class);

    private final HttpClient http;
    private final RetryPolicy retryPolicy;

    SseTransport(HttpClient http, RetryPolicy retryPolicy) {
        this.http = http;
        this.retryPolicy = retryPolicy;
    }

    /** 发请求并返回增量 SSE 迭代器；{@code attempt} 为重试深度。 */
    Iterator<PiHttpClient.ServerSentEvent> execute(HttpRequest request, int attempt,
                                                    AbortSignal signal,
                                                    Duration headerTimeout,
                                                    SseResponseObserver observer) {
        if (signal != null && signal.isAborted()) {
            throw new PiHttpException(0, "Request aborted");
        }
        var infoFuture = new CompletableFuture<HttpResponse.ResponseInfo>();
        var subscriber = new AtomicReference<HttpResponse.BodySubscriber<InputStream>>();
        HttpResponse.BodyHandler<InputStream> handler = info -> {
            var sub = HttpResponse.BodySubscribers.ofInputStream();
            subscriber.set(sub);
            infoFuture.complete(info);
            return sub;
        };
        var exchange = http.sendAsync(request, handler);
        // 头都没等到就失败（连接拒绝/重置）：把失败透传给 infoFuture（已完成则 no-op）。
        exchange.whenComplete((ignored, t) -> {
            if (t != null) {
                infoFuture.completeExceptionally(t);
            }
        });

        HttpResponse.ResponseInfo info;
        try {
            info = infoFuture.get(headerTimeout.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            exchange.cancel(true); // JDK 实测：cancel 真的中止 exchange（对端收 EOF）。
            if (retryPolicy.shouldRetry(new HeaderTimeoutException(headerTimeout))
                    && attempt < retryPolicy.maxRetries()) {
                sleepBeforeRetry(request, attempt, new HeaderTimeoutException(headerTimeout));
                return execute(request, attempt + 1, signal, headerTimeout, observer);
            }
            // 不包 PiHttpException：车道（Mistral）按本类型映射 pi 文案。
            throw new HeaderTimeoutException(headerTimeout);
        } catch (InterruptedException ie) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new PiHttpException(0, "Request interrupted");
        } catch (ExecutionException e) {
            exchange.cancel(true);
            var cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof java.io.IOException io && retryPolicy.shouldRetry(io)
                    && attempt < retryPolicy.maxRetries()) {
                sleepBeforeRetry(request, attempt, io);
                return execute(request, attempt + 1, signal, headerTimeout, observer);
            }
            throw new PiHttpException(0,
                "Request failed after " + (attempt + 1) + " attempts", cause);
        }

        final InputStream body;
        try {
            // ofInputStream 的 getBody 在头后即刻完成（body 可增量读），这里不引超时。
            body = subscriber.get().getBody().toCompletableFuture().get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new PiHttpException(0, "Request interrupted");
        } catch (ExecutionException e) {
            throw new PiHttpException(0, "Response body unavailable", e.getCause());
        }
        HttpResponse<InputStream> response = new InfoResponse(info, request, body);

        int status = response.statusCode();
        if (retryPolicy.shouldRetry(status, response) && attempt < retryPolicy.maxRetries()) {
            // 判重试要先收干错误体（同 legacy 的 ofString 形态）。
            String errorBody = drainQuietly(response.body());
            if (signal != null && signal.isAborted()) {
                throw new PiHttpException(0, "Request aborted");
            }
            String providerMessage = PiHttpClient.providerErrorMessage(errorBody);
            long delayMs = retryPolicy.delayMs(status, attempt, response, providerMessage);
            LOG.warn("[ai] HTTP {} for {}, retry {}/{} in {}ms (Retry-After: {})",
                status, request.uri(), attempt + 1, retryPolicy.maxRetries(), delayMs,
                response.headers().firstValue("Retry-After").orElse("n/a"));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new PiHttpException(0, "Retry interrupted");
                }
            }
            return execute(request, attempt + 1, signal, headerTimeout, observer);
        }
        // docs/17：终态响应（重试环已退出）——2xx 判定前 fire，含错误状态
        // （pi fetch 车道 onResponse 的 firing 位）。
        if (observer != null) {
            observer.onResponse(status, response.headers());
        }
        if (status < 200 || status >= 300) {
            String errorBody = drainQuietly(response.body());
            throw new PiHttpException(status, "HTTP " + status, errorBody);
        }
        // 2xx：body 交给 SseIterator 增量逐行消费（头已到达，固定截止时间不再适用）。
        var reader = new BufferedReader(new InputStreamReader(response.body(),
            StandardCharsets.UTF_8));
        var lines = reader.lines().iterator();
        return new PiHttpClient.SseIterator(new Iterator<>() {
            @Override public boolean hasNext() {
                boolean has;
                try {
                    has = lines.hasNext();
                } catch (RuntimeException e) {
                    closeQuietly(reader);
                    throw e;
                }
                if (!has) {
                    closeQuietly(reader);
                }
                return has;
            }
            @Override public String next() {
                return lines.next();
            }
        });
    }

    /** 只含 {@link HttpResponse.ResponseInfo} 的最小响应视图（status/headers 给重试用）。 */
    private record InfoResponse(HttpResponse.ResponseInfo info, HttpRequest request,
                                InputStream body) implements HttpResponse<InputStream> {
        @Override public int statusCode() {
            return info.statusCode();
        }
        @Override public java.net.http.HttpHeaders headers() {
            return info.headers();
        }
        @Override public HttpRequest request() {
            return request;
        }
        @Override public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }
        @Override public URI uri() {
            return request.uri();
        }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }
        @Override public HttpClient.Version version() {
            return info.version();
        }
    }

    /** IOException/头超时的重试退避。 */
    private void sleepBeforeRetry(HttpRequest request, int attempt, Exception e) {
        if (Thread.currentThread().isInterrupted()) {
            throw new PiHttpException(0, "Retry interrupted", e);
        }
        long delayMs = retryPolicy.delayMs(0, attempt, null);
        LOG.warn("[ai] request to {} failed, retry {}/{} in {}ms",
            request.uri(), attempt + 1, retryPolicy.maxRetries(), delayMs, e);
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new PiHttpException(0, "Retry interrupted", ie);
            }
        }
    }

    private static String drainQuietly(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static void closeQuietly(BufferedReader reader) {
        try {
            reader.close();
        } catch (java.io.IOException ignored) {
            // 流已尽/对端已断：无可清理动作。
        }
    }
}
