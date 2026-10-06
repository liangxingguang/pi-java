package com.pijava.agent.context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.ContextEntries;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B169（{@code docs/17}）：{@code estimateProjectedContextTokens} —— pi
 * {@code compaction.ts:226-262}。用量锚点早于后来的 context_edit/compaction 时，
 * 不再信任那个用量，按投影消息纯字符重算。
 */
class EstimateProjectedContextTokensTest {

    @Test
    void trustsUsageWhenNoEditFollowsIt() {
        var branch = builder()
            .user("e1", "prompt")
            .assistantWithTotal("e2", 1000)
            .user("e3", "more")
            .build();

        var estimate = ContextUsageEstimator.estimateProjectedContextTokens(
            ContextEntries.projectEntries(branch), branch);

        assertThat(estimate.lastUsageIndex()).isNotNull();
        assertThat(estimate.tokens())
            .as("用量条目在最后（无 edit 跟随）⇒ 信任 provider 用量 + 尾部字符估")
            .isGreaterThanOrEqualTo(1000);
    }

    @Test
    void recomputesFromCharsWhenAnEditFollowsTheUsage() {
        var branch = builder()
            .user("e1", "prompt")
            .assistantWithTotal("e2", 1000)
            .user("e3", "more")
            // 孤儿 omission edit（目标不存在，不改变投影消息），但它晚于用量 ⇒
            // pi 规定用量可能已失真，必须纯字符重算。
            .omission("e4", "ghost")
            .build();

        var estimate = ContextUsageEstimator.estimateProjectedContextTokens(
            ContextEntries.projectEntries(branch), branch);

        assertThat(estimate.lastUsageIndex())
            .as("失效重算后没有用量锚点")
            .isNull();
        assertThat(estimate.tokens())
            .as("纯字符重算 ≈ 4，绝不能再报 1000")
            .isEqualTo(4);
    }

    // ── helpers ────────────────────────────────────────────────

    private static Builder builder() {
        return new Builder();
    }

    /** 串 parentId 链的分支构造器（投影沿叶子路径构建）。 */
    private static final class Builder {
        private final List<Entry> entries = new ArrayList<>();
        private String lastId;

        Builder user(String id, String text) {
            entries.add(new Entry.Message(id, 0, lastId, Instant.EPOCH,
                new Message.UserMessage(List.of(new ContentBlock.TextContent(text))),
                false));
            lastId = id;
            return this;
        }

        Builder assistantWithTotal(String id, double totalTokens) {
            var usage = new Usage(5, 5, 0, 0, null, null, totalTokens, Usage.Cost.zero());
            entries.add(new Entry.Message(id, 0, lastId, Instant.EPOCH,
                new Message.AssistantMessage(
                    List.of(new ContentBlock.TextContent("hi")), "stop", null,
                    null, null, null, usage, Instant.EPOCH, null, null),
                false));
            lastId = id;
            return this;
        }

        Builder omission(String id, String targetId) {
            entries.add(new Entry.ContextEdit(id, 0, lastId, Instant.EPOCH, targetId, null));
            lastId = id;
            return this;
        }

        List<Entry> build() {
            return List.copyOf(entries);
        }
    }
}
