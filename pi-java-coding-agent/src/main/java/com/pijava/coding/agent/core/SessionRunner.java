package com.pijava.coding.agent.core;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.PiLoop;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives one harness run on a virtual thread and persists the produced
 * transcript/records into the session (Phase 4 §13.1).
 *
 * <p><b>3d（docs/31 §8.22）后本类不再有重试环</b>：auto-retry 的 ①（预算、退避、
 * 摘尾、取消）与会话级 {@code retryAttempt} 全部住进 agent-core 引擎
 * （{@code PostRunRetry}，pi {@code _handlePostAgentRun} 的 ①→②→③ 全序）。
 * 宿主层只驱动一次，剩下三件事：</p>
 * <ul>
 *   <li>{@code agent_end} <b>每 pass 一条</b>（下游 sink 包装收
 *       {@code PiLoop.Event.AgentEnd}），{@code willRetry} 装饰 = pi
 *       {@code _willRetryAfterAgentEnd}（{@code agent-session.ts:666, 721-733}：
 *       倒扫本 pass 消息找尾 assistant 交 {@code harness.retryWouldFollow}）；</li>
 *   <li>{@code auto_retry_*} / {@code summarization_retry_*} 事件经
 *       {@code RetryObserver}（AgentSession.assemble 装配）映射到会话事件面，
 *       不再由本类按时点手发；</li>
 *   <li>run summary 的 {@code attempts} = 实际跑过的 pass 数
 *       （{@code RunOutcome.passRunIds().size()}）。</li>
 * </ul>
 */
final class SessionRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SessionRunner.class);

    private SessionRunner() {}

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
        // Run summary (§8): wall-clock of the whole drive (incl. the engine's
        // retry backoff) and stream usage accumulated here; tool/step counters
        // come from the lane records filtered by the pass run ids collected below.
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
            // 3d：驱动只有这一次 —— pi 的 ①重试→②压缩→③队列全序在引擎 post-run
            // 里跑（docs/31 §8.22），宿主层的 do-while 与 dropTrailingErrorAssistant
            // 一并撤销（重试的摘尾只动副本，住在 PostRunRetry）。
            // 驱动只有 PiLoop 一条（docs/31 §6）：harness.prompt 就是 pi 的
            // Agent.prompt —— 起手与收口都在其中，调用方只提供会话层的事件接收器。
            List<Entry> transcript = List.of();
            try {
                var outcome = owner.harness()
                    .prompt(laneName, prompt, List.of(), passEvents(owner, laneName));
                // 用户 prompt 的 entry 由引擎起手写入，此处补一次落盘
                // （docs/27 §2.1：pi 的 `_appendEntry` → `_persist` 逐条写）。
                flush(owner, laneName);
                // 收集本次驱动实际跑过的全部 pass 的 run id（①续跑不换
                // session 运行身份、每 pass 各 roll 一个 —— passRunIds 记账），
                // 供 run summary 把 lane records 过滤到本 drive。
                runIds.addAll(outcome.passRunIds());
                transcript = outcome.transcript();
            } catch (Exception e) {
                // 兜底保留：引擎内部失败面（操作没跑到收尾）。重试判定的
                // 那条路已归引擎 —— 这里只可能剩非重试类异常。
                LOG.warn("[session] harness run error, stopReason=error", e);
                stopReason.set("error");
                var error = new StreamEvent.StreamError(
                    "error", e, AssistantMessage.empty());
                if (streamObserver != null) {
                    streamObserver.onStreamEvent(error);
                } else {
                    queue.add(Optional.of(error));
                }
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
                // attempts = 实际 pass 数（①续跑的每个 continue 也算一 pass）；
                // 起手即抛的兜底路上集合为空 ⇒ 计 1（至少试过一次）。
                Math.max(1, runIds.size()),
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
     * 下游 sink 包装（每 pass 一收）：MessageEnd 逐条落盘、首 pass 的
     * AgentStart 上发用户回显、AgentEnd 补 flush 并发<b>每 pass 一条</b>的
     * {@code AgentEnd(willRetry)}（pi 的 agent_end 装饰，:666）。
     */
    private static PiLoop.Sink passEvents(AgentSession owner, String laneName) {
        var echoed = new AtomicBoolean();
        return event -> {
            if (event instanceof PiLoop.Event.MessageEnd) {
                // docs/27 §2.1：每条 entry 产生后即写（崩溃窗口 = 一条 entry）。
                flush(owner, laneName);
            }
            if (event instanceof PiLoop.Event.AgentStart
                    && echoed.compareAndSet(false, true)) {
                // Immediate user echo (pi alignment: agent-loop emits
                // message_start/message_end(user) before streaming; the
                // frontend relies on this to render the prompt instantly
                // instead of only at agent_end's whole-table replacement).
                // 3d 起回显挪到 pass 起点 —— AgentEnd 已在 pass 收尾处发，
                // 驱动返回后再回显就会排在第一条 agent_end 之后。只发首
                // pass（续跑 pass 不重复）；取尾条 user = 本 drive 的 prompt。
                owner.harness().snapshot(laneName).transcript().stream()
                    .filter(Entry.Message.class::isInstance)
                    .map(e -> ((Entry.Message) e).message())
                    .filter(m -> m instanceof Message.UserMessage)
                    .reduce((first, second) -> second)
                    .ifPresent(m -> owner.emitSessionEvent(
                        new AgentSessionEvent.UserMessageReceived(m)));
            }
            if (event instanceof PiLoop.Event.AgentEnd end) {
                // Flush this pass's entries before AgentEnd so the full-history
                // payload matches stateSync's accumulatedEntries() source
                // (id-deduped, safe to call again at end of drive).
                flush(owner, laneName);
                var willRetry = willRetryAfter(owner, end.messages());
                LOG.info("[session] AgentEnd emit (pass): messages={} willRetry={}",
                    owner.accumulatedMessages().size(), willRetry);
                owner.emitSessionEvent(new AgentSessionEvent.AgentEnd(
                    owner.accumulatedMessages(), willRetry));
            }
        };
    }

    /**
     * pi {@code _willRetryAfterAgentEnd}（{@code agent-session.ts:721-733}）的宿主侧：
     * 倒扫<b>本 pass 的消息</b>找第一条 assistant（= 尾 assistant），交引擎公开判据
     * （enabled/预算门 + 溢出交接 + ai 白名单）。装饰与 ① 各算各的、不共享缓存
     * （pi 也是这个形状，§8.22 裁决②）。
     */
    private static boolean willRetryAfter(AgentSession owner, List<Message> passMessages) {
        Message.AssistantMessage lastAssistant = null;
        for (int i = passMessages.size() - 1; i >= 0 && lastAssistant == null; i--) {
            if (passMessages.get(i) instanceof Message.AssistantMessage a) {
                lastAssistant = a;
            }
        }
        return owner.harness().retryWouldFollow(lastAssistant);
    }

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

    private static int exitCode(String stopReason) {
        return switch (stopReason) {
            case "error" -> 1;
            case "aborted" -> 130;
            default -> 0;
        };
    }
}
