package com.pijava.agent.session.jsonl;

import java.time.Instant;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.UsageCause;
import com.pijava.agent.session.SessionMutation;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旧转录的停因词表迁移（B109，{@code 原 docs/56 §6 R2/R3}）。
 *
 * <p>B109 之前归一化停因写作 {@code "tool_use"}，此后与 pi 同字面量写作 {@code "toolUse"}；
 * 而 {@code ~/.pi-java} 下有数千个旧会话文件，助手消息与 usage 审计记录两条路径都带着旧值。
 * 两条**读**路径都必须归一，否则外来字面量会从旧会话漏到对外面（遥测 span 属性、终局帧、
 * RPC 转录、HTML 导出）。</p>
 *
 * <p>夹具是**字节级**的：先用编码器造一行合法的线，再把归一化停因降级回旧写法 ——
 * 那正是旧文件的字节。刻意不走「编码器写、解码器读」的往返：那只会读到自己刚写的新值，
 * 对迁移零判别力。</p>
 */
class StopReasonMigrationTest {

    private static final String LEGACY = "\"stopReason\":\"tool_use\"";
    private static final String CURRENT = "\"stopReason\":\"toolUse\"";

    /** 造一行合法的助手消息线，并把归一化停因降级回旧写法（即旧文件的字节）。 */
    private static String legacyAssistantLine(Message.AssistantMessage message) {
        return JsonlCodec.encodeMutation(new SessionMutation.Entry(null,
            new Entry.Message("e-legacy", 1L, null, Instant.ofEpochMilli(1L), message, null)))
            .replace(CURRENT, LEGACY);
    }

    /**
     * 夹具自检：这一行必须**真的**带旧字节，否则整条用例是空转（会静默变成
     * 「读到自己刚写的新值」那种零判别力的往返）。只对降级目标为 {@code toolUse} 的输入调用。
     */
    private static String downgraded(String line) {
        assertThat(line)
            .as("夹具必须真的产出旧字节（否则整条用例是空转）：%s", line)
            .contains(LEGACY);
        return line;
    }

    private static Entry parseEntry(String line) {
        // 本夹具造的都是**文件的第 1 条** mutation ⇒ 行号 1（pi 的行没有 seq，见 docs/12）。
        var result = JsonlCodec.parseMutation(line, 1L);
        assertThat(result.ok()).as("parse should succeed: %s", result.error()).isTrue();
        return ((SessionMutation.Entry) result.value()).entry();
    }

    private static String stopReasonOf(Entry entry) {
        return ((Message.AssistantMessage) ((Entry.Message) entry).message()).stopReason();
    }

    private static String rawStopReasonOf(Entry entry) {
        return ((Message.AssistantMessage) ((Entry.Message) entry).message()).rawStopReason();
    }

    @Test
    void legacyAssistantStopReasonIsMigratedOnRead() {
        var message = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("done")),
            "toolUse", null, null, null, null, null, Instant.ofEpochMilli(1L), null, null);

        assertThat(stopReasonOf(parseEntry(downgraded(legacyAssistantLine(message)))))
            .isEqualTo("toolUse");
    }

    /**
     * 旧文件里的 usage 是**记录族**的一行（D6 之后 usage 已提为一等 entry）——
     * 装载时必须转换，且 {@code stopReason} 一样要归一。
     *
     * <p>夹具是手写的旧行字节：{@code kind:"record"} ＋ {@code type:"usage"} ＋
     * 旧字面量的 {@code stopReason}。走编码器造不出这个形状（现在的编码器只会写 entry）。</p>
     */
    @Test
    void legacyUsageRecordIsConvertedToAnEntryAndItsStopReasonIsMigrated() {
        String line = "{\"kind\":\"record\",\"seq\":2,\"id\":\"r-legacy\",\"lane\":\"main\","
            + "\"timestamp\":1,\"type\":\"usage\",\"cause\":\"assistant\","
            + "\"usage\":{\"input\":1,\"output\":1,\"cacheRead\":0,\"cacheWrite\":0,"
            + "\"totalTokens\":2,\"cost\":{\"input\":0,\"output\":0,\"cacheRead\":0,"
            + "\"cacheWrite\":0,\"total\":0}},"
            + "\"runId\":\"run-1\",\"entryId\":\"e-legacy\",\"attempt\":0,"
            + "\"stopReason\":\"tool_use\"}";

        var result = JsonlCodec.parseMutation(line, 2L);
        assertThat(result.ok()).as("parse should succeed: %s", result.error()).isTrue();
        var parsed = (Entry.Usage) ((SessionMutation.Entry) result.value()).entry();
        assertThat(parsed.id()).isEqualTo("r-legacy");
        assertThat(parsed.kind()).as("记录族的 cause 变成 pi 的 kind").isEqualTo("assistant");
        assertThat(parsed.usage().input()).isEqualTo(1);
        assertThat(parsed.stopReason())
            .as("旧字面量必须被垫片归一（B109），哪怕是走转换路径进来的")
            .isEqualTo("toolUse");
    }

    /**
     * ★ 本文件最重要的一条：垫片只认**归一化**那一个键。
     *
     * <p>{@code rawStopReason} 是 provider 的线格原值，Anthropic 真会发 {@code "tool_use"} ——
     * 它**必须原样保留**，归一它会伪造出「线格原值 = 归一值」的假相等，
     * 那正是 B20 的 D5 包专门钉住的区别。</p>
     */
    @Test
    void rawStopReasonIsNotMigrated() {
        var message = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("done")),
            "toolUse", null, null, null, null, null, Instant.ofEpochMilli(1L), null, "tool_use");

        var entry = parseEntry(downgraded(legacyAssistantLine(message)));
        assertThat(stopReasonOf(entry)).isEqualTo("toolUse");
        assertThat(rawStopReasonOf(entry)).isEqualTo("tool_use");
    }

    @Test
    void currentStopReasonIsIdempotent() {
        var message = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("done")),
            "toolUse", null, null, null, null, null, Instant.ofEpochMilli(1L), null, null);
        var line = JsonlCodec.encodeMutation(new SessionMutation.Entry(null,
            new Entry.Message("e-new", 1L, null, Instant.ofEpochMilli(1L), message, null)));

        assertThat(stopReasonOf(parseEntry(line))).isEqualTo("toolUse");
    }

    @Test
    void otherStopReasonsAndAbsenceAreUntouched() {
        for (String reason : List.of("stop", "length", "error", "aborted", "deferred", "pending")) {
            var message = new Message.AssistantMessage(
                List.of(new ContentBlock.TextContent("done")),
                reason, null, null, null, null, null, Instant.ofEpochMilli(1L), null, null);
            var entry = parseEntry(legacyAssistantLine(message));
            assertThat(stopReasonOf(entry)).as("stopReason=%s 不该被动", reason).isEqualTo(reason);
        }

        var bare = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("done")),
            null, null, null, null, null, null, Instant.ofEpochMilli(1L), null, null);
        assertThat(stopReasonOf(parseEntry(legacyAssistantLine(bare)))).isNull();
    }
}
