package com.pijava.coding.agent.core;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.Action;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(SessionRunner.class);

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
            LinkedBlockingQueue<Optional<StreamEvent>> queue,
            CompletableFuture<List<Entry>> entriesFuture,
            CompletableFuture<RunStatus> statusFuture,
            StreamObserver streamObserver,
            EntryObserver entryObserver) {
        var laneName = owner.laneName();
        var stopReason = new AtomicReference<>("completed");
        var errorMessage = new AtomicReference<String>(null);
        // Run summary (§8): wall-clock of the whole drive (incl. retry backoff)
        // and stream usage accumulated here; tool/step counters come from the
        // lane records filtered by the runIds collected below.
        final long driveStartNanos = System.nanoTime();
        // Mutable boxes (lambda capture requires effectively-final references).
        long[] inputTokens = {0};
        long[] outputTokens = {0};
        double[] costUsd = {0};
        var runIds = new HashSet<String>();
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
            if (event instanceof StreamEvent.UsageInfo usage) {
                // TokenCounter is the session-wide accumulator and cannot be
                // reused here — this drive's usage is local to the stream.
                inputTokens[0] += usage.inputTokens();
                outputTokens[0] += usage.outputTokens();
                if (usage.usage() != null && usage.usage().cost() != null) {
                    costUsd[0] += usage.usage().cost().total();
                }
            }
            owner.emitSessionEvent(new AgentSessionEvent.MessageUpdate(event));
            if (streamObserver == null) {
                queue.add(Optional.of(event));
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
                    // pi alignment (_prepareRetry + agent.continue): a retry
                    // keeps the prior context and continues from the transcript
                    // tail instead of re-prompting (which would duplicate the
                    // user message under append semantics).
                    Action action;
                    if (attempt == 0) {
                        action = owner.harness().run(laneName, prompt);
                    } else {
                        owner.harness().dropTrailingErrorAssistant(laneName);
                        action = owner.harness().continueRun(laneName);
                    }
                    // 用户 prompt 的 entry 由 run() 产生，先落盘再开始流式
                    // （docs/27 §2.1：pi 的 `_appendEntry` → `_persist` 逐条写）。
                    flush(owner, laneName);
                    // Immediate user echo (pi alignment: agent-loop emits
                    // message_start/message_end(user) before streaming; the
                    // frontend relies on this to render the prompt instantly
                    // instead of only at agent_end's whole-table replacement).
                    // Only the first attempt — retries continue the same lane
                    // and would re-echo the same prompt. Takes the LAST user
                    // message: runs append to the transcript, so the newest
                    // user entry is this run's prompt (findFirst would echo a
                    // stale earlier turn).
                    if (attempt == 0) {
                        owner.harness().snapshot(laneName).transcript().stream()
                            .filter(Entry.Message.class::isInstance)
                            .map(e -> ((Entry.Message) e).message())
                            .filter(m -> m instanceof Message.UserMessage)
                            .reduce((first, second) -> second)
                            .ifPresent(m -> owner.emitSessionEvent(
                                new AgentSessionEvent.UserMessageReceived(m)));
                    }
                    // Collect this attempt's runId (operation id == runId,
                    // ActionExecutor) so the run summary can filter lane
                    // records to only this drive — a retry re-rolls the id.
                    var op = owner.harness().snapshot(laneName).operation();
                    if (op != null && op.id() != null) {
                        runIds.add(op.id());
                    }
                    while (action != null) {
                        action = owner.harness().executeAction(laneName, action);
                        // 每步 action 后落盘（docs/27 §2.1）。pi 的
                        // `session-manager._persist` 在条目产生后立即 appendFileSync，
                        // 崩溃窗口因此是"一条 entry"；此前 pi-java 只在 run 边界
                        // 批量 flush，窗口是"一整个 run"（多轮 + 工具调用）。
                        // 幂等：persistPending 按 id 去重，重复调用不会重写。
                        flush(owner, laneName);
                    }
                    var lane = owner.harness().snapshot(laneName);
                    transcript = List.copyOf(lane.transcript());
                } catch (Exception e) {
                    LOG.warn("[session] harness run error, stopReason=error", e);
                    stopReason.set("error");
                    if (e.getMessage() != null) {
                        errorMessage.set(e.getMessage());
                    }
                    var error = new StreamEvent.StreamError(
                        "error", e, AssistantMessage.empty());
                    if (streamObserver != null) {
                        streamObserver.onStreamEvent(error);
                    } else {
                        queue.add(Optional.of(error));
                    }
                }
                shouldRetry = "error".equals(stopReason.get())
                    && isRetryableError(errorMessage.get())
                    && owner.autoRetryEnabled()
                    && attempt < MAX_RETRIES
                    && !owner.retryAborted();
                // Flush this run's entries before AgentEnd so the full-history
                // payload matches stateSync's accumulatedEntries() source
                // (id-deduped, safe to call again below).
                flush(owner, laneName);
                var endMessages = owner.accumulatedMessages();
                LOG.info("[session] AgentEnd emit: messages={} persistedIds={} shouldRetry={}",
                    endMessages.size(),
                    owner.session() == null ? -1 : owner.persistedEntryIds().size(),
                    shouldRetry);
                owner.emitSessionEvent(new AgentSessionEvent.AgentEnd(endMessages, shouldRetry));
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
            // Consecutive runs now append to the lane transcript (pi alignment),
            // so the end-of-run delivery must dedupe by entry id — replays of
            // earlier turns' entries would duplicate bubbles in the TUI/web.
            // The dedupe set is session-scoped (owner.deliveredEntryIds) because
            // each processPrompt call drives its own SessionRunner instance.
            if (entryObserver != null) {
                for (var entry : transcript) {
                    if (owner.deliveredEntryIds().add(entry.id())) {
                        entryObserver.onEntry(entry);
                    }
                }
            }
            for (var entry : transcript) {
                if (owner.deliveredEntryIds().add(entry.id())) {
                    owner.emitSessionEvent(new AgentSessionEvent.EntryAppended(entry));
                }
            }
            flush(owner, laneName);
            owner.emitSessionEvent(new AgentSessionEvent.AgentSettled());
            var summary = RunSummaryAggregator.aggregate(
                owner.harness().snapshot(laneName).records(), runIds)
                .withTotals(new RunSummaryAggregator.Totals(
                    inputTokens[0], outputTokens[0], costUsd[0]));
            printRunSummary(owner, summary.withMeta(
                attempt + 1,
                (System.nanoTime() - driveStartNanos) / 1_000_000,
                stopReason.get()));
            statusFuture.complete(new RunStatus(
                exitCode(stopReason.get()), stopReason.get()));
        } catch (Exception e) {
            LOG.error("[session] drive failed; emitting empty AgentEnd (run=" + laneName + ")", e);
            var error = new StreamEvent.StreamError(
                "error", e, AssistantMessage.empty());
            if (streamObserver != null) {
                streamObserver.onStreamEvent(error);
            } else {
                queue.add(Optional.of(error));
            }
            owner.emitSessionEvent(new AgentSessionEvent.AgentEnd(List.of(), false));
            owner.emitSessionEvent(new AgentSessionEvent.AgentSettled());
            statusFuture.complete(new RunStatus(1, "error"));
            entriesFuture.complete(List.of());
        } finally {
            if (streamObserver == null) {
                queue.add(Optional.empty());
            }
        }
    }

    /**
     * Emit the run summary (design §8.2): {@code LOG.info} always (lands in
     * the log file), plus {@code System.err} in non-TUI modes — stdout is
     * occupied by the assistant body and must never be mixed. The interactive
     * TUI renders its own panels, so it only gets the log line.
     */
    /**
     * 把尚未落盘的 entry / record 写入持久会话（docs/27 §2.1）。
     *
     * <p>落盘时机与 pi 产品的 {@code session-manager._persist} 对齐：**每条 entry
     * 产生后即写**，而不是攒到 run 结束。pi 的崩溃窗口因此是"一条 entry"，而此前
     * pi-java 的窗口是"一整个 run"（多轮助手响应 + 工具调用，可能数分钟）。</p>
     *
     * <p>幂等：{@code persistPending} 用 id 去重，重复调用只做集合查表。</p>
     */
    private static void flush(AgentSession owner, String laneName) {
        if (owner.session() != null) {
            SessionPersistence.persistPending(owner, owner.session(), laneName);
        }
    }

    private static void printRunSummary(AgentSession owner, RunSummaryAggregator.Summary summary) {
        LOG.info("[pi-java] run summary: attempts={} durationMs={}ms stopReason={}",
            summary.attempts(), summary.durationMs(), summary.stopReason());
        LOG.info("  tokens: in={} out={} cost=${}",
            summary.totals().inputTokens(), summary.totals().outputTokens(),
            summary.totals().costUsd());
        LOG.info("  tools: {} ok, {} failed | steps: {}",
            summary.toolOk(), summary.toolFailed(), summary.steps());
        if (!isTui(owner)) {
            summary.printTo(System.err);
        }
    }

    /** Non-TUI when print mode is on, or an explicit non-text mode (web/json/rpc). */
    private static boolean isTui(AgentSession owner) {
        var args = owner.sessionArgs();
        return !args.print() && (args.mode() == null || "text".equals(args.mode()));
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
