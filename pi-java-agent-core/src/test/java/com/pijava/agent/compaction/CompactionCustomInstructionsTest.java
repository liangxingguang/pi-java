package com.pijava.agent.compaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B174（{@code docs/23}）：{@code CompactionService.compact} 把
 * customInstructions 透传给历史摘要调用 —— 普通路与 split-turn 历史路都传
 * （pi {@code compaction.ts:1005}）。turn-prefix 调用不接该参数。
 */
class CompactionCustomInstructionsTest {

    /** 记录每次 summarize 收到的 customInstructions。 */
    private static final class Recording implements SummaryGenerator {
        final List<String> summarizeCustom = new ArrayList<>();
        final List<String> prefixCustom = new ArrayList<>();

        @Override
        public SummaryResult summarize(List<Message> compressed, String previousSummary,
                                       String customInstructions, int reserveTokens,
                                       String reason) {
            summarizeCustom.add(customInstructions);
            return new SummaryResult("SUMMARY", null);
        }

        @Override
        public SummaryResult summarizeTurnPrefix(List<Message> messages, int reserveTokens,
                                                 String reason) {
            // 签名上没有 customInstructions 位置 —— 记录位仅为对照断言。
            prefixCustom.add(null);
            return new SummaryResult("PREFIX", null);
        }
    }

    /**
     * 普通（非 split）路：切点是开轮 user 时历史摘要经 summarize 一次调用。
     */
    @Test
    void normalPathForwardsCustomInstructionsToSummarize() {
        var recording = new Recording();
        // [user1, assistant1, user2]：keep=1 ⇒ 切点落在最新 user2（开轮，非 split），
        // user1/assistant1 进历史摘要。
        var entries = List.of(
            entry("e1", null, user("first")),
            entry("e2", "e1", assistant("ok")),
            entry("e3", "e2", user("next")));

        CompactionService.compact(entries,
            new CompactionSettings(true, 16_384, 1), recording, 42L,
            "manual", "Focus on auth");

        assertThat(recording.summarizeCustom)
            .as("pi compact：customInstructions 透传进历史摘要")
            .containsExactly("Focus on auth");
        assertThat(recording.prefixCustom).isEmpty();
    }

    /**
     * split-turn 路：历史摘要与 turn-prefix 各一次调用，customInstructions 只进历史。
     */
    @Test
    void splitTurnForwardsCustomInstructionsToHistorySummarizeOnly() {
        var recording = new Recording();
        // [user1, assistant1, user2, assistant2]：切点在 assistant2（split），
        // 历史=[user1, assistant1]，turn-prefix=[user2]。
        var entries = List.of(
            entry("e1", null, user("first")),
            entry("e2", "e1", assistant("ok")),
            entry("e3", "e2", user("second")),
            entry("e4", "e3", assistant("done")));

        CompactionService.compact(entries,
            new CompactionSettings(true, 16_384, 1), recording, 42L,
            "manual", "Focus on auth");

        assertThat(recording.summarizeCustom)
            .as("pi compaction.ts:1005：split 历史摘要同样透传")
            .containsExactly("Focus on auth");
        assertThat(recording.prefixCustom).hasSize(1);
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message assistant(String text) {
        return new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Entry entry(String id, String parentId, Message message) {
        return new Entry.Message(id, 0, parentId, Instant.now(), message, false);
    }
}
