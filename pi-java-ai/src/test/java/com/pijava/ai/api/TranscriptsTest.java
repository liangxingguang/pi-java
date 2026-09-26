package com.pijava.ai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * pi {@code packages/ai/test/system-message-replay.test.ts} 的移植（重放那一半）。
 *
 * <p>⚠️ 本条包之前本夹具的输入是**被删改过的**：pi 的第 5 条系统消息用
 * {@code sections: { b: null }} 表达「删掉 {@code b} 段」，而 java 的
 * {@code Map<String,String>} 表达不了 ⇒ 当时把那一项去掉了（{@code docs/49 §9 R3①}、
 * 登记 L3）。包 A4a 给了删除态载体 ⇒ 本夹具恢复成 pi 的**逐字**输入
 * （{@code docs/52 §4.1}）。</p>
 */
class TranscriptsTest {

    private static final Instant TS_10 = Instant.ofEpochMilli(10);
    private static final Instant TS_14 = Instant.ofEpochMilli(14);

    // ── 重放（pi :44-54）──────────────────────────────────────────────

    /** pi {@code :44-53} —— content 逐条拼接、sections 按名覆盖、tools 走重放、timestamp 取第一条。 */
    @Test
    void replaysContentSectionsAndToolsIntoOneLeadingMessage() {
        var current = Transcripts.getCurrentSystemMessage(transcript().messages());

        assertThat(current).isNotNull();
        assertThat(current.content())
            .containsExactly(new ContentBlock.TextContent("base\n\nalso do this"));
        // 逐字对齐 pi 的 :49：`b` 被第 5 条的 `null` 删掉，只剩 `a` 与 `c`，且**保序**
        // （pi 的 Map 语义：覆盖保留首次位置）—— 用 containsExactly 而不是 InAnyOrder。
        assertThat(current.sections()).containsExactly(
            Map.entry("a", "<a>2</a>"), Map.entry("c", "<c>1</c>"));
        assertThat(current.toolsAdded()).containsExactly(tool("second"));
        assertThat(current.toolsRemoved()).isEmpty();
        assertThat(current.timestamp()).isEqualTo(TS_10);

        assertThat(Transcripts.getCurrentSystemPrompt(transcript().messages()))
            .isEqualTo("base\n\nalso do this\n\n<a>2</a>\n\n<c>1</c>");
    }

    /** pi {@code :56-60} —— 头 + 全部非系统消息，且**幂等**。 */
    @Test
    void collapseKeepsOnlyNonSystemMessagesAfterTheReplayedHead() {
        var collapsed = Transcripts.collapseSystemMessages(transcript());

        assertThat(collapsed.messages()).extracting(Message::role)
            .containsExactly("system", "user", "assistant");
        assertThat(Transcripts.collapseSystemMessages(collapsed)).isEqualTo(collapsed);
    }

    /** pi {@code :62-67} —— 没有系统消息 ⇒ undefined / 空串 / 折叠恒等。 */
    @Test
    void replayOfATranscriptWithoutSystemMessagesIsEmpty() {
        var context = new TranscriptContext(List.of(user("hi")));

        assertThat(Transcripts.getCurrentSystemMessage(context.messages())).isNull();
        assertThat(Transcripts.getCurrentSystemPrompt(context.messages())).isEmpty();
        assertThat(Transcripts.collapseSystemMessages(context).messages())
            .containsExactlyElementsOf(context.messages());
    }

    /** pi {@code :69-84} —— 迟到的一整条 patch 会被重放成提示，并挪到头部。 */
    @Test
    void lateFullPatchOnATranscriptWithoutLeadingMessageReplaysAsThePrompt() {
        var context = new TranscriptContext(List.of(
            user("old session"),
            new Message.SystemMessage("", Instant.ofEpochMilli(2),
                sections("preamble", "You are pi."), List.of(tool("x")), List.of())));

        assertThat(Transcripts.getCurrentSystemPrompt(context.messages())).isEqualTo("You are pi.");

        var head = Transcripts.collapseSystemMessages(context).messages().get(0);
        assertThat(head.role()).isEqualTo("system");
        assertThat(((Message.SystemMessage) head).toolsAdded()).containsExactly(tool("x"));
    }

    // ── 工具重放（pi :46-53）──────────────────────────────────────────

    /** 同一条系统消息里**先删后加** —— 顺序反了会得到空表。 */
    @Test
    void appliesRemovalsBeforeAdditionsWithinOneMessage() {
        var context = new TranscriptContext(List.of(
            new Message.SystemMessage("", Instant.EPOCH, Map.of(),
                List.of(tool("a")), List.of(new ToolReference("a")))));

        assertThat(Transcripts.getCurrentTools(context.messages())).containsExactly(tool("a"));
    }

    /** 同名重声明按 pi 的 {@code Map.set} 语义**保留首次位置**、换值。 */
    @Test
    void redefinitionKeepsFirstDeclarationOrderAndTakesTheLatestValue() {
        var context = new TranscriptContext(List.of(
            new Message.SystemMessage("", Instant.EPOCH, Map.of(),
                List.of(tool("a"), tool("b")), List.of()),
            new Message.SystemMessage("", Instant.EPOCH, Map.of(),
                List.of(tool("a", "changed")), List.of())));

        assertThat(Transcripts.getCurrentTools(context.messages()))
            .extracting(ToolDefinition::name).containsExactly("a", "b");
        assertThat(Transcripts.getCurrentTools(context.messages()).get(0).description())
            .isEqualTo("changed");
    }

    // ── 前导系统消息（pi :36-44）──────────────────────────────────────

    @Test
    void initialSystemMessageIsOnlyTheFirstMessage() {
        assertThat(Transcripts.getInitialSystemMessage(List.of(user("hi")))).isNull();

        var head = new Message.SystemMessage("head", Instant.EPOCH, Map.of(), List.of(), List.of());
        var late = new Message.SystemMessage("late", Instant.EPOCH, Map.of(), List.of(), List.of());

        assertThat(Transcripts.getInitialSystemMessage(List.of(head, user("hi")))).isEqualTo(head);
        // 「前导」判据是**下标 0**，不是「第一条系统消息」（pi :218 的 sourceIndex === 0）
        assertThat(Transcripts.getInitialSystemMessage(List.of(user("hi"), late))).isNull();
        assertThat(Transcripts.withoutInitialSystemMessage(List.of(user("hi"), late)))
            .containsExactly(user("hi"), late);
        assertThat(Transcripts.withoutInitialSystemMessage(List.of(head, user("hi"))))
            .containsExactly(user("hi"));
    }

    // ── resolveTranscript（pi :113-120）───────────────────────────────

    @Test
    void resolveCollapsesUnlessTheModelDeclaresMidConversationSystemMessages() {
        var context = transcript();

        assertThat(Transcripts.resolveTranscript(context, model(null)))
            .isEqualTo(Transcripts.collapseSystemMessages(context));
        assertThat(Transcripts.resolveTranscript(context, model(false)))
            .isEqualTo(Transcripts.collapseSystemMessages(context));
        assertThat(Transcripts.resolveTranscript(context, model(true))).isSameAs(context);
    }

    /** pi 的 {@code model.compat?.supportsMidConvoSystemMessages} 默认缺席 ⇒ 折叠。 */
    @Test
    void midConversationFlagDefaultsToAbsent() {
        assertThat(ModelCompat.NONE.supportsMidConvoSystemMessages()).isNull();
        assertThat(ModelCompat.of(true).supportsMidConvoSystemMessages()).isNull();
        // 4 参形态（包 A2 之前的形状）同样缺席该标志
        assertThat(new ModelCompat(false, null, true, true).supportsMidConvoSystemMessages()).isNull();
        assertThat(new ModelCompat(false, null, true, false, true).supportsMidConvoSystemMessages())
            .isTrue();
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    /** pi {@code :20-41} 的转录，**原样**（含第 5 条的 {@code b: null}）。 */
    private static TranscriptContext transcript() {
        return new TranscriptContext(List.of(
            new Message.SystemMessage("base", TS_10, sections("a", "<a>1</a>", "b", "<b>1</b>"),
                List.of(tool("first")), List.of()),
            user("hello"),
            new Message.SystemMessage("also do this", Instant.ofEpochMilli(12),
                Map.of(), List.of(), List.of()),
            assistant("ok"),
            new Message.SystemMessage("", TS_14,
                sections("a", "<a>2</a>", "b", null, "c", "<c>1</c>"),
                List.of(tool("second")), List.of(new ToolReference("first")))));
    }

    private static ModelInfo model(Boolean supportsMidConversationSystemMessages) {
        return new ModelInfo(ModelId.of("test", "m"), "M", Set.of(),
            100, 100, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(), Map.of(), Map.of(),
            new ModelCompat(false, null, true, false, supportsMidConversationSystemMessages));
    }

    private static Map<String, String> sections(String... keyValues) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message.AssistantMessage assistant(String text) {
        return Message.AssistantMessage.fromPartial(
            com.pijava.ai.message.AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent(text))));
    }

    private static ToolDefinition tool(String name) {
        return tool(name, name + " tool");
    }

    private static ToolDefinition tool(String name, String description) {
        return new ToolDefinition(name, description, Map.of("type", "object"));
    }
}
