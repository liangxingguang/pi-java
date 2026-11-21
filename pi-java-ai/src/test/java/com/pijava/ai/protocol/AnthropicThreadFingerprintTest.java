package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.CacheBreakpointSpec;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 线程前缀哈希的**两半**（{@code docs/12} 的 S7b，方案 A）：逐行指纹取自
 * {@code cache_control} 挂上**之前**（否则标记搬移会让每轮都判「前缀变了」），
 * 设置半边独立于消息行。
 */
class AnthropicThreadFingerprintTest {

    private static final String BASE = "https://api.anthropic.com";

    private static StreamRequest request(List<Message> messages) {
        return new StreamRequest(ModelId.of("anthropic", "claude-sonnet-5"), "be brief",
            messages, List.of(new ToolDefinition("ls", "List files",
                Map.of("type", "object", "properties", Map.of()))), 100, 0.5);
    }

    private static List<Message> conversation() {
        return List.of(
            new Message.SystemMessage("base prompt", java.time.Instant.EPOCH, Map.of(),
                List.of(), List.of()),
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))),
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent("hello")),
                StopReason.STOP, null, "anthropic-messages", "anthropic",
                "claude-sonnet-5", null, null, null, null),
            new Message.UserMessage(List.of(new ContentBlock.TextContent("next"))));
    }

    private static AnthropicRequestBuilder.BuiltParams build(
            Optional<CacheBreakpointSpec> cacheSpec, StreamRequest request) {
        return AnthropicRequestBuilder.buildParams(request, cacheSpec, null, BASE,
            Set.of(), false, false, null, false);
    }

    @Test
    void rowKeysIgnoreTheCacheBreakpointThatIsAttachedAfterwards() {
        var plain = build(Optional.empty(), request(conversation()));
        var cached = build(Optional.of(new CacheBreakpointSpec(false)), request(conversation()));

        // 断点挂在最后一条消息的末块上；指纹在挂断点之前取 ⇒ 两次必须逐行相同。
        assertThat(cached.rowKeys()).isEqualTo(plain.rowKeys());
        assertThat(cached.messageCount()).isEqualTo(plain.messageCount());
        assertThat(cached.rowKeys()).noneMatch(key -> key.contains("cache_control"));
    }

    @Test
    void theSameRequestAlwaysYieldsTheSameSettingsKey() {
        var first = build(Optional.empty(), request(conversation()));
        var second = build(Optional.empty(), request(conversation()));

        assertThat(first.settingsKey()).isEqualTo(second.settingsKey());
        assertThat(first.settingsKey()).isNotBlank();
    }

    @Test
    void changingTheSystemPromptChangesTheSettingsKeyButNotTheRows() {
        var changed = new ArrayList<>(conversation());
        changed.set(0, new Message.SystemMessage("other prompt", java.time.Instant.EPOCH,
            Map.of(), List.of(), List.of()));

        var before = build(Optional.empty(), request(conversation()));
        var after = build(Optional.empty(), request(changed));

        assertThat(after.settingsKey()).isNotEqualTo(before.settingsKey());
        assertThat(after.rowKeys()).isEqualTo(before.rowKeys());
    }

    @Test
    void aGrowingConversationKeepsTheFingerprintOfItsPrefix() {
        var grown = new ArrayList<>(conversation());
        grown.add(new Message.UserMessage(List.of(new ContentBlock.TextContent("third"))));

        var before = build(Optional.empty(), request(conversation()));
        var after = build(Optional.empty(), request(grown));

        assertThat(after.messageCount()).isEqualTo(before.messageCount() + 1);
        assertThat(after.rowKeys().subList(0, before.rowKeys().size()))
            .isEqualTo(before.rowKeys());
    }
}
