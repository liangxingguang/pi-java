package com.pijava.coding.agent.core;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.Action;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;

/**
 * Drives one harness run on a virtual thread and persists the produced
 * transcript/records into the session (Phase 4 §13.1).
 *
 * <p>P6-5d: 支持自动重试（对齐 pi {@code _willRetryAfterAgentEnd}）——run 以
 * {@code error} 结束时，若会话启用 auto-retry 且尝试次数未耗尽且未被
 * {@code abort_retry} 中止，则重跑。每次尝试发 {@code AgentEnd(willRetry)}，
 * 命中重试时发 {@code AutoRetryStart}，最终发 {@code AutoRetryEnd}。重试带
 * 指数退避（{@code baseDelayMs * 2^(attempt-1)}，可被 {@code abort_retry} 中止），
 * 上下文溢出错误不重试（交由压缩处理，对齐 pi {@code isContextOverflow}）。</p>
 */
final class SessionRunner {

    private SessionRunner() {}

    /** 单次 run 的最大自动重试次数（对齐 pi 默认 {@code maxRetries}）。 */
    private static final int MAX_RETRIES = 3;

    /** 重试指数退避的基准延迟（对齐 pi 默认 {@code baseDelayMs}）。 */
    private static final long BASE_DELAY_MS = 2_000;

    /** 上下文溢出错误特征串（对齐 pi {@code isContextOverflow} 的 OVERFLOW_PATTERNS）。 */
    private static final List<String> CONTEXT_OVERFLOW_MARKERS = List.of(
        "prompt is too long", "request_too_large", "input is too long for requested model",
        "exceeds the context window", "maximum context length", "context length exceeded",
        "context_length_exceeded", "input token count exceeds the maximum", "maximum prompt length",
        "reduce the length of the messages", "too many tokens", "maximum context size",
        "context window exceeds limit", "exceeded model token limit", "too long for model",
        "model_context_window_exceeded", "range of input length should be", "input is too long");

    static void drive(
            AgentSession owner,
            String prompt,
            LinkedBlockingQueue<StreamEvent> queue,
            CompletableFuture<List<Entry>> entriesFuture,
            CompletableFuture<RunStatus> statusFuture,
            StreamObserver streamObserver,
            EntryObserver entryObserver) {
        var laneName = owner.laneName();
        var stopReason = new AtomicReference<>("completed");
        var errorMessage = new AtomicReference<String>(null);
        try (var registration = owner.harness().onStreamEvent(event -> {
            if (event instanceof StreamEvent.StreamDone done && done.reason() != null) {
                stopReason.set(done.reason());
            }
            if (event instanceof StreamEvent.StreamError err) {
                stopReason.set("error");
                if (err.error() != null && err.error().getMessage() != null) {
                    errorMessage.set(err.error().getMessage());
                }
            }
            owner.emitSessionEvent(new AgentSessionEvent.MessageUpdate(event));
            if (streamObserver == null) {
                queue.add(event);
            } else {
                streamObserver.onStreamEvent(event);
            }
        })) {
            owner.resetRetryAbort();
            int attempt = 0;
            List<Entry> transcript = List.of();
            boolean shouldRetry;
            do {
                shouldRetry = false;
                try {
                    Action action = owner.harness().run(laneName, prompt);
                    while (action != null) {
                        action = owner.harness().executeAction(laneName, action);
                    }
                    var lane = owner.harness().snapshot(laneName);
                    transcript = List.copyOf(lane.transcript());
                } catch (Exception e) {
                    stopReason.set("error");
                    if (e.getMessage() != null) {
                        errorMessage.set(e.getMessage());
                    }
                    var error = new StreamEvent.StreamError(
                        "error", e, AssistantMessage.empty());
                    if (streamObserver != null) {
                        streamObserver.onStreamEvent(error);
                    } else {
                        queue.add(error);
                    }
                }
                shouldRetry = "error".equals(stopReason.get())
                    && isRetryableError(errorMessage.get())
                    && owner.autoRetryEnabled()
                    && attempt < MAX_RETRIES
                    && !owner.retryAborted();
                owner.emitSessionEvent(new AgentSessionEvent.AgentEnd(
                    messages(transcript), shouldRetry));
                if (shouldRetry) {
                    attempt++;
                    long delayMs = retryDelayMs(attempt);
                    owner.emitSessionEvent(new AgentSessionEvent.AutoRetryStart(
                        attempt, MAX_RETRIES, delayMs, errorMessage.get()));
                    abortableSleep(delayMs, owner);
                }
            } while (shouldRetry);

            if (attempt > 0) {
                boolean success = !"error".equals(stopReason.get());
                owner.emitSessionEvent(new AgentSessionEvent.AutoRetryEnd(
                    success, attempt, success ? null : errorMessage.get()));
            }
            entriesFuture.complete(transcript);
            if (entryObserver != null) {
                for (var entry : transcript) {
                    entryObserver.onEntry(entry);
                }
            }
            for (var entry : transcript) {
                owner.emitSessionEvent(new AgentSessionEvent.EntryAppended(entry));
            }
            if (owner.session() != null) {
                SessionPersistence.persistPending(owner, owner.session(), laneName);
            }
            owner.emitSessionEvent(new AgentSessionEvent.AgentSettled());
            statusFuture.complete(new RunStatus(
                exitCode(stopReason.get()), stopReason.get()));
        } catch (Exception e) {
            var error = new StreamEvent.StreamError(
                "error", e, AssistantMessage.empty());
            if (streamObserver != null) {
                streamObserver.onStreamEvent(error);
            } else {
                queue.add(error);
            }
            owner.emitSessionEvent(new AgentSessionEvent.AgentEnd(List.of(), false));
            owner.emitSessionEvent(new AgentSessionEvent.AgentSettled());
            statusFuture.complete(new RunStatus(1, "error"));
            entriesFuture.complete(List.of());
        } finally {
            if (streamObserver == null) {
                queue.add(null);
            }
        }
    }

    private static List<Message> messages(List<Entry> transcript) {
        return transcript.stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> ((Entry.Message) e).message())
            .toList();
    }

    /**
     * 错误是否可自动重试（pi {@code isRetryableAssistantError}）：上下文溢出不重试，
     * 交由压缩处理，避免空耗重试预算。
     */
    static boolean isRetryableError(String errorMessage) {
        if (errorMessage == null) {
            return true;
        }
        String lower = errorMessage.toLowerCase();
        for (var marker : CONTEXT_OVERFLOW_MARKERS) {
            if (lower.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    /** 指数退避延迟：{@code baseDelayMs * 2^(attempt-1)}（pi {@code _prepareRetry}）。 */
    static long retryDelayMs(int attempt) {
        return BASE_DELAY_MS * (1L << (attempt - 1));
    }

    /** 退避睡眠（每 50ms 轮询 {@code abort_retry}，可中止）。 */
    private static void abortableSleep(long delayMs, AgentSession owner) {
        long end = System.nanoTime() + delayMs * 1_000_000L;
        while (System.nanoTime() < end && !owner.retryAborted()) {
            long remainingMs = (end - System.nanoTime()) / 1_000_000L;
            try {
                Thread.sleep(Math.min(50, Math.max(1, remainingMs)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static int exitCode(String stopReason) {
        return switch (stopReason) {
            case "error" -> 1;
            case "aborted" -> 130;
            default -> 0;
        };
    }
}
