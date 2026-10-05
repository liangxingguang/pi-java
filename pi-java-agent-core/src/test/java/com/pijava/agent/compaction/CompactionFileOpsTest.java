package com.pijava.agent.compaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩产物的文件清单（B2，{@code 原 docs/31 §8.30}）。
 *
 * <p>钉的是 pi 的两样产物：摘要**文本**尾部的 {@code <read-files>}/{@code <modified-files>}
 * 两块，与落库 {@code Entry.Compaction.details} 的两个键（{@code utils.ts:62-72} 与
 * {@code compaction.ts:951-962}）。清单由 toolCall 块**确定性**抽出，从不问模型 ——
 * 所以这里没有一条断言依赖模型输出，全是逐字节与集合断言。</p>
 */
class CompactionFileOpsTest {

    /** keepRecentTokens=1 ⇒ 只保最后一条消息，前面全进摘要（切点算法不是本夹具的对象）。 */
    private static final CompactionSettings KEEP_LAST =
        new CompactionSettings(true, 16_384, 1);

    private static final AtomicInteger CALLS = new AtomicInteger();

    // ---------------------------------------------------------------- 夹具

    private static Entry entry(Message message) {
        return new Entry.Message(UUID.randomUUID().toString(), 0, null, Instant.now(), message, false);
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message assistantText(String text) {
        return new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static ContentBlock call(String toolName, String path) {
        return new ContentBlock.ToolUseContent(
            "call-" + CALLS.incrementAndGet(), toolName, Map.of("path", path));
    }

    private static Message assistantCalling(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks));
    }

    /** 摘要文本原样返回，便于把注意力放在尾部块上。 */
    private static SummaryGenerator fixed(String text) {
        return (compressed, previousSummary, customInstructions, reserveTokens, reason) ->
            new SummaryGenerator.SummaryResult(text, null);
    }

    /** 压缩一段 `[assistant 的工具调用] + [收尾消息]` 的转录（沿 parentId 链）。 */
    private static CompactionResult compact(SummaryGenerator generator, Message... messages) {
        return CompactionService.compact(chained(messages), KEEP_LAST, generator, 100L);
    }

    /** 消息按序串成 parentId 链（投影沿叶子路径构建，root 列表只会看见最后一条）。 */
    private static List<Entry> chained(Message... messages) {
        List<Entry> transcript = new ArrayList<>();
        String parent = null;
        for (Message message : messages) {
            String id = UUID.randomUUID().toString();
            transcript.add(new Entry.Message(id, 0, parent, Instant.now(), message, false));
            parent = id;
        }
        return transcript;
    }

    /** 在 parent 之后追加一条消息条目，返回它的 id。 */
    private static String append(List<Entry> transcript, String parent, Message message) {
        String id = UUID.randomUUID().toString();
        transcript.add(new Entry.Message(id, 0, parent, Instant.now(), message, false));
        return id;
    }

    private static List<String> strings(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return ((List<?>) value).stream().map(String::valueOf).toList();
    }

    // ---------------------------------------------------------------- 抽取

    @Test
    void readWriteAndEditLandInTheirOwnSets() {
        var result = compact(fixed("SUMMARY"),
            user("first"),
            assistantCalling(call("read", "a.ts"), call("write", "b.ts"), call("edit", "c.ts")),
            user("second"));
        assertThat(strings(result.details().get("readFiles"))).containsExactly("a.ts");
        assertThat(strings(result.details().get("modifiedFiles"))).containsExactly("b.ts", "c.ts");
    }

    @Test
    void aPathBothReadAndEditedCountsAsModifiedOnly() {
        // pi computeFileLists（utils.ts:56）：readFiles = read ∖ (edited ∪ written)。
        var result = compact(fixed("SUMMARY"),
            user("first"),
            assistantCalling(call("read", "a.ts"), call("edit", "a.ts")),
            user("second"));
        assertThat(strings(result.details().get("readFiles"))).isEmpty();
        assertThat(strings(result.details().get("modifiedFiles"))).containsExactly("a.ts");
    }

    @Test
    void repeatedCallsDedupeAndSort() {
        var result = compact(fixed("SUMMARY"),
            user("first"),
            assistantCalling(call("read", "b.ts"), call("read", "a.ts")),
            assistantCalling(call("read", "a.ts"), call("edit", "z.ts"), call("edit", "m.ts")),
            user("second"));
        assertThat(strings(result.details().get("readFiles"))).containsExactly("a.ts", "b.ts");
        assertThat(strings(result.details().get("modifiedFiles"))).containsExactly("m.ts", "z.ts");
    }

    @Test
    void otherToolNamesAreNeverRecorded() {
        // pi 的 switch 只认 read/write/edit —— 有 path 参数也不算数。
        var result = compact(fixed("SUMMARY"),
            user("first"),
            assistantCalling(call("bash", "a.ts"), call("glob", "b.ts"), call("grep", "c.ts")),
            user("second"));
        assertThat(strings(result.details().get("readFiles"))).isEmpty();
        assertThat(strings(result.details().get("modifiedFiles"))).isEmpty();
    }

    @Test
    void emptyPathIsSkipped() {
        // pi `if (!path) continue`：空串是 falsy，同样跳过。
        var result = compact(fixed("SUMMARY"),
            user("first"),
            assistantCalling(call("read", "")),
            user("second"));
        assertThat(strings(result.details().get("readFiles"))).isEmpty();
    }

    // ---------------------------------------------------------------- 文本与形状

    @Test
    void summaryTailIsByteExact() {
        // utils.ts:62-72：每块自带 \n\n 前缀、块间没有额外分隔。
        var result = compact(fixed("SUMMARY"),
            user("first"),
            assistantCalling(call("read", "a.ts"), call("write", "z.ts"), call("edit", "m.ts")),
            user("second"));
        assertThat(result.summary()).isEqualTo("SUMMARY"
            + "\n\n<read-files>\na.ts\n</read-files>"
            + "\n\n<modified-files>\nm.ts\nz.ts\n</modified-files>");
    }

    @Test
    void noFileOpsKeepsTheSummaryUntouchedAndStillWritesBothKeys() {
        var result = compact(fixed("SUMMARY"), user("first"), assistantText("ok"), user("second"));
        assertThat(result.summary()).isEqualTo("SUMMARY");
        // 两键恒在、数组可为空 —— 不是 null，也不是缺键（pi 从不写 null）。
        assertThat(result.details()).containsOnlyKeys("readFiles", "modifiedFiles");
        assertThat(strings(result.details().get("readFiles"))).isEmpty();
        assertThat(strings(result.details().get("modifiedFiles"))).isEmpty();
    }

    @Test
    void detailsKeyOrderMatchesPi() {
        // LinkedHashMap 的目的就在这一条：Map.of/Map.copyOf 的迭代序不保证，
        // 落盘后与 pi 的 {"readFiles":…,"modifiedFiles":…} 不可逐字节比对。
        var result = compact(fixed("SUMMARY"),
            user("first"),
            assistantCalling(call("read", "a.ts")),
            user("second"));
        assertThat(result.details().keySet()).containsExactly("readFiles", "modifiedFiles");
    }

    // ---------------------------------------------------------------- 跨压缩累积

    @Test
    void fileOpsCarryOverAcrossSuccessiveCompactions() {
        // 第一次压缩：a.ts 被读。
        List<Entry> first = chained(
            user("first"),
            assistantCalling(call("read", "a.ts")),
            user("second"),
            assistantText("ok"));
        var firstResult = CompactionService.compact(first, KEEP_LAST, fixed("FIRST"), 100L);
        assertThat(strings(firstResult.details().get("readFiles"))).containsExactly("a.ts");

        // 第二次压缩的转录：上一份 compaction marker 打头（CompactionExecutor:423 的位置），
        // 丢弃段里**没有**任何 read —— 清单只能来自回灌。其后条目沿链挂在 marker 之后。
        var marker = new Entry.Compaction(UUID.randomUUID().toString(), 0, null, Instant.now(),
            firstResult.summary(), firstResult.firstKeptEntryId(), List.of(),
            (int) firstResult.tokensBefore(), firstResult.details(), null);
        // 保留段首条目必须沿用原始 id（marker.firstKeptEntryId 指向它），只重挂 parent。
        var kept = (Entry.Message) first.get(3);
        var relinked = new Entry.Message(kept.id(), 0, marker.id(),
            kept.timestamp(), kept.message(), kept.terminate());
        List<Entry> second = new ArrayList<>();
        second.add(marker);
        second.add(relinked);
        String userThird = append(second, kept.id(), user("third"));
        append(second, userThird, assistantText("done"));

        var secondResult = CompactionService.compact(second, KEEP_LAST, fixed("SECOND"), 100L);
        assertThat(strings(secondResult.details().get("readFiles")))
            .as("a.ts 只出现在上一份 compaction 的 details 里，必须经回灌留下")
            .containsExactly("a.ts");
        assertThat(secondResult.summary())
            .isEqualTo("SECOND\n\n<read-files>\na.ts\n</read-files>");
    }

    @Test
    void malformedPreviousDetailsIsIgnored() {
        // pi 的形状守卫（compaction.ts:54-68）：非列表、非字符串元素一律跳过，不抛。
        var marker = new Entry.Compaction(UUID.randomUUID().toString(), 0, null, Instant.now(),
            "previous", "someone", List.of(), 0,
            Map.of("readFiles", "not-a-list", "modifiedFiles", List.of(1, "b.ts")), null);
        List<Entry> transcript = new ArrayList<>();
        transcript.add(marker);
        String p1 = append(transcript, marker.id(), user("first"));
        String p2 = append(transcript, p1, assistantText("ok"));
        append(transcript, p2, user("second"));

        var result = CompactionService.compact(transcript, KEEP_LAST, fixed("X"), 100L);
        assertThat(strings(result.details().get("readFiles"))).isEmpty();
        assertThat(strings(result.details().get("modifiedFiles"))).containsExactly("b.ts");
    }
}
