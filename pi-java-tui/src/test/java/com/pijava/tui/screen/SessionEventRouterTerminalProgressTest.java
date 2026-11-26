package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.tui.component.ChatPanel;
import com.pijava.tui.component.PendingQueueView;
import com.pijava.tui.util.TerminalProgressReporter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** E2-32：会话事件驱动 OSC 9;4 进度（pi interactive-mode.ts:3617/3845/3867/3887）。 */
class SessionEventRouterTerminalProgressTest {

    @Test
    void turnAndCompactionEventsDriveProgress() {
        var sink = new ArrayList<String>();
        var panel = new ChatPanel();
        var router = new SessionEventRouter(
            panel, new PendingQueueView(), new CacheNoticeRenderer(panel, () -> 0));
        try (var reporter = new TerminalProgressReporter(sink::add, () -> true)) {
            router.setTerminalProgressReporter(reporter);

            router.onSessionEvent(new AgentSessionEvent.TurnStart());
            assertThat(sink).containsExactly(TerminalProgressReporter.ACTIVE_SEQUENCE);
            assertThat(reporter.active()).isTrue();

            router.onSessionEvent(new AgentSessionEvent.AgentEnd(List.of(), false));
            assertThat(sink.get(sink.size() - 1))
                .isEqualTo(TerminalProgressReporter.CLEAR_SEQUENCE);
            assertThat(reporter.active()).isFalse();

            router.onSessionEvent(new AgentSessionEvent.CompactionStart(
                AgentSessionEvent.CompactionReason.MANUAL));
            assertThat(reporter.active()).isTrue();

            router.onSessionEvent(new AgentSessionEvent.CompactionEnd(
                AgentSessionEvent.CompactionReason.MANUAL, null, false, false, null, null));
            assertThat(reporter.active()).isFalse();
            assertThat(sink.get(sink.size() - 1))
                .isEqualTo(TerminalProgressReporter.CLEAR_SEQUENCE);
        }
    }
}
