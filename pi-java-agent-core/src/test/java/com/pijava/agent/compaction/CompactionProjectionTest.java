package com.pijava.agent.compaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B169（{@code docs/17}）：压缩切点与摘要输入必须读 context_edit 后的投影。
 *
 * <p>pi 锚点 {@code 200387122} 的生产路径是 {@code prepareCompaction} →
 * {@code buildSessionProjection} → {@code findProjectedCutPoint}：被 omission 的
 * 消息不进累加、不进摘要；切点候选只从投影消息里挑；相邻元数据条目回吸进丢弃段。
 * 测试条目照 pi 夹具的样子串 parentId 链（投影沿叶子路径构建）。</p>
 */
class CompactionProjectionTest {

    /** 记录送进摘要生成器的消息（pi 的 messagesToSummarize）。 */
    private static final class CapturingGenerator implements SummaryGenerator {
        final List<Message> seen = new ArrayList<>();

        @Override
        public SummaryResult summarize(List<Message> compressed, String previousSummary,
                                       String customInstructions, int reserveTokens, String reason) {
            seen.addAll(compressed);
            return new SummaryResult("summary", null);
        }

        @Override
        public SummaryResult summarizeTurnPrefix(List<Message> messages, int reserveTokens,
                                                 String reason) {
            seen.addAll(messages);
            return new SummaryResult("prefix", null);
        }
    }

    @Test
    void omittedAssistantTextDoesNotReachTheSummaryGenerator() {
        // 失败助手带着不该外传的文本，被持久 omission edit 剔除；切点在它后面的
        // user 上。pi 的摘要请求只看投影 —— 被剔除文本不许过桥。
        var transcript = builder()
            .user("e1", "first")
            .assistant("e2", "FAILED-SECRET-TEXT", "error")
            .omission("e3", "e2")
            .user("e4", "second")
            .assistant("e5", "ok", "stop")
            .build();

        var generator = new CapturingGenerator();
        CompactionService.compact(transcript,
            new CompactionSettings(true, 16_384, 3), generator, 42L, "overflow");

        assertThat(generator.seen)
            .as("被 omission 的失败助手不进摘要请求")
            .singleElement()
            .isInstanceOf(Message.UserMessage.class);
        assertThat(textsOf(generator.seen)).doesNotContain("FAILED-SECRET-TEXT");
    }

    @Test
    void omittedAssistantMovesCutToTheOlderMessage() {
        // 被 omission 的助手原文很大（100 字符）：旧实现按原文累加，切点会落在它
        // 上面；pi 按投影（贡献 0），预算在更老的 user 上才凑满。
        var transcript = builder()
            .user("e1", "ancient one")
            .user("e2", "old")
            .assistant("e3", "x".repeat(100), "error")
            .omission("e4", "e3")
            .user("e5", "tail")
            .assistant("e6", "done", "stop")
            .build();

        var result = CompactionService.compact(transcript,
            new CompactionSettings(true, 16_384, 3),
            SummaryGenerator.truncating(), 42L, "overflow");

        assertThat(result.firstKeptEntryId())
            .as("投影后切点落在 e2；按原文会错误地落在被剔除的 e3")
            .isEqualTo("e2");
    }

    @Test
    void transcriptWithOnlyToolResultsIsNotCompactable() {
        // pi findProjectedCutPoint：投影里没有合法切点（toolResult 不许切）⇒
        // prepareCompaction 返回 undefined，而不是硬走「只留最后一条」的发明切点。
        var onlyToolResult = builder().toolResult("e1").build();

        assertThatThrownBy(() -> CompactionService.compact(onlyToolResult,
            CompactionSettings.defaults(), SummaryGenerator.truncating(), 7L, "manual"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recoveryOmissionSuffixAdvancesTheCut() {
        // pi findProjectedCutPoint 的闭后缀规则：预算在 e2 凑满，其后是「被剔除的
        // 失败助手 + 它的 omission edit」闭后缀 ⇒ cut 推进到失败助手的位置，让 e2
        // 进摘要（落在 cut 之前），而不是留在保留段。
        var transcript = builder()
            .user("e1", "first")
            .user("e2", "hit")
            .assistant("e3", "boom", "error")
            .omission("e4", "e3")
            .build();

        var generator = new CapturingGenerator();
        var result = CompactionService.compact(transcript,
            new CompactionSettings(true, 16_384, 1), generator, 42L, "overflow");

        assertThat(result.firstKeptEntryId()).isEqualTo("e3");
        assertThat(textsOf(generator.seen)).contains("first", "hit");
        assertThat(textsOf(generator.seen))
            .as("被剔除的失败文本本身仍不进摘要")
            .doesNotContain("boom");
    }

    @Test
    void adjacentMetadataEntriesAreAbsorbedIntoTheDiscardedPrefix() {
        // pi：切点向前吸收相邻的 context-invisible 元数据条目。model_change 不进
        // 模型上下文，边界落在它上面（它被丢弃），而不是落在后面的 user 上。
        var transcript = builder()
            .user("e1", "old")
            .modelChange("e2")
            .user("e3", "tail")
            .build();

        var result = CompactionService.compact(transcript,
            new CompactionSettings(true, 16_384, 1),
            SummaryGenerator.truncating(), 42L, "threshold");

        assertThat(result.firstKeptEntryId())
            .as("相邻的 model_change 元数据要回吸进丢弃段")
            .isEqualTo("e2");
    }

    @Test
    void omittedAssistantBeforeTheCutIsAbsorbedIntoTheDiscardedPrefix() {
        // pi 回吸判据读**投影**消息（previous.messages.length > 0）：error 助手被
        // omission 后投影为空，即使它「本征可见」也要回吸进丢弃段，切点落在它的
        // 源条目上而不是落在它的 edit 上。
        var transcript = builder()
            .user("e1", "old")
            .assistant("e2", "bad", "error")
            .omission("e3", "e2")
            .user("e4", "tail")
            .build();

        var result = CompactionService.compact(transcript,
            new CompactionSettings(true, 16_384, 1),
            SummaryGenerator.truncating(), 42L, "threshold");

        assertThat(result.firstKeptEntryId())
            .as("被 omission 的 error 助手投影为空，回吸进丢弃段")
            .isEqualTo("e2");
    }

    // ── helpers ────────────────────────────────────────────────

    private static Builder builder() {
        return new Builder();
    }

    /** 串 parentId 链的转录构造器（对应 pi compaction.test.ts 的 lastId 夹具）。 */
    private static final class Builder {
        private final List<Entry> entries = new ArrayList<>();
        private String lastId;

        Builder user(String id, String text) {
            entries.add(new Entry.Message(id, 0, lastId, Instant.EPOCH,
                new Message.UserMessage(List.of(new ContentBlock.TextContent(text)), Instant.EPOCH),
                false));
            lastId = id;
            return this;
        }

        Builder assistant(String id, String text, String stopReason) {
            entries.add(new Entry.Message(id, 0, lastId, Instant.EPOCH,
                new Message.AssistantMessage(
                    List.of(new ContentBlock.TextContent(text)), stopReason, null,
                    null, null, null, null, Instant.EPOCH, null, null),
                false));
            lastId = id;
            return this;
        }

        Builder omission(String id, String targetId) {
            entries.add(new Entry.ContextEdit(id, 0, lastId, Instant.EPOCH, targetId, null));
            lastId = id;
            return this;
        }

        Builder modelChange(String id) {
            entries.add(new Entry.ModelChange(id, 0, lastId, Instant.EPOCH, "faux", "m"));
            lastId = id;
            return this;
        }

        Builder toolResult(String id) {
            entries.add(new Entry.Message(id, 0, lastId, Instant.EPOCH,
                new Message.ToolResultMessage(
                    "call-1", "read", List.of(new ContentBlock.TextContent("result")),
                    null, null, List.of(), false, Instant.EPOCH),
                false));
            lastId = id;
            return this;
        }

        List<Entry> build() {
            return List.copyOf(entries);
        }
    }

    private static List<String> textsOf(List<Message> messages) {
        List<String> texts = new ArrayList<>();
        for (var message : messages) {
            for (var block : allBlocks(message)) {
                if (block instanceof ContentBlock.TextContent text) {
                    texts.add(text.text());
                }
            }
        }
        return texts;
    }

    private static List<ContentBlock> allBlocks(Message message) {
        return switch (message) {
            case Message.UserMessage user -> user.content();
            case Message.AssistantMessage assistant -> assistant.content();
            case Message.ToolResultMessage toolResult -> toolResult.content();
            case Message.SystemMessage system -> system.content();
        };
    }
}
