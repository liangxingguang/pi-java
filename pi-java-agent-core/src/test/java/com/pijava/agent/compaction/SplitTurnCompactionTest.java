package com.pijava.agent.compaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B171（{@code docs/18}）：切点落在一轮内部时，pi 切两段摘要 —— 历史走到本轮
 * user 之前，本轮 user→切点走 {@code TURN_PREFIX_SUMMARIZATION_PROMPT} 第二次调用，
 * 再按固定字面量逐字合并，usage 经 {@code combineUsage} 相加。
 *
 * <p>所有剧本用 {@code keepRecentTokens=7}：尾部两条短消息（各 ~2 token）凑不满，
 * 加上 40 字符的助手/用户（10 token）才越界，切点因此落在该条上。条目照 pi 夹具
 * 串 parentId 链（投影沿叶子路径构建）。</p>
 */
class SplitTurnCompactionTest {

    private static final CompactionSettings SETTINGS =
        new CompactionSettings(true, 16_384, 7);

    /** 两路脚本：记录各自收到的消息与 previousSummary，返回固定结果。 */
    private static final class ScriptedGenerator implements SummaryGenerator {
        final List<Message> history = new ArrayList<>();
        final List<Message> prefix = new ArrayList<>();
        final List<String> previousSummaries = new ArrayList<>();
        private final SummaryResult historyResult;
        private final SummaryResult prefixResult;
        private final RuntimeException prefixFailure;

        ScriptedGenerator(SummaryResult historyResult, SummaryResult prefixResult) {
            this(historyResult, prefixResult, null);
        }

        ScriptedGenerator(SummaryResult historyResult, SummaryResult prefixResult,
                          RuntimeException prefixFailure) {
            this.historyResult = historyResult;
            this.prefixResult = prefixResult;
            this.prefixFailure = prefixFailure;
        }

        @Override
        public SummaryResult summarize(List<Message> compressed, String previousSummary,
                                       String customInstructions, int reserveTokens, String reason) {
            history.addAll(compressed);
            previousSummaries.add(previousSummary);
            return historyResult;
        }

        @Override
        public SummaryResult summarizeTurnPrefix(List<Message> messages, int reserveTokens,
                                                  String reason) {
            prefix.addAll(messages);
            if (prefixFailure != null) {
                throw prefixFailure;
            }
            return prefixResult;
        }
    }

    @Test
    void mergesTheTwoSummariesAndTheirUsage() {
        var transcript = splitTurnTranscript();
        var generator = new ScriptedGenerator(
            new SummaryResult("HIST", Usage.of(10, 5)),
            new SummaryResult("PREF", Usage.of(20, 3)));

        var result = CompactionService.compact(transcript, SETTINGS, generator, 42L, "overflow");

        assertThat(result.summary())
            .as("pi compaction.ts:1026 的逐字合并格式")
            .isEqualTo("HIST\n\n---\n\n**Turn Context (split turn):**\n\nPREF");
        assertThat(result.usage().input()).isEqualTo(30);
        assertThat(result.usage().output()).isEqualTo(8);
        assertThat(result.usage().totalTokens()).isEqualTo(38);
    }

    @Test
    void splitsTheMessagesAtTheTurnStart() {
        var transcript = splitTurnTranscript();
        var generator = new ScriptedGenerator(
            new SummaryResult("HIST", null), new SummaryResult("PREF", null));

        var result = CompactionService.compact(transcript, SETTINGS, generator, 42L, "overflow");

        assertThat(textsOf(generator.history))
            .as("历史截至本轮 user 之前")
            .containsExactly("history q", "history a");
        assertThat(textsOf(generator.prefix))
            .as("turn prefix = 本轮 user（切点助手自身保留，不在 prefix 里）")
            .containsExactly("current request text");
        assertThat(generator.previousSummaries)
            .as("无更早 compaction ⇒ previousSummary=null")
            .singleElement().isNull();
        assertThat(result.firstKeptEntryId()).isEqualTo("e4");
    }

    @Test
    void noEarlierHistoryUsesTheNoPriorHistoryLiteral() {
        // 第一条就是本轮 user，切点在其后的助手：历史为空、不发历史调用，pi 用
        // 固定文案 "No prior history." 作为合并前缀。
        var transcript = builder()
            .user("e1", "current request text")
            .assistant("e2", "P".repeat(40))
            .user("e3", "tail q")
            .assistant("e4", "tail a")
            .build();
        var generator = new ScriptedGenerator(
            new SummaryResult("UNUSED", null), new SummaryResult("PREF", null));

        var result = CompactionService.compact(transcript, SETTINGS, generator, 42L, "overflow");

        assertThat(generator.history)
            .as("历史为空 ⇒ 不发历史摘要调用")
            .isEmpty();
        assertThat(result.summary())
            .isEqualTo("No prior history.\n\n---\n\n**Turn Context (split turn):**\n\nPREF");
    }

    @Test
    void cutOnAUserMessageIsNotASplitTurn() {
        // 40 字符的条目是 user：切点落在它上面即一轮开头 ⇒ startsTurn ⇒ 不 split，
        // 只发一次历史摘要，无 prefix。
        var transcript = builder()
            .user("e1", "old")
            .assistant("e2", "old a")
            .user("e3", "P".repeat(40))
            .assistant("e4", "tail a")
            .build();
        var generator = new ScriptedGenerator(
            new SummaryResult("HIST", null), new SummaryResult("PREF", null));

        var result = CompactionService.compact(transcript, SETTINGS, generator, 42L, "threshold");

        assertThat(textsOf(generator.history)).containsExactly("old", "old a");
        assertThat(generator.prefix).isEmpty();
        assertThat(result.firstKeptEntryId()).isEqualTo("e3");
        assertThat(result.summary()).isEqualTo("HIST");
    }

    @Test
    void turnPrefixFailurePropagates() {
        var transcript = splitTurnTranscript();
        var generator = new ScriptedGenerator(
            new SummaryResult("HIST", null), null,
            new IllegalStateException("Turn prefix summarization failed: boom"));

        assertThatThrownBy(() -> CompactionService.compact(
            transcript, SETTINGS, generator, 42L, "overflow"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Turn prefix summarization failed: boom");
    }

    @Test
    void readToolCallInTheTurnPrefixReachesTheFileLists() {
        // prefix 区间 [本轮 user, 切点前助手] 里的助手发出 read 调用 —— 该文件要进
        // <read-files> 与 details（pi prepareCompaction 对 turnPrefix 补抽 fileOps）。
        var transcript = builder()
            .user("e1", "history q")
            .assistant("e2", "history a")
            .user("e3", "do it")
            .assistantToolCall("e4", "read", "/a.txt")
            .assistant("e5", "P".repeat(40))
            .user("e6", "tail q")
            .assistant("e7", "tail a")
            .build();
        var generator = new ScriptedGenerator(
            new SummaryResult("HIST", null), new SummaryResult("PREF", null));

        var result = CompactionService.compact(transcript, SETTINGS, generator, 42L, "overflow");

        assertThat(result.summary())
            .endsWith("\n\n<read-files>\n/a.txt\n</read-files>");
        assertThat((List<?>) result.details().get("readFiles"))
            .containsExactly("/a.txt");
    }

    // ── fixtures ───────────────────────────────────────────────

    /** 标准 split-turn 剧本：切点 e4（40 字符助手），本轮 e3→e4，历史 e1/e2。 */
    private static List<Entry> splitTurnTranscript() {
        return builder()
            .user("e1", "history q")
            .assistant("e2", "history a")
            .user("e3", "current request text")
            .assistant("e4", "P".repeat(40))
            .user("e5", "tail q")
            .assistant("e6", "tail a")
            .build();
    }

    private static Builder builder() {
        return new Builder();
    }

    /** 串 parentId 链的转录构造器（对应 pi compaction 夹具的 lastId 形状）。 */
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

        Builder assistant(String id, String text) {
            entries.add(new Entry.Message(id, 0, lastId, Instant.EPOCH,
                new Message.AssistantMessage(
                    List.of(new ContentBlock.TextContent(text)), "stop", null,
                    null, null, null, null, Instant.EPOCH, null, null),
                false));
            lastId = id;
            return this;
        }

        Builder assistantToolCall(String id, String name, String path) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("path", path);
            entries.add(new Entry.Message(id, 0, lastId, Instant.EPOCH,
                new Message.AssistantMessage(
                    List.of(new ContentBlock.ToolUseContent(id + "-call", name, arguments)),
                    "stop", null, null, null, null, null, Instant.EPOCH, null, null),
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
            for (var block : message.content()) {
                if (block instanceof ContentBlock.TextContent text) {
                    texts.add(text.text());
                }
            }
        }
        return texts;
    }
}
