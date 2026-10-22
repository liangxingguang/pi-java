package com.pijava.tui.screen;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.Usage;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.MetaKind;

/**
 * 批 13（docs/24 §7，T10）：暖用量行——pi 逐字文案（cache-warmer.ts:449-453 经
 * {@code CacheWarmingFormatting.formatUsage}）＋门（interactive-mode.ts:4111
 * {@code getShowCacheMissNotices()}）＋即时臂（:3449-3451 {@code entry_appended}
 * 上屏）＋onEntry 去重（java 增量追加特有，pi 是重建式渲染无此问题）。
 */
class ChatScreenCacheWarmingTest {

    private static Entry.Usage warmEntry(String id, String note) {
        return new Entry.Usage(id, 0, null, Instant.ofEpochMilli(0), "cache_warm",
            "anthropic", "claude-opus-4-6",
            new Usage(0, 1, 100, 0, null, null, 101,
                new Usage.Cost(0.00004, 0.00005, 0.02940725, 0, 0.02949725)),
            note, null, null, null, null, null);
    }

    // ── ChatMessage 文案两形＋非 cache_warm 不渲 ────────────────────────

    @Test
    void cacheWarmNoticeUsesPiVerbatimText() {
        var withNote = ChatMessage.from(warmEntry("u1", "extension override"));
        assertThat(withNote).isEqualTo(new ChatMessage.System(
            "Cache warmed (extension override): $0.029497", MetaKind.GENERIC));

        var bare = ChatMessage.from(warmEntry("u2", null));
        assertThat(bare).isEqualTo(new ChatMessage.System(
            "Cache warmed: $0.029497", MetaKind.GENERIC));
    }

    @Test
    void nonCacheWarmUsageEntriesDoNotRender() {
        var assistant = new Entry.Usage("u3", 0, null, Instant.ofEpochMilli(0),
            "assistant", "anthropic", "claude-opus-4-6",
            Usage.of(100, 10, 110), null, null, null, null, null, null);
        assertThat(ChatMessage.from(assistant)).isNull();
    }

    // ── 门（pi :4111）＋即时臂（pi :3449-3451）＋onEntry 去重 ───────────

    @Test
    void entryAppendedRendersWarmUsageOnlyWhenGateOpen() {
        var gate = new java.util.concurrent.atomic.AtomicBoolean(false);
        var screen = new ChatScreen();
        screen.setShowCacheMissNoticesSupplier(gate::get);

        screen.onSessionEvent(new AgentSessionEvent.EntryAppended(warmEntry("u1", null)));
        assertThat(screen.messages()).as("门关 ⇒ 不渲").isEmpty();

        gate.set(true);
        screen.onSessionEvent(new AgentSessionEvent.EntryAppended(warmEntry("u2", null)));
        assertThat(screen.messages()).containsExactly(
            new ChatMessage.System("Cache warmed: $0.029497", MetaKind.GENERIC));
    }

    @Test
    void onEntrySkipsAlreadyShownLiveNoticeAndHonorsGate() {
        var gate = new java.util.concurrent.atomic.AtomicBoolean(true);
        var screen = new ChatScreen();
        screen.setShowCacheMissNoticesSupplier(gate::get);

        var entry = warmEntry("u1", null);
        screen.onSessionEvent(new AgentSessionEvent.EntryAppended(entry));
        // run 结束 onEntry 投递同一条目 ⇒ 跳过（liveUsageNoticeIds 去重）。
        screen.onEntry(entry);
        assertThat(screen.messages()).hasSize(1);

        // 另一条目：onEntry 直渲（同门）。
        screen.onEntry(warmEntry("u2", null));
        assertThat(screen.messages()).hasSize(2);

        // 门关：onEntry 也不渲。
        gate.set(false);
        screen.onEntry(warmEntry("u3", null));
        assertThat(screen.messages()).hasSize(2);
    }

    @Test
    void rebuildTranscriptRendersWarmUsageUnderGate() {
        var gate = new java.util.concurrent.atomic.AtomicBoolean(true);
        var screen = new ChatScreen();
        screen.setShowCacheMissNoticesSupplier(gate::get);

        var entry = warmEntry("u1", null);
        screen.rebuildTranscript(List.of(entry), "u1");
        assertThat(screen.messages()).containsExactly(
            new ChatMessage.System("Cache warmed: $0.029497", MetaKind.GENERIC));

        gate.set(false);
        screen.rebuildTranscript(List.of(entry), "u1");
        assertThat(screen.messages()).isEmpty();
    }
}
