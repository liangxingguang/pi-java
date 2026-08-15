package com.pijava.agent.compaction;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
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
     * @param transcript      the full transcript (oldest first)
     * @param settings        compaction settings
     * @param summaryGenerator generates the summary of the discarded prefix
     */
    public static CompactionResult compact(List<Entry> transcript,
                                           CompactionSettings settings,
                                           SummaryGenerator summaryGenerator) {
        if (transcript.size() <= 1) {
            throw new IllegalStateException("Nothing to compact: transcript too small");
        }
        int cut = findCutPoint(transcript, settings.keepRecentTokens());
        List<Entry> discarded = new ArrayList<>(transcript.subList(0, cut));
        List<Message> discardedMessages = discarded.stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> ((Entry.Message) e).message())
            .toList();
        String summary = summaryGenerator
            .summarize(discardedMessages, null, null, settings.reserveTokens())
            .text();
        String firstKept = transcript.get(cut).id();
        long tokensBefore = estimateTokens(transcript);
        return new CompactionResult(
            summary, firstKept, tokensBefore, null, null, null);
    }

    /**
     * Compute the cut index: the oldest retained entry. Cuts land on a user
     * message (turn start), or an assistant message not followed by a tool
     * result so tool results always follow their tool calls.
     */
    static int findCutPoint(List<Entry> transcript, int keepRecentTokens) {
        long accumulated = 0;
        for (int i = transcript.size() - 1; i >= 0; i--) {
            accumulated += estimateTokens(transcript.get(i));
            if (accumulated >= keepRecentTokens) {
                for (int j = i; j >= 0; j--) {
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
        return 0;
    }

    /** Rough token estimate: ~4 chars per token over message text content. */
    public static int estimateTokens(List<Entry> entries) {
        long chars = 0;
        for (var entry : entries) {
            if (entry instanceof Entry.Message msg) {
                for (var block : msg.message().content()) {
                    chars += textOf(block).length();
                }
            }
        }
        return (int) (chars / 4);
    }

    private static long estimateTokens(Entry entry) {
        if (entry instanceof Entry.Message msg) {
            long chars = 0;
            for (var block : msg.message().content()) {
                chars += textOf(block).length();
            }
            return chars / 4;
        }
        return 0;
    }

    private static String textOf(ContentBlock block) {
        return switch (block) {
            case ContentBlock.TextContent t -> t.text();
            case ContentBlock.ThinkingContent t -> t.text();
            case ContentBlock.ImageContent i -> "";
            case ContentBlock.ToolUseContent t -> t.name() + " " + t.arguments();
            case ContentBlock.ToolResultContent t -> {
                StringBuilder sb = new StringBuilder();
                for (var inner : t.content()) {
                    sb.append(textOf(inner));
                }
                yield sb.toString();
            }
        };
    }
}