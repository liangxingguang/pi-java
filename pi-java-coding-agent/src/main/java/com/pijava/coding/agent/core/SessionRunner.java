package com.pijava.coding.agent.core;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.PiLoop;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives one harness run on a virtual thread and persists the produced
 * transcript/records into the session (Phase 4 §13.1).
 *
 * <p><b>3d（原 docs/31 §8.22）后本类不再有重试环</b>：auto-retry 的 ①（预算、退避、
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
        // pi _runAgentPrompt:1823：驱动起手清 abort 请求旗（settled 的 aborted 载荷源）。
        owner.agentRunAbortRequested.set(false);
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
                // Summary/exitCode 保留含 "completed" 的 String 词表：枚举边界出 wire 字面。
                stopReason.set(done.reason().wireName());
            }
            if (event instanceof StreamEvent.StreamError err) {
                // C 批次（原 docs/55 §5 F4）：采信车道给的 reason，不再一律塌成 "error"。
                // pi 的两者语义不同（打印模式两者都退 1，但消息与状态保留 aborted，
                // print-mode.ts:139-155 / agent.ts:526-542）；本仓的 PiLoopRunner 与
                // RunFailure 也一直保留它 ⇒ 此前是同仓两套口径。
                // ⚠️ 本改动**今天不可观察**：下面的读尾（B5 第 2 步）在尾条是
                // error/aborted 的助手消息时会用 tail.stopReason() 覆盖这个值，
                // 而流错误必然产生这样一条尾条 ⇒ 变异探针 M4 零红（原 docs/55 §12）。
                stopReason.set(StreamEvent.isSettled(err.reason())
                    ? err.reason().wireName() : "error");
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
            // 里跑（原 docs/31 §8.22），宿主层的 do-while 与 dropTrailingErrorAssistant
            // 一并撤销（重试的摘尾只动副本，住在 PostRunRetry）。
            // 驱动只有 PiLoop 一条（原 docs/31 §6）：harness.prompt 就是 pi 的
            // Agent.prompt —— 起手与收口都在其中，调用方只提供会话层的事件接收器。
            List<Entry> transcript = List.of();
            try {
                var outcome = owner.harness()
                    .prompt(laneName, prompt, List.of(), passEvents(owner, laneName));
                // 用户 prompt 的 entry 由引擎起手写入，此处补一次落盘
                // （原 docs/27 §2.1：pi 的 `_appendEntry` → `_persist` 逐条写）。
                flush(owner, laneName);
                // 收集本次驱动实际跑过的全部 pass 的 run id（①续跑不换
                // session 运行身份、每 pass 各 roll 一个 —— passRunIds 记账），
                // 供 run summary 把 lane records 过滤到本 drive。
                runIds.addAll(outcome.passRunIds());
                transcript = outcome.transcript();
            } catch (Throwable t) {
                // 兜底保留：引擎内部失败面（操作没跑到收尾）。重试判定的
                // 那条路已归引擎 —— 这里只可能剩非重试类异常。
                // B5 第 1 步（原 docs/31 §8.36.4）：`Exception` → `Throwable` —— pi 的
                // catch 无类型（`agent.ts:500`），Java 的 Exception/Error 分裂是方言，
                // 不是 pi 的形状。只接 Exception 会让 Error 直穿：两个 future 永不完成，
                // SessionResult.status()/entries() 是 join ⇒ 打印模式永久挂起。
                LOG.warn("[session] harness run error, stopReason=error", t);
                stopReason.set("error");
                var error = StreamEvent.StreamError.settle(
                    StopReason.ERROR, t, AssistantMessage.empty());
                if (streamObserver != null) {
                    streamObserver.onStreamEvent(error);
                } else {
                    queue.add(Optional.of(error));
                }
            }

            // B5 第 2 步（原 docs/31 §8.36.5）：pi `modes/print-mode.ts:139-155` —— 跑完之后
            // 读**转录尾条**定终局，而不是看流信号。引擎把 pass 内的抛出收成一条失败助手
            // 消息后，流上不会再有任何终局信号（那次 pass 的 StreamDone 早在工具批次之前
            // 就发过了）⇒ 只看流信号会把崩溃的 run 记成 (0, completed)。
            // **无条件读**（没有「见过终局就跳过」的闸）：一次驱动可能跑多个 pass、
            // 每 pass 至少一条 StreamDone ⇒「见过终局」与「这次运行以失败收场」是两回事。
            // 判据逐字照抄 pi：尾条是助手且 stopReason 为 error/aborted 才改写 ——
            // 正常路径上尾条与流信号同源同值，不产生行为差异。
            var tail = tailAssistant(transcript);
            if (tail != null && (tail.stopReason() == StopReason.ERROR
                    || tail.stopReason() == StopReason.ABORTED)) {
                stopReason.set(tail.stopReason().wireName());
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
            // pi agent_settled（agent-session.ts:203/:1083/:1849）：每次驱动恰一条，
            // aborted 读会话层 abort 请求旗。每回合的 turn_end 由漏斗直供（引擎发射）。
            owner.emitSessionEvent(new AgentSessionEvent.AgentSettled(
                owner.agentRunAbortRequested()));
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
        } catch (Throwable t) {
            // B5 第 1 步（原 docs/31 §8.36.4）：同 `:105` 那处，`Exception` → `Throwable`。
            LOG.error("[session] drive failed; emitting empty AgentEnd (run=" + laneName + ")", t);
            var error = StreamEvent.StreamError.settle(
                StopReason.ERROR, t, AssistantMessage.empty());
            if (streamObserver != null) {
                streamObserver.onStreamEvent(error);
            } else {
                queue.add(Optional.of(error));
            }
            // 防御方言（正常路径不可达）：引擎抛穿时补一对收口事件，pi 无对应。
            owner.emitSessionEvent(new AgentSessionEvent.AgentEnd(List.of(), false));
            owner.emitSessionEvent(new AgentSessionEvent.AgentSettled(
                owner.agentRunAbortRequested()));
            statusFuture.complete(new RunStatus(1, "error"));
            entriesFuture.complete(List.of());
        } finally {
            if (streamObserver == null) {
                queue.add(Optional.empty());
            }
            // B5 第 1 步的「活性」那一半（原 docs/31 §8.36.4 ①-b）：照抄引擎侧
            // PiLaneEngine.drive 的同一条纪律 —— 无论从哪条路离开（含 catch 体
            // 自己再抛、含 Error 直穿 try-with-resources），两个 future 都必须落定。
            // 否则 SessionResult.status()/entries() 是 join ⇒ 打印模式只能 Ctrl-C。
            // complete() 幂等：正常路径上这两次调用都是无操作。
            // 不承诺 OOM 极端下的活性：若 complete 自身再抛，JVM 已在崩溃态。
            entriesFuture.complete(List.of());
            statusFuture.complete(new RunStatus(1, "error"));
        }
    }

    /**
     * 下游 sink 包装：pi 的<b>单一漏斗全量转发</b>（{@code agent-session.ts:1139-1142}）——
     * 引擎事件全量成为会话事件，仅 {@code agent_end} 过漏斗时装饰 {@code willRetry}。
     */
    private static PiLoop.Sink passEvents(AgentSession owner, String laneName) {
        return event -> {
            // pi 漏斗（agent-session.ts:1139-1142）：引擎事件全量成为会话事件，
            // 仅 agent_end 过漏斗时装饰 willRetry。
            // ⚠️ MessageUpdate 刻意不在此列：会话层的 MessageUpdate 由 :92 的原始帧
            // 广播链喂（含 UsageInfo 等非生命周期帧），漏斗再转一手会双发。
            switch (event) {
                case PiLoop.Event.AgentStart ignored ->
                    owner.emitSessionEvent(new AgentSessionEvent.AgentStart());
                case PiLoop.Event.TurnStart ignored ->
                    owner.emitSessionEvent(new AgentSessionEvent.TurnStart());
                case PiLoop.Event.TurnEnd e ->
                    owner.emitSessionEvent(new AgentSessionEvent.TurnEnd(
                        e.message(), e.toolResults()));
                case PiLoop.Event.MessageStart m ->
                    owner.emitSessionEvent(new AgentSessionEvent.MessageStart(m.message()));
                case PiLoop.Event.MessageEnd m -> {
                    // 原 docs/27 §2.1：每条 entry 产生后即写（崩溃窗口 = 一条 entry）。
                    flush(owner, laneName);
                    owner.emitSessionEvent(new AgentSessionEvent.MessageEnd(m.message()));
                }
                case PiLoop.Event.ToolExecutionStart s ->
                    owner.emitSessionEvent(new AgentSessionEvent.ToolExecutionStart(
                        s.toolCallId(), s.toolName(), s.args()));
                case PiLoop.Event.ToolExecutionUpdate u ->
                    owner.emitSessionEvent(new AgentSessionEvent.ToolExecutionUpdate(
                        u.toolCallId(), u.toolName(), u.args(), u.partialResult()));
                case PiLoop.Event.ToolExecutionEnd e ->
                    owner.emitSessionEvent(new AgentSessionEvent.ToolExecutionEnd(
                        e.toolCallId(), e.toolName(), e.result(), e.isError(),
                        e.durationMs()));
                case PiLoop.Event.AgentEnd end -> {
                    flush(owner, laneName);
                    var willRetry = willRetryAfter(owner, end.messages());
                    LOG.info("[session] AgentEnd emit (pass): newMessages={} willRetry={}",
                        end.messages().size(), willRetry);
                    // #52：载荷改 pi 形状 —— 本 pass 的 newMessages
                    // （agent-loop.ts:254/290/320），不再是 accumulatedMessages() 全历史。
                    // web 的整表替换由 AgentEventTranslator 的 transcriptSource 吸收（§1.7）。
                    owner.emitSessionEvent(new AgentSessionEvent.AgentEnd(
                        end.messages(), willRetry));
                }
                case PiLoop.Event.MessageUpdate ignored -> { /* :92 广播已喂，见 switch 注释 */ }
            }
        };
    }

    /**
     * pi print 模式的「读尾」（{@code modes/print-mode.ts:139-155}）：转录的
     * <b>最后一条消息</b>是助手消息时返回它，否则 {@code null}。
     *
     * <p>pi 的判据是 {@code state.messages[last]?.role === "assistant" &&
     * (stopReason === "error" || stopReason === "aborted")} —— 尾条**不是**助手就不判，
     * 不会往回找更早的助手。这里照抄：倒扫跳过非消息条目（entry 表里还有别的变体），
     * 遇到第一条消息条目就定案。</p>
     */
    private static Message.AssistantMessage tailAssistant(List<Entry> transcript) {
        for (int i = transcript.size() - 1; i >= 0; i--) {
            if (transcript.get(i) instanceof Entry.Message entry) {
                return entry.message() instanceof Message.AssistantMessage assistant
                    ? assistant : null;
            }
        }
        return null;
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
     * 把尚未落盘的 entry / record 写入持久会话（原 docs/27 §2.1）。
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
