package com.pijava.tui.screen;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.Usage;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.MetaKind;
import com.pijava.tui.util.CacheMissTracker;

/**
 * 批 13（docs/24 §7，T11）：cache miss 通知——即时臂（pi :4185-4191，message_end
 * 挂点）＋条目流臂（pi :3999-4065 重建注入）＋同实例去重＋{@link CacheMissTracker}
 * 双臂（feed 推进／detectOnMessageEnd 不推进）。
 */
class ChatScreenCacheMissTest {

    private static Usage usage(double input, double cacheRead, double cacheWrite) {
        return new Usage(input, 1, cacheRead, cacheWrite, null, null,
            input + cacheRead + cacheWrite + 1, Usage.Cost.zero());
    }

    private static Message.AssistantMessage assistant(String model, long epochMs,
                                                      Usage usage) {
        return assistant(model, epochMs, usage, StopReason.LENGTH);
    }

    private static Message.AssistantMessage assistant(String model, long epochMs,
                                                      Usage usage, StopReason stopReason) {
        return new Message.AssistantMessage(List.of(), stopReason, null,
            "anthropic-messages", "anthropic", model, usage,
            Instant.ofEpochMilli(epochMs), null, null);
    }

    private static Entry.Message entryOf(Message.AssistantMessage message, String id) {
        return entryOf(message, id, null);
    }

    private static Entry.Message entryOf(Message.AssistantMessage message, String id,
                                         String parentId) {
        return new Entry.Message(id, 0, parentId, Instant.now(), message, null);
    }

    private static ChatScreen gatedScreen() {
        var screen = new ChatScreen();
        screen.setShowCacheMissNoticesSupplier(() -> true);
        return screen;
    }

    // ── tracker 双臂 ────────────────────────────────────────────────────

    @Test
    void trackerFeedsPrevAndDetectsOnMessageEndWithoutAdvancing() {
        var tracker = CacheMissTracker.noModels();
        // 首 turn：充 prev（报过缓存活动）。
        assertThat(tracker.feed(entryOf(assistant("claude-opus-4-6", 0,
            usage(0, 0, 100_000)), "m1"))).isEmpty();
        // 即时臂：未投递消息用当前 prev 判一次（不推进——同一 prev 可再判）。
        var miss = tracker.detectOnMessageEnd(assistant("claude-opus-4-6", 400_000,
            usage(100_000, 0, 0)));
        assertThat(miss).isPresent();
        assertThat(miss.get().missedTokens()).isEqualTo(100_000);
        assertThat(miss.get().idleMs()).isEqualTo(400_000);
        assertThat(tracker.detectOnMessageEnd(assistant("claude-opus-4-6", 400_000,
            usage(100_000, 0, 0)))).as("detect 不推进 prev").isPresent();

        // compaction 复位。
        tracker.feed(new Entry.Compaction("c1", 0, null, Instant.now(),
            "s", null, List.of(), 0, null, null, null));
        assertThat(tracker.detectOnMessageEnd(assistant("claude-opus-4-6", 500_000,
            usage(100_000, 0, 0)))).isEmpty();

        // cache_warm 条目充 prev（reportedCache 恒 true）。
        tracker.feed(new Entry.Usage("w1", 0, null, Instant.ofEpochMilli(600_000),
            "cache_warm", "anthropic", "claude-opus-4-6", usage(0, 100_000, 0), null,
            null, null, null, null, null));
        assertThat(tracker.detectOnMessageEnd(assistant("claude-opus-4-6", 610_000,
            usage(100_000, 0, 0)))).isPresent();
    }

    // ── 即时臂＋同实例去重（pi :4185-4191）──────────────────────────────

    @Test
    void messageEndShowsMissNoticeOnceAndOnEntryDedupesSameInstance() {
        var screen = gatedScreen();
        var first = assistant("claude-opus-4-6", 0, usage(0, 0, 100_000));
        screen.onEntry(entryOf(first, "m1"));

        var second = assistant("claude-opus-4-6", 400_000, usage(100_000, 0, 0));
        screen.onSessionEvent(new AgentSessionEvent.MessageEnd(second));
        // pi :4193-4206 逐字：100k ⇒ "100k tokens re-billed"；idle 400s ≥ 300s ⇒ "7m idle"。
        assertThat(screen.messages()).containsExactly(
            new ChatMessage.System("Cache miss after 7m idle: 100k tokens re-billed",
                MetaKind.GENERIC));

        // run 结束 onEntry 投递同一实例 ⇒ 不重复。
        screen.onEntry(entryOf(second, "m2"));
        assertThat(screen.messages()).hasSize(1);
    }

    @Test
    void modelSwitchLabelsTheNotice() {
        var screen = gatedScreen();
        screen.onEntry(entryOf(assistant("claude-opus-4-6", 0, usage(0, 0, 100_000)), "m1"));
        screen.onSessionEvent(new AgentSessionEvent.MessageEnd(
            assistant("claude-sonnet-4-5", 1_000, usage(100_000, 0, 0))));
        assertThat(screen.messages()).containsExactly(
            new ChatMessage.System(
                "Cache miss after model switch: 100k tokens re-billed", MetaKind.GENERIC));
    }

    @Test
    void gateOffSuppressesNoticesAndAbortedMessagesAreSkipped() {
        var noticesOff = new ChatScreen(); // 默认门关
        noticesOff.onEntry(entryOf(assistant("claude-opus-4-6", 0,
            usage(0, 0, 100_000)), "m1"));
        noticesOff.onSessionEvent(new AgentSessionEvent.MessageEnd(
            assistant("claude-opus-4-6", 1_000, usage(100_000, 0, 0))));
        assertThat(noticesOff.messages()).isEmpty();

        var screen = gatedScreen();
        screen.onEntry(entryOf(assistant("claude-opus-4-6", 0,
            usage(0, 0, 100_000)), "m1"));
        // aborted/error 不注入（pi :4063/:4186 同门）——且 tracker 已推进 prev，
        // 下一条正常消息相对 aborted 判（pi 同：asPreviousRequest 不问 stopReason）。
        screen.onSessionEvent(new AgentSessionEvent.MessageEnd(
            assistant("claude-opus-4-6", 1_000, usage(100_000, 0, 0), StopReason.ABORTED)));
        assertThat(screen.messages()).isEmpty();
    }

    // ── 重建臂（pi :3999-4065）─────────────────────────────────────────

    @Test
    void rebuildTranscriptInjectsMissNoticesAfterTheAssistantBubble() {
        var screen = gatedScreen();
        var first = assistant("claude-opus-4-6", 0, usage(0, 0, 100_000));
        var second = assistant("claude-opus-4-6", 1_000, usage(100_000, 0, 0));
        screen.rebuildTranscript(List.of(entryOf(first, "m1"), entryOf(second, "m2", "m1")),
            "m2");

        var systems = screen.messages().stream()
            .filter(m -> m instanceof ChatMessage.System).toList();
        assertThat(systems).containsExactly(
            new ChatMessage.System("Cache miss: 100k tokens re-billed", MetaKind.GENERIC));
        // 通知在 assistant 气泡之后（pi :4062 序）。
        var messages = screen.messages();
        var assistantIndex = messages.indexOf(new ChatMessage.Assistant(second.content()));
        assertThat(messages.indexOf(systems.get(0))).isGreaterThan(assistantIndex);
    }
}
