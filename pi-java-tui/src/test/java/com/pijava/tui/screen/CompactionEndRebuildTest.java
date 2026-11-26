package com.pijava.tui.screen;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.entry.Entry;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.core.AgentSessionEvent.CompactionReason;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.MetaKind;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B38（pi interactive-mode.ts:3886-3935）：compaction_end 的取消消息、成功后的
 * 整表重建、摘要卡与成本通知。
 */
class CompactionEndRebuildTest {

    private static Entry.Compaction compaction(Usage usage) {
        return new Entry.Compaction("c1", 1L, null, Instant.EPOCH, "summary text", "u1",
            List.of(), 1234, Map.of(), usage, null, null);
    }

    private static Entry userEntry() {
        return new Entry.Message("u1", 2L, "c1", Instant.EPOCH,
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hello"))), null);
    }

    private static CompactionResult result(Usage usage) {
        return new CompactionResult("summary text", "u1", 1234L, null, usage, Map.of());
    }

    @Test
    void abortedManualShowsPiCancelError() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.CompactionEnd(
            CompactionReason.MANUAL, null, true, false, null, null));

        assertThat(screen.lastMessage())
            .isEqualTo(new ChatMessage.Error("Error: Compaction cancelled"));
        assertThat(screen.indicator()).isNull();
    }

    @Test
    void abortedAutoShowsPiStatusLine() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.CompactionEnd(
            CompactionReason.THRESHOLD, null, true, false, null, null));

        assertThat(screen.lastMessage())
            .isEqualTo(new ChatMessage.System("Auto-compaction cancelled", MetaKind.GENERIC));
    }

    @Test
    void successRebuildsFromContextEntriesAndAppendsTheSummaryCard() {
        var screen = new ChatScreen();
        var compaction = compaction(null);
        screen.setCompactionContextSupplier(() -> List.of(compaction, userEntry()));

        screen.onSessionEvent(new AgentSessionEvent.CompactionEnd(
            CompactionReason.MANUAL, result(null), false, false, null, compaction));

        assertThat(screen.messages()).hasSize(2);
        assertThat(screen.messages().get(0)).isEqualTo(new ChatMessage.User("hello"));
        assertThat(screen.lastMessage()).isEqualTo(new ChatMessage.CompactionCard(
            1234, "summary text", "c1", Instant.EPOCH.toString()));
        assertThat(screen.indicator()).isNull();
    }

    @Test
    void successAppendsTheCompactionCostNoticeWhenUsageIsPresent() {
        var screen = new ChatScreen();
        screen.setShowCacheMissNoticesSupplier(() -> true);
        var usage = new Usage(100, 50, 0, 0, null, null, 150,
            new Usage.Cost(1, 2, 0, 0, 3.5));
        var compaction = compaction(usage);
        screen.setCompactionContextSupplier(() -> List.of(compaction, userEntry()));

        screen.onSessionEvent(new AgentSessionEvent.CompactionEnd(
            CompactionReason.MANUAL, result(usage), false, false, null, compaction));

        assertThat(screen.lastMessage()).isEqualTo(new ChatMessage.System(
            "Compaction: 150 tokens billed (~$3.50)", MetaKind.GENERIC));
        assertThat(screen.messages().get(screen.messages().size() - 2))
            .isInstanceOf(ChatMessage.CompactionCard.class);
    }

    @Test
    void costNoticeStaysBehindTheNoticesGate() {
        var screen = new ChatScreen();
        var usage = Usage.of(100, 50);
        var compaction = compaction(usage);
        screen.setCompactionContextSupplier(() -> List.of(compaction, userEntry()));

        screen.onSessionEvent(new AgentSessionEvent.CompactionEnd(
            CompactionReason.MANUAL, result(usage), false, false, null, compaction));

        assertThat(screen.lastMessage()).isInstanceOf(ChatMessage.CompactionCard.class);
    }

    @Test
    void manualErrorShowsTheErrorWithPrefix() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.CompactionEnd(
            CompactionReason.MANUAL, null, false, false, "boom", null));

        assertThat(screen.lastMessage()).isEqualTo(new ChatMessage.Error("Error: boom"));
    }

    @Test
    void autoErrorAppendsTheBareErrorText() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.CompactionEnd(
            CompactionReason.OVERFLOW, null, false, false, "boom", null));

        assertThat(screen.lastMessage()).isEqualTo(new ChatMessage.Error("boom"));
    }

    @Test
    void successWithoutACompactionEntryThrowsLikePi() {
        var screen = new ChatScreen();
        screen.setCompactionContextSupplier(List::of);

        assertThatThrownBy(() -> screen.onSessionEvent(new AgentSessionEvent.CompactionEnd(
            CompactionReason.MANUAL, result(null), false, false, null, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Completed compaction is missing from the session context");
    }
}
