package com.pijava.tui.screen;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.tui.component.ChatMessage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B46（pi interactive-mode.ts:3797-3842）：工具执行的实时卡——开始追加、更新原地、
 * 结束换终态；已出终态的 tool result 条目不再重复渲染。
 */
class ToolExecutionStreamingTest {

    private static final Map<String, Object> ARGS = Map.of("command", "ls");

    @Test
    void liveCardAppearsUpdatesInPlaceAndFinalizesWithoutDuplication() {
        var screen = new ChatScreen();

        screen.onSessionEvent(new AgentSessionEvent.ToolExecutionStart("t1", "bash", ARGS));
        assertThat(screen.messageCount()).isEqualTo(1);
        assertThat(screen.lastMessage()).isInstanceOf(ChatMessage.ToolRunning.class);

        screen.onSessionEvent(new AgentSessionEvent.ToolExecutionUpdate(
            "t1", "bash", ARGS, ToolResult.success("partial")));
        assertThat(screen.messageCount()).isEqualTo(1);
        var running = (ChatMessage.ToolRunning) screen.lastMessage();
        assertThat(running.content()).hasSize(1);

        var finalResult = ToolResult.success("done");
        screen.onSessionEvent(new AgentSessionEvent.ToolExecutionEnd(
            "t1", "bash", finalResult, false, 5L));
        assertThat(screen.messageCount()).isEqualTo(1);
        assertThat(screen.lastMessage()).isEqualTo(
            new ChatMessage.ToolResult(finalResult.content(), false, 5L, "bash"));
    }

    @Test
    void theCommittedToolResultEntryIsSkippedAfterTheLiveCardReachedItsFinalState() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.ToolExecutionStart("t1", "bash", ARGS));
        screen.onSessionEvent(new AgentSessionEvent.ToolExecutionEnd(
            "t1", "bash", ToolResult.success("done"), false, 5L));
        assertThat(screen.messageCount()).isEqualTo(1);

        screen.onEntry(new Entry.Message("m2", 2L, "m1", Instant.EPOCH,
            new Message.ToolResultMessage("t1", "bash",
                List.of(new ContentBlock.TextContent("done")), false), null));

        assertThat(screen.messageCount()).isEqualTo(1);
    }

    @Test
    void nestedToolCallsStayInsideTheParentRow() {
        var screen = new ChatScreen();

        screen.onSessionEvent(new AgentSessionEvent.ToolExecutionStart(
            "t2", "read", Map.of(), "parent-1"));

        assertThat(screen.messageCount()).isZero();
    }

    @Test
    void toolRunningShowsElapsedAfterUpdateAndTookAfterEnd() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.ToolExecutionStart("t3", "bash", ARGS));
        screen.onSessionEvent(new AgentSessionEvent.ToolExecutionUpdate(
            "t3", "bash", ARGS, ToolResult.success("partial")));
        var running = (ChatMessage.ToolRunning) screen.lastMessage();
        assertThat(running.startNanos()).isNotNull();
        var lines = com.pijava.tui.component.MessageBubble.lines(running);
        assertThat(lines.stream().map(com.pijava.tui.component.LogicalLine::markup))
            .anyMatch(m -> m.contains("Elapsed "));

        screen.onSessionEvent(new AgentSessionEvent.ToolExecutionEnd(
            "t3", "bash", ToolResult.success("done"), false, 5_000L));
        assertThat(screen.messageCount()).isEqualTo(1);
        var result = (ChatMessage.ToolResult) screen.lastMessage();
        var resultLines = com.pijava.tui.component.MessageBubble.lines(result);
        assertThat(resultLines.stream().map(com.pijava.tui.component.LogicalLine::markup))
            .anyMatch(m -> m.contains("Took 5.0s"));
    }

    @Test
    void multipleToolUpdatesReplaceTheSameIndexWithoutExtraCards() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.ToolExecutionStart("t4", "bash", ARGS));
        screen.onSessionEvent(new AgentSessionEvent.ToolExecutionUpdate(
            "t4", "bash", ARGS, ToolResult.success("p1")));
        screen.onSessionEvent(new AgentSessionEvent.ToolExecutionUpdate(
            "t4", "bash", ARGS, ToolResult.success("p2")));
        screen.onSessionEvent(new AgentSessionEvent.ToolExecutionUpdate(
            "t4", "bash", ARGS, ToolResult.success("p3")));
        assertThat(screen.messageCount()).isEqualTo(1);
        assertThat(screen.lastMessage()).isInstanceOf(ChatMessage.ToolRunning.class);
    }

    @Test
    void toolDurationsTimerTextCoversBothArms() {
        assertThat(com.pijava.tui.component.ToolDurations.timerText(true, null, 1200L))
            .isEqualTo("Elapsed 1.2s");
        assertThat(com.pijava.tui.component.ToolDurations.timerText(false, 5000L, 0L))
            .isEqualTo("Took 5.0s");
    }

    @Test
    void updateWithoutAStartIsIgnored() {
        var screen = new ChatScreen();

        screen.onSessionEvent(new AgentSessionEvent.ToolExecutionUpdate(
            "missing", "bash", ARGS, ToolResult.success("partial")));
        screen.onSessionEvent(new AgentSessionEvent.ToolExecutionEnd(
            "missing", "bash", ToolResult.success("done"), false, 5L));

        assertThat(screen.messageCount()).isZero();
    }
}
