package com.pijava.ai.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * pi {@code packages/ai/test/system-message-replay.test.ts:86-98} 的移植：
 * {@code getSystemMessageText} / {@code renderSystemMessageUpdate} 的逐字期望值。
 *
 * <p>⚠️ 本条包之前本类是**被删改过的**：pi 的更新消息带 {@code sections: { b: null }}，
 * 而 java 的 {@code Map<String,String>} 表达不了「值在场且为 null」，故当时去掉了那一项，
 * 连带 pi 期望串里的 {@code 'Removed system prompt section "b".'} 也不存在
 * （{@code 原 docs/49 §9 R3①}、登记 L3）。包 A4a 给了删除态载体 ⇒ 本类恢复成 pi 的**逐字**
 * 移植（{@code 原 docs/52 §4.1}）。</p>
 */
class MessageTextsTest {

    // ── contentText（pi utils/text.ts:6-11）────────────────────────────

    @Test
    void joinsTextBlocksWithTheDefaultNewlineSeparator() {
        var content = List.<ContentBlock>of(
            new ContentBlock.TextContent("a"),
            new ContentBlock.ThinkingContent("ignored", null, false),
            new ContentBlock.TextContent("b"));

        assertThat(MessageTexts.contentText(content)).isEqualTo("a\nb");
        assertThat(MessageTexts.contentText(content, "|")).isEqualTo("a|b");
    }

    @Test
    void returnsEmptyTextForContentWithoutTextBlocks() {
        assertThat(MessageTexts.contentText(List.of())).isEmpty();
        assertThat(MessageTexts.contentText(
            List.of(new ContentBlock.ThinkingContent("t", null, false)))).isEmpty();
    }

    // ── getSystemMessageText（pi utils/text.ts:15-21）──────────────────

    /** pi {@code :90} —— 完整提示 = content 后接各 section 值。 */
    @Test
    void rendersLeadingPromptAsContentThenSections() {
        var leading = new Message.SystemMessage("base", Instant.ofEpochMilli(10),
            sections("a", "<a>1</a>", "b", "<b>1</b>"), List.of(), List.of());

        assertThat(MessageTexts.getSystemMessageText(leading))
            .isEqualTo("base\n\n<a>1</a>\n\n<b>1</b>");
    }

    /** pi {@code :82} —— 空 content 不产生前导 {@code "\n\n"}（空段被滤掉）。 */
    @Test
    void dropsEmptyPartsWhenRenderingThePrompt() {
        var patched = new Message.SystemMessage("", Instant.ofEpochMilli(2),
            sections("preamble", "You are pi."), List.of(), List.of());

        assertThat(MessageTexts.getSystemMessageText(patched)).isEqualTo("You are pi.");
    }

    // ── renderSystemMessageUpdate（pi utils/text.ts:23-40）─────────────

    /** pi {@code :91-97} —— 按名框住；**不过滤**空段（与上一个方法的关键差别）。 */
    @Test
    void rendersLaterUpdateFramedBySectionName() {
        var update = new Message.SystemMessage("", Instant.ofEpochMilli(14),
            sections("a", "<a>2</a>", "b", null, "c", "<c>1</c>"), List.of(), List.of());

        assertThat(MessageTexts.renderSystemMessageUpdate(update)).isEqualTo(
            "Updated system prompt section \"a\":\n\n<a>2</a>\n\n"
                + "Removed system prompt section \"b\".\n\n"
                + "Updated system prompt section \"c\":\n\n<c>1</c>");
    }

    /** ⚠️ 与 {@link #dropsEmptyPartsWhenRenderingThePrompt} 成对：同一个空段，两个渲染器答案不同。 */
    @Test
    void keepsEmptySectionValuesWhenRenderingAnUpdate() {
        var update = new Message.SystemMessage("", Instant.ofEpochMilli(1),
            sections("a", ""), List.of(), List.of());

        assertThat(MessageTexts.renderSystemMessageUpdate(update))
            .isEqualTo("Updated system prompt section \"a\":\n\n");
        assertThat(MessageTexts.getSystemMessageText(update)).isEmpty();
    }

    /** pi {@code :29} —— content 文本在前，section 帧在后，以 {@code "\n\n"} 连。 */
    @Test
    void putsContentTextBeforeSectionFramesInAnUpdate() {
        var update = new Message.SystemMessage("also do this", Instant.ofEpochMilli(12),
            sections("a", "<a>2</a>"), List.of(), List.of());

        assertThat(MessageTexts.renderSystemMessageUpdate(update))
            .isEqualTo("also do this\n\nUpdated system prompt section \"a\":\n\n<a>2</a>");
    }

    /** 插入顺序的 section 表（{@code Map.of} 的迭代顺序未定义，夹具一律显式保序）。 */
    private static Map<String, String> sections(String... keyValues) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
