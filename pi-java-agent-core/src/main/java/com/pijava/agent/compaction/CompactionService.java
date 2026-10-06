package com.pijava.agent.compaction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.pijava.agent.context.ContextUsageEstimator;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.ContextEntries;
import com.pijava.agent.session.ContextEntries.ProjectedEntry;
import com.pijava.ai.Usage;
import com.pijava.ai.message.Message;

/**
 * Compaction v2: "summary + retained tail" (aligned with pi
 * {@code prepareCompaction}/{@code findProjectedCutPoint},
 * {@code compaction.ts:803-917}). Replaces the Phase 2 truncate-and-count service.
 *
 * <p><b>B169（{@code docs/17}）</b>：切点、摘要输入、文件清单全部读 context_edit
 * 后的<b>投影</b>。投影按「源条目 × 投影消息」分组（{@link ContextEntries#projectEntries}）：
 * 被 omission 的消息贡献 0、不进摘要；切点只从投影消息里挑；切完再向后吸收
 * 「被剔除助手 + 其 edit」闭后缀、向前吸收相邻 context-invisible 元数据。</p>
 *
 * <p>非紧凑形状（无合法切点 / 切点前没有可摘要消息）经 {@link #prepare} 判为
 * {@code null} —— 对应 pi {@code prepareCompaction} 的 {@code undefined}：自动路径
 * 静默不压，手动路径抛错。旧实现「硬走兜底切点、只留最后一条」是本仓发明，已撤。</p>
 *
 * <p><b>B171（{@code docs/18}）</b>：切点落在一轮内部（切点助手、其前有本轮
 * user）时，历史摘要截至本轮 user 之前，本轮 user→切点走
 * {@link SummaryGenerator#summarizeTurnPrefix} 第二次调用，两段摘要按固定字面量
 * 合并、usage 相加。上一份 compaction 的 {@code summary} 同时作为
 * {@code previousSummary} 接通（pi :901-906）。</p>
 */
public final class CompactionService {

    private CompactionService() {}

    /**
     * 压缩前的确定性规划（pi {@code CompactionPreparation} 的无事件部分）。
     *
     * @param projected       源条目分组投影
     * @param boundaryStart   上一份 compaction 之后的起点
     * @param cut             第一条保留条目在投影中的下标
     * @param toSummarize     cut 之前要进历史摘要的投影消息（已剔除 system）
     * @param turnPrefix      split turn 时本轮 user→切点之间的投影消息
     * @param splitTurn       切点是否落在一轮内部（B171）
     * @param previousSummary 上一份 compaction 的 summary（无则 null）
     * @param firstKeptId     第一条保留条目的 id
     */
    public record Plan(
        List<ProjectedEntry> projected,
        int boundaryStart,
        int cut,
        List<Message> toSummarize,
        List<Message> turnPrefix,
        boolean splitTurn,
        String previousSummary,
        String firstKeptId
    ) {}

    /**
     * pi 的 {@code prepareCompaction}（{@code compaction.ts:872-960}）：投影 →
     * 上一份 compaction 边界 → 切点 → 摘要消息。任一步不成立 ⇒ {@code null}
     * （pi 返回 {@code undefined}）。
     */
    public static Plan prepare(List<Entry> transcript, CompactionSettings settings) {
        var projected = ContextEntries.projectEntries(transcript);

        // 最新的、投影非空的 compaction 是上一份摘要（pi :893-906）；它保留段里
        // 可能残留的更旧 compaction 标记投影为空，不算。它的 summary 作为
        // previousSummary，boundaryStart = 其后一位。
        int boundaryStart = 0;
        String previousSummary = null;
        for (int i = 0; i < projected.size(); i++) {
            if (projected.get(i).source() instanceof Entry.Compaction previous
                    && !projected.get(i).messages().isEmpty()) {
                boundaryStart = i + 1;
                previousSummary = previous.summary();
            }
        }

        CutPoint cutPoint = findCutPoint(projected, boundaryStart, settings.keepRecentTokens());
        int cut = cutPoint.index();
        int historyEnd = cutPoint.splitTurn() ? cutPoint.turnStartIndex() : cut;
        List<Message> toSummarize = compactionMessages(projected, boundaryStart, historyEnd);
        List<Message> turnPrefix = cutPoint.splitTurn()
            ? compactionMessages(projected, cutPoint.turnStartIndex(), cut)
            : List.of();
        // pi：两份消息皆空才返回 undefined（compaction.ts:925）。
        if (toSummarize.isEmpty() && turnPrefix.isEmpty()) {
            return null;
        }
        return new Plan(projected, boundaryStart, cut,
            List.copyOf(toSummarize), List.copyOf(turnPrefix),
            cutPoint.splitTurn(), previousSummary, projected.get(cut).source().id());
    }

    /**
     * pi {@code getMessagesFromProjectedEntryForCompaction}（{@code compaction.ts:98-102}）：
     * 收集投影 {@code [from, to)} 的对话消息 —— compaction 源不重复摘要，
     * system 消息是 prompt state 不进对话。
     */
    private static List<Message> compactionMessages(List<ProjectedEntry> projected,
                                                     int from, int to) {
        List<Message> messages = new ArrayList<>();
        for (int i = from; i < to; i++) {
            if (projected.get(i).source() instanceof Entry.Compaction) {
                continue;
            }
            for (Message message : projected.get(i).messages()) {
                if (!(message instanceof Message.SystemMessage)) {
                    messages.add(message);
                }
            }
        }
        return messages;
    }

    /**
     * Compact a transcript.
     *
     * <p>判据/切点都来自 {@link #prepare}；{@code null}（pi 的空路径）抛
     * {@link IllegalStateException}。{@code tokensBefore} 由调用方从投影上下文
     * 算好传入（pi {@code preparation.tokensBefore}）。</p>
     *
     * @param summaryGenerator generates the summary of the discarded prefix
     * @param tokensBefore    压缩前的上下文估算（pi {@code preparation.tokensBefore}）
     * @param reason          触发原因（"manual"/"threshold"/"overflow"），透传给摘要
     *                        生成器做环 B 事件装饰（3d）；可为 null
     * @param customInstructions pi {@code compact(customInstructions)}：追加到历史摘要
     *                        prompt 的聚焦指令（B174，{@code docs/23}）；turn-prefix
     *                        调用不接；自动压缩路恒 null；可为 null
     */
    public static CompactionResult compact(List<Entry> transcript,
                                           CompactionSettings settings,
                                           SummaryGenerator summaryGenerator,
                                           long tokensBefore, String reason,
                                           String customInstructions) {
        Plan plan = prepare(transcript, settings);
        if (plan == null) {
            throw new IllegalStateException("Nothing to compact: transcript too small");
        }

        String summary;
        Usage usage;
        if (plan.splitTurn() && !plan.turnPrefix().isEmpty()) {
            // B171（docs/18，pi compaction.ts:992-1029）：无更早历史时不发历史
            // 调用，historyText 取 previousSummary 或固定 "No prior history."。
            String historyText = plan.previousSummary() != null
                ? plan.previousSummary() : "No prior history.";
            Usage historyUsage = null;
            if (!plan.toSummarize().isEmpty()) {
                var historyResult = summaryGenerator.summarize(
                    plan.toSummarize(), plan.previousSummary(), customInstructions,
                    settings.reserveTokens(), reason);
                historyText = historyResult.text();
                historyUsage = historyResult.usage();
            }
            var prefixResult = summaryGenerator.summarizeTurnPrefix(
                plan.turnPrefix(), settings.reserveTokens(), reason);
            summary = historyText
                + "\n\n---\n\n**Turn Context (split turn):**\n\n"
                + prefixResult.text();
            usage = historyUsage != null
                ? UsageCombiner.combine(historyUsage, prefixResult.usage())
                : prefixResult.usage();
        } else {
            var result = summaryGenerator.summarize(
                plan.toSummarize(), plan.previousSummary(), customInstructions,
                settings.reserveTokens(), reason);
            summary = result.text();
            usage = result.usage();
        }

        // 文件清单（B2，原 docs/31 §8.30）：pi 在摘要文本尾部追加两块，并把同一份
        // 清单落进 details。fileOps 在 pi 覆盖 messagesToSummarize + turnPrefix
        // 两份消息（compaction.ts:927-935），这里合并后交给同一条 collect。
        List<Message> fileOpsMessages = new ArrayList<>(plan.toSummarize());
        fileOpsMessages.addAll(plan.turnPrefix());
        CompactionFiles.Lists lists = CompactionFiles.collect(fileOpsMessages, transcript);
        return new CompactionResult(
            summary + lists.formatted(), plan.firstKeptId(), tokensBefore, null,
            usage, lists.details());
    }

    /** 旧式调用（测试）：{@code reason = customInstructions = null}。 */
    public static CompactionResult compact(List<Entry> transcript,
                                           CompactionSettings settings,
                                           SummaryGenerator summaryGenerator,
                                           long tokensBefore) {
        return compact(transcript, settings, summaryGenerator, tokensBefore, null, null);
    }

    /** 无 customInstructions 的旧式调用（测试）。 */
    public static CompactionResult compact(List<Entry> transcript,
                                           CompactionSettings settings,
                                           SummaryGenerator summaryGenerator,
                                           long tokensBefore, String reason) {
        return compact(transcript, settings, summaryGenerator, tokensBefore, reason, null);
    }

    /**
     * pi {@code findProjectedCutPoint}（{@code compaction.ts:803-870}）。
     *
     * <p>先收集合法切点（投影出 user/assistant 类消息；compaction 源不是切点，
     * toolResult 不能切）；再从新到旧累加投影消息的 {@code estimateTokens}，达
     * {@code keepRecentTokens} 时取该位置或其后第一个合法切点；随后两段修正：
     * 闭后缀（recovery omission）推进、相邻元数据回吸。</p>
     */
    private static CutPoint findCutPoint(List<ProjectedEntry> entries, int start,
                                         int keepRecentTokens) {
        List<Integer> cutPoints = new ArrayList<>();
        for (int i = start; i < entries.size(); i++) {
            ProjectedEntry entry = entries.get(i);
            if (!(entry.source() instanceof Entry.Compaction)
                    && entry.messages().stream().anyMatch(CompactionService::isCutPointMessage)) {
                cutPoints.add(i);
            }
        }
        if (cutPoints.isEmpty()) {
            // pi：没有合法切点 ⇒ firstKept = start（随后 prepare 因摘要为空返回 null）。
            return new CutPoint(start, -1, false);
        }

        long accumulated = 0;
        boolean exceededBudget = false;
        int cutIndex = cutPoints.getFirst();
        for (int i = entries.size() - 1; i >= start; i--) {
            long messageTokens = 0;
            for (Message message : entries.get(i).messages()) {
                messageTokens += ContextUsageEstimator.estimateTokens(message);
            }
            if (messageTokens == 0) {
                continue;
            }
            accumulated += messageTokens;
            if (accumulated >= keepRecentTokens) {
                exceededBudget = true;
                int reached = i;
                cutIndex = cutPoints.stream()
                    .filter(candidate -> candidate >= reached)
                    .findFirst()
                    .orElse(cutPoints.getLast());
                break;
            }
        }

        if (exceededBudget) {
            cutIndex = advanceOverRecoveryOmissionSuffix(entries, cutIndex);
        }

        // 向前吸收相邻 context-invisible 元数据（pi :862-866）。判据读源条目的
        // 本征可见性（不经 edit、不经本仓的停因过滤）—— error 助手在 pi 侧本征
        // 可见，吸收到它前面就要停住。
        while (cutIndex > start) {
            ProjectedEntry previous = entries.get(cutIndex - 1);
            // pi 判据读**投影**消息（previous.messages.length > 0）：本征可见但已被
            // omission 的条目投影为空，照样回吸。
            if (previous.source() instanceof Entry.Compaction
                    || !previous.messages().isEmpty()) {
                break;
            }
            cutIndex--;
        }
        // pi compaction.ts:863-869：切点消息开轮 ⇒ 非 split；否则向前找本轮
        // 起点，找不到也非 split。
        boolean startsTurn = isProjectedTurnStart(entries.get(cutIndex));
        int turnStartIndex = startsTurn
            ? -1 : findProjectedTurnStartIndex(entries, cutIndex, start);
        return new CutPoint(cutIndex, turnStartIndex,
            !startsTurn && turnStartIndex != -1);
    }

    /**
     * 切点结果（pi {@code CutPointResult}，{@code compaction.ts:775-779}）。
     *
     * @param index          第一条保留条目在投影中的下标
     * @param turnStartIndex split turn 时本轮起点下标；非 split 为 -1
     * @param splitTurn      切点是否落在一轮内部
     */
    private record CutPoint(int index, int turnStartIndex, boolean splitTurn) {}

    /** pi {@code isProjectedTurnStart}（{@code compaction.ts:790-793}）。 */
    private static boolean isProjectedTurnStart(ProjectedEntry entry) {
        if (entry.source() instanceof Entry.Compaction) {
            return false;
        }
        return entry.messages().stream().anyMatch(CompactionService::isTurnStartMessage);
    }

    /** pi {@code findProjectedTurnStartIndex}（{@code compaction.ts:795-800}）。 */
    private static int findProjectedTurnStartIndex(List<ProjectedEntry> entries,
                                                   int entryIndex, int startIndex) {
        for (int i = entryIndex; i >= startIndex; i--) {
            if (isProjectedTurnStart(entries.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** pi {@code isTurnStartMessage}：只有 user 类消息开轮（assistant/toolResult 不开）。 */
    private static boolean isTurnStartMessage(Message message) {
        return message instanceof Message.UserMessage;
    }

    /**
     * pi {@code isRecoveryOmissionSuffix}（{@code compaction.ts:833-860}：切点后面
     * 是「被 omission 的助手 + 其 edit」构成的闭后缀时，切点推进一格 —— 让恢复
     * 尝试落在丢弃侧而不是保留侧。
     */
    private static int advanceOverRecoveryOmissionSuffix(List<ProjectedEntry> entries,
                                                         int cutIndex) {
        Set<String> omittedSuffixIds = new HashSet<>();
        for (int i = cutIndex + 1; i < entries.size(); i++) {
            if (isOmitted(entries.get(i))) {
                omittedSuffixIds.add(entries.get(i).source().id());
            }
        }
        boolean hasExternalReplacement = false;
        for (int i = cutIndex + 1; i < entries.size(); i++) {
            if (entries.get(i).source() instanceof Entry.ContextEdit edit
                    && edit.replacement() != null
                    && !omittedSuffixIds.contains(edit.targetId())) {
                hasExternalReplacement = true;
            }
        }

        boolean hasOmittedAssistant = false;
        boolean allSuffixEntriesClosed = true;
        for (int i = cutIndex + 1; i < entries.size(); i++) {
            Entry source = entries.get(i).source();
            if (source instanceof Entry.Message mm
                    && mm.message() instanceof Message.AssistantMessage
                    && isOmitted(entries.get(i))) {
                hasOmittedAssistant = true;
            }
            if (source instanceof Entry.Compaction
                    || (intrinsicallyVisible(source) && !isOmitted(entries.get(i)))) {
                allSuffixEntriesClosed = false;
            }
        }
        if (!hasExternalReplacement && hasOmittedAssistant && allSuffixEntriesClosed) {
            return cutIndex + 1;
        }
        return cutIndex;
    }

    /** pi {@code isOmitted}：源条目本征可见、投影消息为空（即被 omission edit 剔除）。 */
    private static boolean isOmitted(ProjectedEntry entry) {
        return intrinsicallyVisible(entry.source()) && entry.messages().isEmpty();
    }

    /**
     * pi {@code isIntrinsicallyVisible}（{@code compaction.ts:836-837}：源条目不经
     * edit 时是否产出上下文消息。
     */
    private static boolean intrinsicallyVisible(Entry entry) {
        return !(entry instanceof Entry.ContextEdit)
            && (entry instanceof Entry.Message
                || entry instanceof Entry.CustomMessage
                || (entry instanceof Entry.BranchSummary branch && branch.summary() != null)
                || entry instanceof Entry.Compaction);
    }

    /**
     * pi {@code isCutPointMessage}（{@code compaction.ts:362-378}）：user 及其同类
     * （custom/bashExecution/摘要消息在 Java 都投影成 UserMessage）与 assistant
     * 是合法切点；toolResult/system 不是。
     */
    private static boolean isCutPointMessage(Message message) {
        return message instanceof Message.UserMessage
            || message instanceof Message.AssistantMessage;
    }
}
