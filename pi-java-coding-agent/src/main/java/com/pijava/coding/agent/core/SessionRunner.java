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
 * 命中重试时发 {@code AutoRetryStart}，最终发 {@code AutoRetryEnd}。</p>
 */
final class SessionRunner {

    private SessionRunner() {}

    /** 单次 run 的最大自动重试次数（对齐 pi 默认 {@code maxRetries}）。 */
    private static final int MAX_RETRIES = 3;

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
                    && owner.autoRetryEnabled()
                    && attempt < MAX_RETRIES
                    && !owner.retryAborted();
                owner.emitSessionEvent(new AgentSessionEvent.AgentEnd(
                    messages(transcript), shouldRetry));
                if (shouldRetry) {
                    attempt++;
                    owner.emitSessionEvent(new AgentSessionEvent.AutoRetryStart(
                        attempt, MAX_RETRIES, 0, errorMessage.get()));
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

    private static int exitCode(String stopReason) {
        return switch (stopReason) {
            case "error" -> 1;
            case "aborted" -> 130;
            default -> 0;
        };
    }
}
