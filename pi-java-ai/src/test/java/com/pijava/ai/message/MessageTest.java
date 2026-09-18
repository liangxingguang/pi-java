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

    /** 3a：终局消息携带 provider 身份三元组 + 计量 + 时间戳 + 错误文本（全 9 组件）。 */
    @Test
    void assistantMessageCarriesIdentityUsageAndTimestamp() {
        var at = java.time.Instant.ofEpochMilli(1_700_000_000_000L);
        var usage = new com.pijava.ai.Usage(10, 5, 1, 2, null, null, 18,
                com.pijava.ai.Usage.Cost.zero());
        var msg = new Message.AssistantMessage(List.of(), "stop", null,
                "anthropic-messages", "anthropic", "claude-sonnet-5", usage, at, null, null);

        assertThat(msg.api()).isEqualTo("anthropic-messages");
        assertThat(msg.provider()).isEqualTo("anthropic");
        assertThat(msg.model()).isEqualTo("claude-sonnet-5");
        assertThat(msg.usage()).isEqualTo(usage);
        assertThat(msg.timestamp()).isEqualTo(at);
        assertThat(msg.errorMessage()).isNull();
        assertThat(msg.role()).isEqualTo("assistant");
    }

    /**
     * 3a：{@code fromPartial} 是全字段投影 —— pi 的 partial 与终局同形状
     * （assistant-message-frame.ts:77-92），只搬 content/stopReason 就是 3a 前的丢点。
     */
    @Test
    void fromPartialProjectsEveryField() {
        var at = java.time.Instant.ofEpochMilli(1_700_000_123_456L);
        var usage = new com.pijava.ai.Usage(7, 3, 0, 0, null, null, 10,
                com.pijava.ai.Usage.Cost.zero());
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("hello")))
                .withStopReason("error")
                .withRawStopReason("refusal")
                .withIdentity("openai-responses", "openai", "mock", at)
                .withErrorMessage("boom")
                .withUsage(new com.pijava.ai.stream.StreamEvent.UsageInfo(7, 3, null, usage));

        var msg = Message.AssistantMessage.fromPartial(partial);

        assertThat(msg.content()).hasSize(1);
        assertThat(msg.stopReason()).isEqualTo("error");
        assertThat(msg.rawStopReason()).isEqualTo("refusal");
        assertThat(msg.deferred()).isNull();
        assertThat(msg.api()).isEqualTo("openai-responses");
        assertThat(msg.provider()).isEqualTo("openai");
        assertThat(msg.model()).isEqualTo("mock");
        assertThat(msg.timestamp()).isEqualTo(at);
        assertThat(msg.errorMessage()).isEqualTo("boom");
        // 有全量分解 ⇒ 原样用（cache/cost 不丢）
        assertThat(msg.usage()).isEqualTo(usage);
    }

    /** 3a：只有计数没有分解 ⇒ 合成完整 Usage（cache 0、cost 零、totalTokens 求和）。 */
    @Test
    void fromPartialSynthesizesUsageFromCounts() {
        var partial = AssistantMessage.empty()
                .withUsage(new com.pijava.ai.stream.StreamEvent.UsageInfo(12, 5, null));

        var usage = Message.AssistantMessage.fromPartial(partial).usage();

        assertThat(usage.input()).isEqualTo(12);
        assertThat(usage.output()).isEqualTo(5);
        assertThat(usage.cacheRead()).isZero();
        assertThat(usage.cacheWrite()).isZero();
        assertThat(usage.totalTokens()).isEqualTo(17);
        assertThat(usage.cost()).isEqualTo(com.pijava.ai.Usage.Cost.zero());
    }

    /** 3a：没有 UsageInfo ⇒ usage 为 null（线上键省略，null ≙ pi 的 undefined）。 */
    @Test
    void fromPartialWithoutUsageInfoLeavesUsageNull() {
        var msg = Message.AssistantMessage.fromPartial(AssistantMessage.empty());

        assertThat(msg.usage()).isNull();
        assertThat(msg.api()).isNull();
        assertThat(msg.timestamp()).isNull();
    }

    /** 3a：withStopReason 是重写不是重建 —— 身份/计量/时间戳/错误文本必须原样保住。 */
    @Test
    void withStopReasonPreservesIdentityAndMetrics() {
        var at = java.time.Instant.ofEpochMilli(1_700_000_000_000L);
        var usage = new com.pijava.ai.Usage(1, 2, 0, 0, null, null, 3,
                com.pijava.ai.Usage.Cost.zero());
        var msg = new Message.AssistantMessage(List.of(), "stop", null,
                "pi-messages", "pi", "model-x", usage, at, "err", null);

        var rewritten = msg.withStopReason("aborted");

        assertThat(rewritten.stopReason()).isEqualTo("aborted");
        assertThat(rewritten.api()).isEqualTo("pi-messages");
        assertThat(rewritten.provider()).isEqualTo("pi");
        assertThat(rewritten.model()).isEqualTo("model-x");
        assertThat(rewritten.usage()).isEqualTo(usage);
        assertThat(rewritten.timestamp()).isEqualTo(at);
        assertThat(rewritten.errorMessage()).isEqualTo("err");
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
