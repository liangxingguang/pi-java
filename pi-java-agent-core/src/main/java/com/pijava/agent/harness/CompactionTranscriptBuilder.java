package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.compaction.CompactionService;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.compaction.SummaryGenerator;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.SuppliedCompaction;
import com.pijava.agent.session.ContextEntries;
import com.pijava.ai.api.Transcripts;
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
     * <p>三点须留意：① 摘要这一步本身<b>不</b>复用 {@code llm.request} 名（聚合口径
     * 保留 {@code compaction.summary} 这一层）；② 生成器的重试环（3d 环 B）此前所有
     * 尝试都落在同一条跨度下，D320 起<b>每次尝试</b>各开一个 {@code llm.request}
     * 子跨度（{@code kind=summary}）并把负载行绑到它，重试的次数/时序从跨度看得见；
     * ③ 非 LLM 的截断兜底生成器（{@code SummaryGenerator.truncating()}）没有 LLM
     * 调用，这条跨度仍然出现但只有 {@code summaryChars}、没有事件行与 token ——
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
                ctx.summaryGenerator(), executor.contextTokens(lane), reason, customInstructions,
                attemptSpans(summarySpan));
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
        // 批 33（#8）：压缩点的完整系统提示与工具状态（pi appendCompaction:1270-1283）；
        // entry 与 systemMessage 共用同一个 Instant（pi 把 systemMessage.timestamp 覆盖为
        // compaction 时刻）。
        Instant now = Instant.now();
        var systemMessage = currentSystemMessage(lane.transcript, now);
        var compactionEntry = new Entry.Compaction(
            UUID.randomUUID().toString(), lane.nextSeq(), HarnessUtils.lastEntryId(lane),
            now, result.summary(), result.firstKeptEntryId(),
            // fromHook=null ≙ pi undefined（pi-generated；扩展摘要的 fromHook=true
            // 写路在 suppliedTranscript，docs/15）。
            retainedTail, (int) result.tokensBefore(), result.details(), result.usage(), null,
            systemMessage);
        return new Built(assembleKept(lane.transcript, result.firstKeptEntryId(),
            compactionEntry), result);
    }

    /**
     * D320：每次摘要尝试一个 {@code llm.request} 子跨度（{@code kind=summary}），
     * 绑定为当前跨度后再跑请求，负载行因此归属到<b>那一次</b>尝试；跨度收尾时带上
     * 本次尝试的 token 与停因。父跨度是 {@code compaction.summary}。
     */
    private SummaryGenerator.AttemptSpans attemptSpans(TelemetrySpan summarySpan) {
        return (attempt, attemptReason, body) ->
            summarySpan.startSpan(new SpanOptions("llm.request",
                attemptAttributes(attempt, attemptReason)), span -> {
                ctx.telemetry().pushCurrent(span);
                try {
                    var response = body.get();
                    var usage = response.usage();
                    if (usage != null) {
                        span.addAttribute("inputTokens", usage.input());
                        span.addAttribute("outputTokens", usage.output());
                    }
                    if (response.stopReason() != null) {
                        span.addAttribute("stopReason", response.stopReason().wireName());
                    }
                    return response;
                } finally {
                    ctx.telemetry().popCurrent(span);
                }
            });
    }

    private static java.util.Map<String, Object> attemptAttributes(int attempt, String reason) {
        var attrs = new LinkedHashMap<String, Object>();
        attrs.put("attempt", attempt);
        attrs.put("kind", "summary");
        if (reason != null) {
            attrs.put("reason", reason);
        }
        return attrs;
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
        Instant now = Instant.now();
        var compactionEntry = new Entry.Compaction(
            UUID.randomUUID().toString(), lane.nextSeq(), HarnessUtils.lastEntryId(lane),
            now, supplied.summary(), supplied.firstKeptEntryId(),
            keptMessagesFrom(lane.transcript, supplied.firstKeptEntryId()),
            (int) supplied.tokensBefore(), supplied.details(), supplied.usage(), true,
            currentSystemMessage(lane.transcript, now));
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

    /**
     * 批 33（#8）：压缩点的系统提示快照。{@link Transcripts#getCurrentSystemMessage}
     * 已经逐字实现 pi {@code getCurrentSystemMessage}（内容按 {@code "\n\n"} 拼接、
     * sections 按名覆盖、toolsAdded 取当前工具、两者皆无为 {@code null}）；这里
     * 只把 timestamp 覆盖为 compaction 时刻（pi {@code session-manager.ts:1283}）。
     */
    private static Message.SystemMessage currentSystemMessage(List<Entry> transcript,
                                                              Instant timestamp) {
        var current = Transcripts.getCurrentSystemMessage(ContextEntries.toMessages(transcript));
        return current == null ? null
            : new Message.SystemMessage(current.content(), timestamp,
                current.sections(), current.toolsAdded(), current.toolsRemoved());
    }
}
