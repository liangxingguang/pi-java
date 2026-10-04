package com.pijava.ai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

/**
 * 包 A2 / R5（{@code 原 docs/49 §5.5}）：系统消息对工具调用记账**透明**。
 *
 * <p>期望值来自 pi 的**实测探针**（{@code 原 docs/49 §7.6} 的 PR-1/PR-2，pi {@code 3390bd936}）：
 * 落在 toolCall 与其结果之间的系统消息被扣住、等结果（含合成结果）发完再发。</p>
 *
 * <p>⚠️ 这一条在包 A2 之前是 {@code IllegalStateException: unreachable message role} ——
 * 也就是说它同时是「A2 的三条车道能不能发出请求」的前置条件（A1 之后该系统消息可达）。</p>
 */
class OrphanToolResultsHeldSystemMessageTest {

    private static final ModelId<?> TARGET = ModelId.of("anthropic", "claude-sonnet-5");

    /** pi PR-1：真结果到场 —— 系统消息排在它**之后**。 */
    @Test
    void holdsASystemMessageUntilTheToolResultIsEmitted() {
        var transformed = transform(
            user("go"),
            assistantToolCall("tc1"),
            lateSystem("mid"),
            toolResult("tc1"));

        // ⚠️ 工具结果的 role 字面量在 java 上是 "tool"（pi 是 "toolResult"）——
        // 既有刻意偏差，不在包 A2 内。
        assertThat(roles(transformed)).containsExactly("user", "assistant", "tool", "system");
        assertThat(transformed.get(3)).isInstanceOf(Message.SystemMessage.class);
    }

    /** pi PR-2：孤儿调用被合成 —— 合成结果在 held 之前（载荷逐字同 pi）。 */
    @Test
    void synthesizesTheOrphanResultBeforeTheHeldSystemMessage() {
        var transformed = transform(
            user("go"),
            assistantToolCall("tc1"),
            lateSystem("mid"),
            user("next"));

        assertThat(roles(transformed))
            .containsExactly("user", "assistant", "tool", "system", "user");
        var synthetic = (Message.ToolResultMessage) transformed.get(2);
        assertThat(synthetic.toolUseId()).isEqualTo("tc1");
        assertThat(synthetic.toolName()).isEqualTo("lookup");
        assertThat(synthetic.isError()).isTrue();
        assertThat(synthetic.content())
            .containsExactly(new ContentBlock.TextContent("No result provided"));
    }

    /** 前导系统消息**留在下标 0**（Anthropic 车道的切头依赖这一点）。 */
    @Test
    void leadingSystemMessageStaysAtTheHead() {
        var transformed = transform(
            new Message.SystemMessage("head", Instant.EPOCH, Map.of(), List.of(), List.of()),
            user("go"),
            assistantToolCall("tc1"));

        // 末尾那个未答调用照例被合成（`closePendingToolCalls` 的收尾调用）。
        assertThat(roles(transformed)).containsExactly("system", "user", "assistant", "tool");
    }

    /** 没有未答调用时系统消息**就地放行**（不是被扣住等下一次 close）。 */
    @Test
    void passesASystemMessageThroughWhenNoToolCallIsPending() {
        var transformed = transform(lateSystem("a"), user("go"), lateSystem("b"));

        assertThat(roles(transformed)).containsExactly("system", "user", "system");
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    private static List<Message> transform(Message... messages) {
        return TransformMessages.apply(List.of(messages), TARGET, "anthropic-messages",
            ModelInfo.minimal(TARGET));
    }

    private static List<String> roles(List<Message> messages) {
        return messages.stream().map(Message::role).toList();
    }

    private static Message.SystemMessage lateSystem(String text) {
        return new Message.SystemMessage(text, Instant.EPOCH, Map.of(), List.of(), List.of());
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message.AssistantMessage assistantToolCall(String id) {
        return new Message.AssistantMessage(
            List.of(new ContentBlock.ToolUseContent(id, "lookup", Map.of())),
            "toolUse", null, "anthropic-messages", "anthropic", "claude-sonnet-5",
            null, null, null, null);
    }

    private static Message.ToolResultMessage toolResult(String id) {
        return new Message.ToolResultMessage(id, "lookup",
            List.of(new ContentBlock.TextContent("r")), false);
    }
}
