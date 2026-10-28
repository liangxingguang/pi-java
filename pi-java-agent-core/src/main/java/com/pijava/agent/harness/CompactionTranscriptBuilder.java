package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.compaction.CompactionService;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.SuppliedCompaction;
import com.pijava.ai.message.Message;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.TelemetrySpan;

/**
 * {@link CompactionExecutor} 的**转录装配簇**抽离（结构债拆分：行为零变化纯移动，
 * javadoc/注释逐字随代码搬）——压缩体（摘要生成＋新转录装配）、扩展供应臂与两个
 * 共用装配助手。
 *
 * <p>{@code tokensBefore} 的估算回呼 executor 的包私有 {@code contextTokens}
 * （与移动前同源同值）。</p>
 */
final class CompactionTranscriptBuilder {

    private final ExecutionContext ctx;
    private final CompactionExecutor executor;

    CompactionTranscriptBuilder(ExecutionContext ctx, CompactionExecutor executor) {
        this.ctx = ctx;
        this.executor = executor;
    }

    /** 压缩体产物：新转录列表 + 结果对象（{@code estimatedTokensAfter} 由调用方在重建后补）。 */
    record Built(List<Entry> kept, CompactionResult result) {}

    /**
     * 压缩体：跑摘要生成器 + 装配新的转录列表。
     *
     * <p><b>摘要请求的宿主跨度</b>（{@code 原 docs/31 §8.29}）：摘要是一次**真正的
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
    Built compactTranscript(LaneState lane, CompactionSettings settings, String reason,
                            TelemetrySpan parent, String customInstructions) {
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
                ctx.summaryGenerator(), executor.contextTokens(lane), reason, customInstructions);
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
            // fromHook=null ≙ pi undefined（pi-generated；扩展摘要的 fromHook=true
            // 写路在 suppliedTranscript，docs/15）。
            retainedTail, (int) result.tokensBefore(), result.details(), result.usage(), null);
        return new Built(assembleKept(lane.transcript, result.firstKeptEntryId(),
            compactionEntry), result);
    }

    /**
     * pi 的「扩展供应压缩」臂（docs/15 裁决⑥，agent-session.ts:2813-2836/3145-3167）：
     * 跳过摘要生成器，直接以供应产物装配新转录；{@code fromHook=true}（pi
     * {@code appendCompaction(..., fromExtension, ...)} 的同位）。{@code tokensBefore}
     * 以扩展值为准（pi 同）；{@code estimatedTokensAfter} 由调用方在重建后补（与
     * 默认路同一来源，pi :2383）。
     */
    Built suppliedTranscript(LaneState lane, SuppliedCompaction supplied) {
        var result = new CompactionResult(supplied.summary(), supplied.firstKeptEntryId(),
            supplied.tokensBefore(), null, supplied.usage(), supplied.details());
        var compactionEntry = new Entry.Compaction(
            UUID.randomUUID().toString(), lane.nextSeq(), HarnessUtils.lastEntryId(lane),
            Instant.now(), supplied.summary(), supplied.firstKeptEntryId(),
            keptMessagesFrom(lane.transcript, supplied.firstKeptEntryId()),
            (int) supplied.tokensBefore(), supplied.details(), supplied.usage(), true);
        return new Built(assembleKept(lane.transcript, supplied.firstKeptEntryId(),
            compactionEntry), result);
    }

    /**
     * 装配新转录：压缩标记置头＋第一条保留条目起的后缀（{@link #compactTranscript}
     * 与 {@link #suppliedTranscript} 共用）。firstKeptId 找不到 ⇒ 自留压缩（仅标记）。
     */
    private static List<Entry> assembleKept(List<Entry> transcript, String firstKeptId,
                                            Entry.Compaction marker) {
        var kept = new ArrayList<Entry>();
        boolean seen = false;
        for (var entry : transcript) {
            if (seen) {
                kept.add(entry);
            } else if (entry.id().equals(firstKeptId)) {
                kept.add(entry);
                seen = true;
            }
        }
        kept.add(0, marker);
        return kept;
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
