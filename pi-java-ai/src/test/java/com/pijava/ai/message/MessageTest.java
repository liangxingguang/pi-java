package com.pijava.ai.message;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link Message} sealed hierarchy and {@link ContentBlock} subtypes.
 */
class MessageTest {

    /**
     * 变体集合与 pi 的 {@code Message} 联合类型一致：恰好三个角色，**没有 system**
     * （{@code packages/ai/src/types.ts:470}）。系统提示是 {@code Context.systemPrompt}
     * 上的独立字段，不是消息。多出第四个变体意味着某处又把系统提示塞回了消息列表。
     */
    @Test
    void messageUnionHasExactlyPiThreeRoles() {
        var variants = java.util.Arrays.stream(Message.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .sorted()
                .toList();

        assertThat(variants)
                .containsExactly("AssistantMessage", "ToolResultMessage", "UserMessage");
    }

    @Test
    void userMessageShouldHoldMultipleBlocks() {
        var msg = new Message.UserMessage(List.of(
                new ContentBlock.TextContent("Hello"),
                new ContentBlock.ImageContent("image/png", "base64data")));

        assertThat(msg.content()).hasSize(2);
        assertThat(msg.content().get(0)).isInstanceOf(ContentBlock.TextContent.class);
        assertThat(msg.content().get(1)).isInstanceOf(ContentBlock.ImageContent.class);
    }

    @Test
    void assistantMessageShouldHoldToolUse() {
        var msg = new Message.AssistantMessage(List.of(
                new ContentBlock.ToolUseContent("toolu_01",
                        "read", Map.of("path", "/src/main.java"))));

        assertThat(msg.content()).hasSize(1);
        var block = (ContentBlock.ToolUseContent) msg.content().get(0);
        assertThat(block.id()).isEqualTo("toolu_01");
        assertThat(block.name()).isEqualTo("read");
        assertThat(block.arguments()).containsEntry("path", "/src/main.java");
    }

    @Test
    void assistantMessageCarriesStopReasonAndDeferredHandle() {
        var handle = new DeferredHandle("faux", "test-model", "faux-api", "batch-1",
            1_700_000_000_000L, 500L, Map.of("row", 2));
        var msg = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("hi")), "deferred", handle);

        assertThat(msg.content()).hasSize(1);
        assertThat(msg.stopReason()).isEqualTo("deferred");
        assertThat(msg.deferred()).isEqualTo(handle);
        assertThat(msg.role()).isEqualTo("assistant");
    }

    @Test
    void singleArgConstructorLeavesStopReasonAndDeferredNull() {
        var msg = new Message.AssistantMessage(List.of(new ContentBlock.TextContent("hi")));

        assertThat(msg.stopReason()).isNull();
        assertThat(msg.deferred()).isNull();
    }

    @Test
    void deferredHandleDefensiveCopy() {
        var data = new java.util.HashMap<String, Object>();
        data.put("row", 2);
        var handle = new DeferredHandle("faux", "test-model", "faux-api", "batch-1",
            null, null, data);

        data.put("row", "modified");

        assertThat(handle.data()).containsEntry("row", 2);
    }

    @Test
    void deferredHandleAcceptsANullDataMap() {
        var handle = new DeferredHandle("faux", "test-model", "faux-api", "batch-1",
            null, null, null);

        assertThat(handle.data()).isNull();
        assertThat(handle.expiresAt()).isNull();
        assertThat(handle.pollAfterMs()).isNull();
    }

    @Test
    void toolResultShouldPreserveErrorFlag() {
        var msg = new Message.ToolResultMessage("toolu_01", "read",
                List.of(new ContentBlock.TextContent("File not found")), true);

        assertThat(msg.content()).hasSize(1);
        assertThat(msg.toolUseId()).isEqualTo("toolu_01");
        assertThat(msg.toolName()).isEqualTo("read");
        assertThat(msg.isError()).isTrue();
        // 兼容构造器 = pi 对象字面量省略可选字段（details/usage undefined、addedToolNames 缺省）
        assertThat(msg.details()).isNull();
        assertThat(msg.usage()).isNull();
        assertThat(msg.addedToolNames()).isEmpty();
    }

    @Test
    void toolResultCarriesTheStructuredPayloadLikePi() {
        // pi ToolResultMessage（ai/types.ts:452-468）的三载荷 + 空 addedToolNames 归一
        var details = java.util.Map.of("kind", "card");
        var full = new Message.ToolResultMessage("toolu_02", "mcp",
                List.of(new ContentBlock.TextContent("ok")), details, "usage-token",
                new java.util.ArrayList<>(List.of("mcp:a")), false);

        assertThat(full.details()).isEqualTo(details);
        assertThat(full.usage()).isEqualTo("usage-token");
        assertThat(full.addedToolNames()).containsExactly("mcp:a");

        var nullNames = new Message.ToolResultMessage("toolu_03", "mcp",
                List.of(new ContentBlock.TextContent("ok")), null, null, null, true);
        assertThat(nullNames.addedToolNames()).isEmpty();
    }

    @Test
    void textContentEquality() {
        var a = new ContentBlock.TextContent("hello");
        var b = new ContentBlock.TextContent("hello");
        var c = new ContentBlock.TextContent("world");

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toolUseContentDefensiveCopy() {
        var args = new java.util.HashMap<String, Object>();
        args.put("key", "value");
        var block = new ContentBlock.ToolUseContent("id", "tool", args);

        args.put("key", "modified");
        assertThat(block.arguments()).containsEntry("key", "value");
    }
}
