package com.pijava.ai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.ai.message.Message;
import com.pijava.ai.message.MessageTexts;

/**
 * 包 A4a：pi 的 {@code sections} **删除态**（值 {@code null} ＝ 删掉具名段）在 Java 上的形状。
 *
 * <p>oracle 是 pi {@code packages/ai/test/system-message-replay.test.ts}：{@code :20-41}
 * 的转录（第 5 条的 {@code sections: { a: "<a>2</a>", b: null, c: "<c>1</c>" }）、
 * {@code :44-53} 的重放期望、{@code :86-98} 的两个渲染期望。本条包之前，Java 的
 * {@code Map<String,String>} 没有「值在场但为 null」这个状态，因此
 * {@code system-message-replay.test.ts} 的这两组断言在本仓是**被删改过的**
 * （{@code 原 docs/49 §9 R3①}、登记 L3）；本类把它们补回来。</p>
 */
class SystemMessageSectionsTest {

    private static final Instant TS_10 = Instant.ofEpochMilli(10);
    private static final Instant TS_14 = Instant.ofEpochMilli(14);

    // ── 形状：值可为 null，键不可 ─────────────────────────────────────

    /** pi 的类型是 {@code Record<string, string | null>}（{@code types.ts:501}）。 */
    @Test
    void sectionValuesMayBeNullToExpressRemoval() {
        var message = new Message.SystemMessage("base", TS_10,
            sections("a", "<a>1</a>", "b", null), List.of(), List.of());

        assertThat(message.sections()).containsEntry("b", null).hasSize(2);
        // 顺序是渲染序（pi 的 Object.values），删除项**留在原位**，不因为值是 null 被挪走
        assertThat(message.sections().keySet()).containsExactly("a", "b");
    }

    /** 键仍然是必填的：pi 的 `Record` 键来自 `Object.keys`，不存在 null 键。 */
    @Test
    void sectionNamesMayNotBeNull() {
        var withNullKey = new LinkedHashMap<String, String>();
        withNullKey.put(null, "x");

        assertThatNullPointerException().isThrownBy(() ->
            new Message.SystemMessage("", TS_10, withNullKey, List.of(), List.of()));
    }

    /** 表本身仍是不可变的（删除态不放松这条）。 */
    @Test
    void sectionsStayImmutableWithNullValues() {
        var source = sections("a", "<a>1</a>", "b", null);
        var message = new Message.SystemMessage("", TS_10, source, List.of(), List.of());

        source.put("c", "<c>1</c>");
        assertThat(message.sections()).doesNotContainKey("c");
    }

    // ── 渲染：完整提示**跳过**删除项 ─────────────────────────────────

    /** pi {@code text.ts:15-21} 的 {@code if (text !== null)}。 */
    @Test
    void removedSectionsDoNotRenderIntoThePrompt() {
        var leading = new Message.SystemMessage("base", TS_10,
            sections("a", "<a>1</a>", "b", null), List.of(), List.of());

        assertThat(MessageTexts.getSystemMessageText(leading)).isEqualTo("base\n\n<a>1</a>");
    }

    // ── 渲染：中途更新**渲染**删除帧（与上一条成对）──────────────────

    /** 删除帧与「空值帧」不同：空串段照发 {@code Updated}，只有 {@code null} 才是删除。 */
    @Test
    void anEmptySectionIsNotARemoval() {
        var update = new Message.SystemMessage("", TS_14,
            sections("a", "", "b", null), List.of(), List.of());

        assertThat(MessageTexts.renderSystemMessageUpdate(update)).isEqualTo(
            "Updated system prompt section \"a\":\n\n\n\n"
                + "Removed system prompt section \"b\".");
        // 而完整提示里两者**都**不产生文本
        assertThat(MessageTexts.getSystemMessageText(update)).isEmpty();
    }

    // ── 重放：null 删名 ──────────────────────────────────────────────
    //
    // pi `:44-53` 的逐字重放期望（`sections` 只剩 `a`/`c`）在 `TranscriptsTest`
    // （`replaysContentSectionsAndToolsIntoOneLeadingMessage`），`:91-97` 的逐字渲染期望在
    // `MessageTextsTest`（`rendersLaterUpdateFramedBySectionName`）—— 那两个类是本包恢复成
    // pi 逐字 oracle 的**主**夹具。本类只补齐它们覆盖不到的形状与顺序语义。

    /**
     * pi 的 {@code Map} 语义，两条都要在 Java 的 {@code LinkedHashMap} 上成立：
     * 覆盖**保留首次位置**，删掉再设**排到末尾**。
     */
    @Test
    void replayKeepsFirstPositionOnOverwriteAndMovesResetSectionsToTheEnd() {
        var context = new TranscriptContext(List.of(
            new Message.SystemMessage("", TS_10, sections("a", "1", "b", "1"), List.of(), List.of()),
            new Message.SystemMessage("", Instant.ofEpochMilli(11),
                sections("b", null, "c", "1"), List.of(), List.of()),
            new Message.SystemMessage("", Instant.ofEpochMilli(12),
                sections("b", "2"), List.of(), List.of())));

        // a 覆盖原位；b 被删过 ⇒ 重新出现时在末尾；c 在中间
        assertThat(Transcripts.getCurrentSystemMessage(context.messages()).sections().keySet())
            .containsExactly("a", "c", "b");
    }

    /** 只看重放：一条消息里同时删与设，**删除不影响同名之外的位置**。 */
    @Test
    void aRemovalInALaterMessageDoesNotDisturbOtherSections() {
        var context = new TranscriptContext(List.of(
            new Message.SystemMessage("", TS_10, sections("a", "1", "b", "1", "c", "1"),
                List.of(), List.of()),
            new Message.SystemMessage("", Instant.ofEpochMilli(11),
                sections("b", null), List.of(), List.of())));

        assertThat(Transcripts.getCurrentSystemMessage(context.messages()).sections().keySet())
            .containsExactly("a", "c");
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    /** 保序表（{@code Map.of} 的迭代顺序未定义，夹具一律显式保序）；偶数位可为 {@code null}。 */
    private static Map<String, String> sections(String... keyValues) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
