package com.pijava.agent.compaction;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;

/**
 * Compaction v2: "summary + retained tail" (aligned with pi
 * {@code compaction.ts}). Replaces the Phase 2 truncate-and-count service.
 *
 * <p>The cut point scans from the newest entry backward, accumulating
 * estimated tokens until {@code keepRecentTokens} is reached. Cuts only land
 * on user/assistant messages — never on tool results, which must follow their
 * tool call.</p>
 */
public final class CompactionService {

    private CompactionService() {}

    /**
     * Compact a transcript.
     *
     * <p>pi 的 {@code prepareCompaction} 只在**空路径**时不可压缩
     * （{@code compaction.ts:638}；单条消息同样可压 —— 切点就落在它上面），
     * 且 {@code tokensBefore} 由调用方从上下文消息（用量优先的
     * {@code estimateContextTokens}，{@code :667}）算好传入 —— Java 侧同一
     * 形状：判据/落库值都由 {@code CompactionExecutor.contextTokens} 提供，
     * 本函数不再自己发明字符估算。</p>
     *
     * @param transcript      the full transcript (oldest first)
     * @param settings        compaction settings
     * @param summaryGenerator generates the summary of the discarded prefix
     * @param tokensBefore    压缩前的上下文估算（pi {@code preparation.tokensBefore}）
     */
    public static CompactionResult compact(List<Entry> transcript,
                                           CompactionSettings settings,
                                           SummaryGenerator summaryGenerator,
                                           long tokensBefore) {
        if (transcript.isEmpty()) {
            throw new IllegalStateException("Nothing to compact: transcript too small");
        }
        int cut = findCutPoint(transcript, settings.keepRecentTokens());
        List<Entry> discarded = new ArrayList<>(transcript.subList(0, cut));
        List<Message> discardedMessages = discarded.stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> ((Entry.Message) e).message())
            .toList();
        SummaryGenerator.SummaryResult summaryResult = summaryGenerator
            .summarize(discardedMessages, null, null, settings.reserveTokens());
        String firstKept = transcript.get(cut).id();
        return new CompactionResult(
            summaryResult.text(), firstKept, tokensBefore, null,
            summaryResult.usage(), null);
    }

    /**
     * Compute the cut index: the oldest retained entry. Cuts land on a user
     * message (turn start), or an assistant message not followed by a tool
     * result so tool results always follow their tool calls.
     */
    static int findCutPoint(List<Entry> transcript, int keepRecentTokens) {
        long accumulated = 0;
        for (int i = transcript.size() - 1; i >= 0; i--) {
            // pi findCutPoint :387 读的就是**同一个** estimateTokens(message)
            // （ceil(chars/4)，含 toolCall 的 JSON 长度与图像常数）；非消息 entry
            // 在 pi 的累加里贡献 0（:386 `entry.type !== "message"` 直接 continue）。
            if (transcript.get(i) instanceof Entry.Message msg) {
                accumulated += com.pijava.agent.context.ContextUsageEstimator
                    .estimateTokens(msg.message());
            }
            if (accumulated >= keepRecentTokens) {
                return safeCut(transcript, i);
            }
        }
        // Threshold not reached: keep only the last user/assistant message so
        // compaction always makes progress on small transcripts.
        return safeCut(transcript, transcript.size() - 1);
    }

    private static int safeCut(List<Entry> transcript, int from) {
        for (int j = from; j >= 0; j--) {
            if (transcript.get(j) instanceof Entry.Message m) {
                String role = m.message().role();
                if ("user".equals(role)) {
                    return j;
                }
                if ("assistant".equals(role)) {
                    boolean followedByTool = j + 1 < transcript.size()
                        && transcript.get(j + 1) instanceof Entry.Message next
                        && "tool".equals(next.message().role());
                    if (!followedByTool) {
                        return j;
                    }
                }
            }
        }
        return 0;
    }

}
