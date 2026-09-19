package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.compaction.CompactionService;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.CompactionContext;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.OperationOutcome;
import com.pijava.agent.record.StepKind;
import com.pijava.ai.message.Message;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.TelemetrySpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compact a lane transcript: {@code before_compaction} hook, compaction span,
 * and the {@link Entry.Compaction} marker entry with the retained tail.
 *
 * <p>Extracted from the former step-chain executor in the agent-loop L1 cleanup
 * (docs/20 §8) to keep files under the 500-line limit.</p>
 *
 * <p><b>3c 的分层</b>（{@code docs/31 §8.21}）：pi 的自动压缩只有一个函数
 * {@code _runAutoCompaction}（{@code agent-session.ts:2270-2445}），守卫、事件、
 * 落库、重建、二次删尾、异常兜底全在其中；轮内阈值门（{@code :550}）与运行后的
 * {@code _checkCompaction}（{@code :2255}）都汇入它。这里同形：
 * {@link #runAutoCompaction} 是那条唯一的路，{@link #applyCompaction} 只是它的
 * 共享执行体（{@code _runDefaultCompaction} 的对应物），手动 {@code /compact}
 * 走 pi 的 {@code compact()} 形状 —— 各发各的事件，文案前缀也不同
 * （{@code "Compaction failed: "} vs {@code "Auto-compaction failed: "}）。</p>
 */
final class CompactionExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(CompactionExecutor.class);

    private final ExecutionContext ctx;

    CompactionExecutor(ExecutionContext ctx) {
        this.ctx = ctx;
    }

    /**
     * 一次自动压缩的双结果 —— pi 的 {@code _runAutoCompaction} 返回值只被用作
     * 「要不要 continue」（{@code :2420/:2425}），但 pi-java 的轮内门还要知道
     * 「有没有真的压」（决定 {@code NextTurnUpdate.context} 的换与不换）。两个
     * 问句分开答，pi 的两个消费点各自读自己那半。
     *
     * @param compacted      转录是否被替换（落库+重建发生）
     * @param shouldContinue 驱动侧是否要再 continue 一轮（pi 返回值语义）
     */
    record AutoCompactionOutcome(boolean compacted, boolean shouldContinue) {

        static final AutoCompactionOutcome SKIPPED = new AutoCompactionOutcome(false, false);
    }

    /**
     * Compact the specified lane's transcript (pi {@code AgentSession.compact()},
     * the manual entry point, {@code agent-session.ts:1967-2105}).
     *
     * <p>守卫是 pi {@code compact()} 的形状（{@code agent-session.ts:1963-1969}
     * 对 {@code prepareCompaction} 空返回的解释，判据在 {@code compaction.ts:638}）：
     * <b>空路径</b> ⇒ "Nothing to compact (session too small)"；<b>最后一条已是
     * compaction 标记</b> ⇒ "Already compacted"。pi-java 用同一个
     * {@link NothingToCompactException} 承载两种原因（异常类型是方言，错误文案
     * 归宿主命令面清点）。此前这里的 {@code size <= 1} 门槛在 pi 不存在 ——
     * 单条消息的转录在 pi 是可压缩的（切点扫到它自己）。</p>
     *
     * <p><b>事件时序照 pi</b>：{@code compaction_start{manual}} 在守卫<b>之前</b>
     * （{@code :1970}），守卫或压缩体抛错 ⇒ {@code compaction_end{manual,
     * result:undefined, aborted:false, errorMessage:"Compaction failed: …"}}
     * 后原样再抛（{@code :2092-2104}）。</p>
     */
    void compact(String laneName, CompactionSettings settings) {
        var lane = ctx.requireLane(laneName);
        // pi :1969 —— 手动路在 compaction_start **之前**建控制器（自动路的 :2291 相反）。
        // 守卫（"Already compacted"）、压缩体、中止三条抛出路径都在窗口内；
        // 清位在 finally，与 pi 的 :2117 `_clearManualCompactionState()` 同形。
        lane.enterCompaction();
        try {
            ctx.compactionObserver().onStart("manual");
            CompactionRun run;
            try {
                if (lane.transcript.isEmpty()) {
                    throw new NothingToCompactException(laneName);
                }
                if (lane.transcript.getLast() instanceof Entry.Compaction) {
                    throw new NothingToCompactException(laneName);
                }
                run = applyCompaction(laneName, lane, settings,
                    (int) contextTokens(lane), "manual", false);
            } catch (RuntimeException e) {
                // pi 的 catch（:2092-2104）：end{manual, aborted: 取消类, errorMessage:
                // 非取消类才有 "Compaction failed: " 前缀} 后再抛。取消分支的 end 已由
                // applyCompaction 的发中止检查承担（aborted:true），这里不重复发。
                if (!"Compaction cancelled".equals(e.getMessage())) {
                    ctx.compactionObserver().onEnd("manual", null, false, false,
                        "Compaction failed: " + (e.getMessage() == null ? "compaction failed" : e.getMessage()));
                }
                throw e;
            }
            if (run.aborted()) {
                // pi :2049-2051 在 try 内抛 "Compaction cancelled"（end{aborted:true}
                // 走它的 catch）；这里等价：end 已在体内发过，只差抛出。
                throw new IllegalStateException("Compaction cancelled");
            }
            ctx.publishState(laneName);
        } finally {
            lane.exitCompaction();
        }
    }

    /**
     * pi {@code estimateContextTokens(context.messages).tokens} —— 用量优先的
     * 上下文估算（compaction.ts:215-243；操作数是车道工作副本 ≙ pi 的
     * {@code context.messages}）。压缩的三条路径（threshold / manual / overflow）
     * 共用这一个来源做 tokensBefore，正如 pi 三条路都经
     * {@code prepareCompaction}（{@code :667}）。
     */
    long contextTokens(LaneState lane) {
        return (long) com.pijava.agent.context.ContextUsageEstimator
            .estimateContextTokens(List.copyOf(lane.messages)).tokens();
    }

    /**
     * 轮内阈值门（pi {@code _compactBeforeNextAssistantResponse}，
     * {@code agent-session.ts:538-555}）。
     *
     * <p><b>门形状（3b，docs/31 §8.20）</b>逐条对齐：无模型 ⇒ 跳过；当前模型的
     * {@code contextWindow <= 0} ⇒ 跳过；{@code !shouldCompact(
     * estimateContextTokens(context.messages).tokens, model.contextWindow,
     * settings)} ⇒ 跳过。通过后 pi 交 {@code _runAutoCompaction("threshold",
     * false)}（{@code :550}）—— 这里同形交 {@link #runAutoCompaction}，
     * 事件与守卫都在其内。门自己的 {@code transcript.size() <= 1} 发明早在 3b
     * 删除；{@code prepareCompaction} 守卫由 runAutoCompaction 承担。</p>
     *
     * @return whether a compaction ran (the caller must then hand the rebuilt
     *         messages back to the loop through {@code NextTurnUpdate.context})
     */
    boolean checkThreshold(String laneName, LaneState lane) {
        var settings = ctx.compactionSettings().get();
        if (settings == null) return false;
        var model = ctx.model().get();
        if (model == null) return false;
        int window = ctx.contextWindow(model);
        if (window <= 0) return false;
        long estimatedTokens = contextTokens(lane);
        if (!com.pijava.agent.context.ContextUsageEstimator
                .shouldCompact(estimatedTokens, window, settings)) {
            return false;
        }
        return runAutoCompaction(laneName, lane, "threshold", false).compacted();
    }

    /**
     * pi {@code _runAutoCompaction(reason, willRetry)}（{@code agent-session.ts:2270-2445}）
     * 的逐条移植 —— 3c 起它是一切<b>自动</b>压缩的唯一入口（轮内阈值、运行后阈值、
     * 溢出共用，与 pi 同形）。
     *
     * <p>守卫顺序照 pi：无模型 ⇒ false（{@code :2277}）；{@code prepareCompaction}
     * 空返回（空路径 / 末条已是 compaction，{@code compaction.ts:638}）⇒ false
     * （{@code :2286}，<b>不发</b> start）。此后 {@code compaction_start}（{@code :2290}）、
     * 压缩体、中止判定（{@code :2362} ⇒ {@code end{aborted:true}}）、
     * {@code end{result, willRetry}}（{@code :2408}）。</p>
     *
     * <p><b>willRetry 的二次删尾</b>（{@code :2410-2419}）：压缩后的状态重建可能把
     * 那条溢出/截断助手消息从日志里带回来（R2 删的只是副本，日志未动），
     * {@code agent.continue()} 拒绝以助手消息收尾的状态，故再删一次 —— 判据是
     * 尾部消息 stopReason ∈ {error, length}。非重试路径返回
     * {@code hasQueuedMessages()}（{@code :2425}）。</p>
     *
     * <p>异常兜底照 {@code :2426-2448}：start 已发 ⇒ 发 {@code end{result:undefined,
     * aborted:false, willRetry:false, errorMessage}}，文案前缀按 reason 分流
     * （overflow ⇒ "Context overflow recovery failed: "，否则 "Auto-compaction
     * failed: "），返回 false。{@code _emitSessionCompactFailed}（扩展层伴生事件）
     * 不在 3c 面（docs/31 §8.21.5 登记）。</p>
     */
    AutoCompactionOutcome runAutoCompaction(String laneName, LaneState lane,
                                            String reason, boolean willRetry) {
        if (ctx.model().get() == null) {
            return AutoCompactionOutcome.SKIPPED;
        }
        var settings = ctx.compactionSettings().get();
        if (settings == null) {
            return AutoCompactionOutcome.SKIPPED;
        }
        // pi prepareCompaction 的守卫（compaction.ts:638）：空路径或末条已是
        // compaction ⇒ 静默不压、不发事件。
        if (lane.transcript.isEmpty()
                || lane.transcript.getLast() instanceof Entry.Compaction) {
            return AutoCompactionOutcome.SKIPPED;
        }
        ctx.compactionObserver().onStart(reason);
        // pi :2290 发 compaction_start、:2291 才建控制器 —— 上面的三条前置守卫
        // （无模型 / 无设置 / prepareCompaction 空返回）都已 return，与 pi 的
        // :2276/:2286 一样**不进**窗口。清位在 finally（pi :2450）。
        lane.enterCompaction();
        try {
            var run = applyCompaction(laneName, lane, settings,
                (int) contextTokens(lane), reason, willRetry);
            if (run.aborted()) {
                // pi :2362-2377：end{aborted:true, willRetry:false} 已在体内发，
                // 转录未替换。
                return AutoCompactionOutcome.SKIPPED;
            }
            if (willRetry) {
                dropTrailingRetryableAssistant(lane);
                return new AutoCompactionOutcome(true, true);
            }
            return new AutoCompactionOutcome(true, hasQueuedMessages(lane));
        } catch (RuntimeException e) {
            var message = e.getMessage() != null ? e.getMessage() : "compaction failed";
            var formatted = "overflow".equals(reason)
                ? "Context overflow recovery failed: " + message
                : "Auto-compaction failed: " + message;
            ctx.compactionObserver().onEnd(reason, null, false, false, formatted);
            return AutoCompactionOutcome.SKIPPED;
        } finally {
            lane.exitCompaction();
        }
    }

    /** pi {@code agent.hasQueuedMessages()}（{@code agent.ts:309-311}）：steer/followUp 任一有货。 */
    static boolean hasQueuedMessages(LaneState lane) {
        return !lane.steerQueue.isEmpty() || !lane.followUpQueue.isEmpty();
    }

    /**
     * pi {@code _runAutoCompaction}/{@code compact()} 的共享执行体（{@code :2296-2408}
     * 去掉事件的部分；{@code _runDefaultCompaction} 的对应物）：钩子 → 压缩产物 →
     * 整体替换 → 工作副本重建 → 记录与跨度。事件（start/end）由调用方按各自时序发。
     *
     * <p><b>中止判定</b>（pi {@code :2362}）在生成之后、落库之前：pi-java 的
     * {@code SummaryGenerator} 不接信号（方言），只能在生成返回后观察
     * {@code lane.abortSignal()}；命中则转录未动，返回 {@code aborted:true}。</p>
     *
     * @param reason {@code "manual"} / {@code "threshold"} / {@code "overflow"}
     * @param willRetry pi 的 {@code compaction_end.willRetry}，随 end 事件透传
     */
    CompactionRun applyCompaction(String laneName, LaneState lane,
                                  CompactionSettings settings, int estimatedTokens,
                                  String reason, boolean willRetry) {
        ctx.telemetry().incrementCounter("compactions", 1);
        int entriesBefore = lane.transcript.size();
        long start = System.nanoTime();
        String compactionEntryId = null;
        var span = (lane.runSpan != null ? lane.runSpan : ctx.telemetry())
            .openSpan(new SpanOptions("compaction.apply",
                java.util.Map.of("reason", reason, "estimatedTokens", estimatedTokens,
                    "entriesBefore", entriesBefore)));
        try {
            var compactCtx = new CompactionContext(laneName,
                List.copyOf(lane.transcript), estimatedTokens);
            var plan = ctx.hookSystem().fireBeforeCompaction(laneName, compactCtx);
            List<Entry> compacted;
            CompactionResult result = null;
            if (plan != null && !plan.keepEntries().isEmpty()) {
                compacted = plan.keepEntries();
            } else {
                var built = compactTranscript(lane, settings, reason, span);
                compacted = built.kept();
                result = built.result();
            }
            var signal = lane.abortSignal();
            if (signal != null && signal.isAborted()) {
                ctx.compactionObserver().onEnd(reason, null, true, false, null);
                return new CompactionRun(null, true);
            }
            lane.transcript.clear();
            lane.transcript.addAll(compacted);
            // 日志被整体替换 ⇒ 工作副本跟着重建（pi agent-session.ts:2380-2382 的
            // 「写 entry → buildSessionContext → state.messages = ...」）。只有重建这一条路：
            // 压缩从不原地改写消息，它换的是日志。
            HarnessUtils.rebuildLaneMessages(lane);
            // The builder path puts the fresh marker at the head; the hook-plan
            // path keeps caller-supplied entries and creates no marker.
            if (!compacted.isEmpty() && compacted.get(0) instanceof Entry.Compaction marker) {
                compactionEntryId = marker.id();
            }
            if (result != null) {
                // pi :2383：estimatedTokensAfter 从**重建后**的消息读，纯字符和
                // （estimateMessagesTokens，无用量锚点）—— 与触发判据的
                // estimateContextTokens 是两回事。
                result = new CompactionResult(result.summary(), result.firstKeptEntryId(),
                    result.tokensBefore(),
                    (long) com.pijava.agent.context.ContextUsageEstimator
                        .estimateMessagesTokens(List.copyOf(lane.messages)),
                    result.usage(), result.details());
            }
            span.addAttribute("entriesAfter", lane.transcript.size());
            LOG.info("[agent] compaction lane={} reason={} tokensBefore={} entries {}->{}",
                laneName, reason, estimatedTokens, entriesBefore, lane.transcript.size());
            emitCompactionRecords(laneName, lane, reason, compactionEntryId,
                (System.nanoTime() - start) / 1_000_000);
            // pi :2408 的 end 在重建与记录之后；plan 路径没有产物对象，result 发
            // null ≙ pi 的 undefined（自定义压缩内容本就没有 CompactionResult 的方言）。
            ctx.compactionObserver().onEnd(reason, result, false, willRetry, null);
            return new CompactionRun(result, false);
        } finally {
            span.close();
        }
    }

    /** {@link #applyCompaction} 的结果：产物（可无）+ 是否被中止。 */
    record CompactionRun(CompactionResult result, boolean aborted) {}

    /**
     * pi {@code :2410-2419} —— 状态重建可能把尾部助手消息带回副本；以
     * error/length 收尾的那条会被 {@code agent.continue()} 拒绝，故再删一次。
     * 日志不动（pi 同源：日志留着，副本删掉）。
     */
    private void dropTrailingRetryableAssistant(LaneState lane) {
        var messages = lane.messages;
        if (messages.isEmpty()) {
            return;
        }
        if (messages.get(messages.size() - 1) instanceof Message.AssistantMessage last
                && ("error".equals(last.stopReason()) || "length".equals(last.stopReason()))) {
            messages.remove(messages.size() - 1);
        }
    }

    /**
     * Record the compaction in the lane's record log (docs/21 D5).
     *
     * <p>A mid-run compaction is just another step of the running operation,
     * so it only appends a {@code StepAttempt(COMPACTION)}. Storage rejects a
     * second open operation per lane, so opening one here would corrupt the
     * session — an idle compaction, which has no enclosing operation, emits
     * the full started/step/finished triple instead.</p>
     */
    private void emitCompactionRecords(String laneName, LaneState lane, String reason,
                                       String resultEntryId, long durationMs) {
        String entryId = resultEntryId == null ? "" : resultEntryId;
        if (lane.isRunning()) {
            lane.records.add(compactionAttempt(laneName, lane, lane.runId, reason, entryId, durationMs));
            return;
        }
        String opId = UUID.randomUUID().toString();
        lane.records.add(new LaneRecord.OperationStarted(opId, 0, laneName, null,
            HarnessUtils.lastEntryId(lane),
            new LaneRecord.OperationStarted.Compaction(null, entryId)));
        lane.records.add(compactionAttempt(laneName, lane, opId, reason, entryId, durationMs));
        lane.records.add(new LaneRecord.OperationFinished(
            UUID.randomUUID().toString(), 0, laneName, null, opId,
            OperationOutcome.COMPLETED, null, durationMs));
    }

    private static LaneRecord.StepAttempt compactionAttempt(String laneName, LaneState lane,
                                                           String runId, String reason,
                                                           String resultEntryId, long durationMs) {
        // Attempts are numbered per (run, step) series and must be consecutive.
        // Nothing enforces that any more (the record-log fold was retired,
        // docs/30); the run summary reads these numbers, so a gap would still
        // misreport the step count.
        int attempt = 0;
        for (var record : lane.records) {
            if (record instanceof LaneRecord.StepAttempt step
                    && step.step() == StepKind.COMPACTION
                    && java.util.Objects.equals(step.runId(), runId)) {
                attempt++;
            }
        }
        return new LaneRecord.StepAttempt(
            UUID.randomUUID().toString(), 0, laneName, null, runId,
            StepKind.COMPACTION, attempt, resultEntryId, reason,
            null, null, null, null, durationMs);
    }

    /** 压缩体产物：新转录列表 + 结果对象（{@code estimatedTokensAfter} 由调用方在重建后补）。 */
    private record Built(List<Entry> kept, CompactionResult result) {}

    /**
     * 压缩体：跑摘要生成器 + 装配新的转录列表。
     *
     * <p><b>摘要请求的宿主跨度</b>（{@code docs/31 §8.29}）：摘要是一次**真正的
     * LLM 调用**，但它的 payload 行（{@code llm.payload.request/response}，由
     * {@code PayloadRecordingStreamFn} 发出）此前**没有归属** —— 压缩路径从不
     * {@code pushCurrent}，行上既无 {@code traceId} 也无 {@code spanId}。这里补
     * {@code compaction.summary} 子跨度（父 = {@code compaction.apply}）并在摘要
     * 生成期间绑定为当前跨度，与 {@code PiLaneSink.beginRequest} 对主循环请求做的
     * 是同一件事。</p>
     *
     * <p>三点须留意：① <b>不</b>复用 {@code llm.request} 名 —— 否则「按名数
     * {@code llm.request} = agent 轮数」这条既有聚合口径静默失效；② 生成器的重试
     * 环（3d 环 B）在<b>同一条</b>跨度下发生，重试的每次请求行都绑到它，从行数看得见
     * 重试；③ 非 LLM 的截断兜底生成器（{@code SummaryGenerator.truncating()}）没有
     * LLM 调用，这条跨度仍然出现但只有 {@code summaryChars}、没有事件行与 token ——
     * 跨度描述的是「摘要这一步」，不是「一定发生了一次请求」。</p>
     */
    private Built compactTranscript(LaneState lane, CompactionSettings settings, String reason,
                                    TelemetrySpan parent) {
        var summarySpan = parent.openSpan(new SpanOptions("compaction.summary",
            java.util.Map.of("reason", reason)));
        CompactionResult result;
        try {
            ctx.telemetry().pushCurrent(summarySpan);
            // tokensBefore 单一来源：pi 的三条路（threshold/manual/overflow）都从
            // prepareCompaction :667 的 estimateContextTokens 读，这里同形 —— 落库的
            // Entry.Compaction.tokensBefore 因此是「用量优先」值，与触发判据同源。
            // reason 透传给摘要生成器（3d 环 B 的 attempt_start 事件装饰）。
            result = CompactionService.compact(lane.transcript, settings,
                ctx.summaryGenerator(), contextTokens(lane), reason);
            summarySpan.addAttribute("summaryChars", result.summary().length());
            var usage = result.usage();
            if (usage != null) {
                summarySpan.addAttribute("inputTokens", usage.input());
                summarySpan.addAttribute("outputTokens", usage.output());
            }
        } finally {
            // 与 PiLaneSink.endRequest 同序：先 close 再 pop。
            summarySpan.close();
            ctx.telemetry().popCurrent(summarySpan);
        }
        var retainedTail = keptMessagesFrom(lane.transcript, result.firstKeptEntryId());
        var compactionEntry = new Entry.Compaction(
            UUID.randomUUID().toString(), lane.nextSeq(), HarnessUtils.lastEntryId(lane),
            Instant.now(), result.summary(), result.firstKeptEntryId(),
            retainedTail, (int) result.tokensBefore(), result.details(), result.usage());
        var kept = new ArrayList<Entry>();
        String firstKept = result.firstKeptEntryId();
        boolean seen = false;
        for (var entry : lane.transcript) {
            if (seen) {
                kept.add(entry);
            } else if (entry.id().equals(firstKept)) {
                kept.add(entry);
                seen = true;
            }
        }
        kept.add(0, compactionEntry);
        return new Built(kept, result);
    }

    private static List<Message> keptMessagesFrom(List<Entry> transcript, String firstKeptId) {
        List<Message> kept = new ArrayList<>();
        boolean seen = false;
        for (var entry : transcript) {
            if (entry.id().equals(firstKeptId)) {
                seen = true;
            }
            if (seen && entry instanceof Entry.Message msg) {
                kept.add(msg.message());
            }
        }
        return kept;
    }
}
