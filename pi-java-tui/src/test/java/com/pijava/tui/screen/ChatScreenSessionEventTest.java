package com.pijava.tui.screen;

import java.util.List;

import com.pijava.agent.harness.SessionSnapshot;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.core.AgentSessionEvent.CompactionReason;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.StatusIndicator;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.ElementRegistry;
import dev.tamboui.toolkit.event.EventRouter;
import dev.tamboui.toolkit.focus.FocusManager;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包⑤（TUI 重试面）的定点用例：会话事件 → 指示器槽 / 聊天区。
 *
 * <p>⚠️ L5 差分<b>结构上</b>覆盖不到这里的任何一条 —— 一切宿主渲染（状态栏文本、
 * 聊天区追加）都在差分边界之外（docs/31 §8.32.5 第 3 条）⇒ L5 全绿不构成
 * 本文件任何一条断言成立的证据，每条都得在这里钉死。</p>
 *
 * <p>文本断言<b>逐字符</b>对齐 pi 的格式串（{@code components/status-indicator.ts}
 * 与 {@code interactive-mode.ts:3449-3503}）。</p>
 */
class ChatScreenSessionEventTest {

    /** 静态指示器的文本（含倒计时的变体另外按截止时刻算，见下）。 */
    private static String text(ChatScreen screen) {
        var indicator = screen.indicator();
        return indicator == null ? null : indicator.textAt(0L);
    }

    @Test
    void autoRetryStartShowsPiRetryTextWithCeilSeconds() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.AutoRetryStart(1, 3, 4500L, "boom"));

        var retry = (StatusIndicator.Retry) screen.indicator();
        // 首帧秒数 = ceil(4500/1000) = 5（pi 用 Math.ceil，不是 floor）
        assertThat(retry.textAt(System.nanoTime()))
            .isEqualTo("Retrying (1/3) in 5s... (esc to cancel)");
        // 每过一秒退一格（pi CountdownTimer 的 1 Hz setInterval 语义）
        assertThat(retry.textAt(retry.deadlineNanos() - 3_000L * 1_000_000))
            .isEqualTo("Retrying (1/3) in 3s... (esc to cancel)");
        // 到点即 0（pi 在 remaining <= 0 时 dispose，末帧就是 onTick(0)）
        assertThat(retry.textAt(retry.deadlineNanos() + 5_000L * 1_000_000))
            .isEqualTo("Retrying (1/3) in 0s... (esc to cancel)");
    }

    @Test
    void theCancelHintFollowsTheConfiguredInterruptKey() {
        var screen = new ChatScreen();
        screen.setInterruptHint("ctrl+c");
        screen.onSessionEvent(new AgentSessionEvent.AutoRetryStart(1, 3, 1000L, "boom"));

        var retry = (StatusIndicator.Retry) screen.indicator();
        assertThat(retry.textAt(System.nanoTime()))
            .isEqualTo("Retrying (1/3) in 1s... (ctrl+c to cancel)");
    }

    @Test
    void autoRetryEndClearsTheIndicatorAndReportsOnlyFinalFailure() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.AutoRetryStart(1, 2, 1000L, "boom"));
        screen.onSessionEvent(new AgentSessionEvent.AutoRetryEnd(false, 2, "still down"));

        assertThat(screen.indicator()).isNull();
        assertThat(screen.lastMessage())
            .isEqualTo(new ChatMessage.Error("Error: Retry failed after 2 attempts: still down"));
    }

    @Test
    void autoRetryEndOnSuccessWritesNothing() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.AutoRetryStart(1, 2, 1000L, "boom"));
        screen.onSessionEvent(new AgentSessionEvent.AutoRetryEnd(true, 1, null));

        assertThat(screen.indicator()).isNull();
        // 成功就是正常响应，不进聊天区（pi：只在 !success 时 showError）
        assertThat(screen.messages()).isEmpty();
    }

    @Test
    void emptyFinalErrorFallsBackToUnknownError() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.AutoRetryEnd(false, 3, ""));

        // pi: event.finalError || "Unknown error"（空串是 falsy）
        assertThat(screen.lastMessage())
            .isEqualTo(new ChatMessage.Error("Error: Retry failed after 3 attempts: Unknown error"));
    }

    @Test
    void clearingAnotherKindLeavesTheActiveIndicatorAlone() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.AutoRetryStart(1, 3, 4000L, "boom"));

        // pi clearStatusIndicator 的第一句守卫：kind 不匹配即 no-op
        screen.clearIndicator(StatusIndicator.Kind.COMPACTION);
        assertThat(screen.indicator()).isInstanceOf(StatusIndicator.Retry.class);

        screen.clearIndicator(StatusIndicator.Kind.RETRY);
        assertThat(screen.indicator()).isNull();
    }

    @Test
    void clearingRetryDoesNotKillTheCompactionIndicator() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.CompactionStart(CompactionReason.MANUAL));

        screen.clearIndicator(StatusIndicator.Kind.RETRY);
        assertThat(text(screen)).isEqualTo("Compacting context... (esc to cancel)");
    }

    @Test
    void compactionTextFollowsPiPerReason() {
        var screen = new ChatScreen();

        screen.onSessionEvent(new AgentSessionEvent.CompactionStart(CompactionReason.MANUAL));
        assertThat(text(screen)).isEqualTo("Compacting context... (esc to cancel)");

        screen.onSessionEvent(new AgentSessionEvent.CompactionStart(CompactionReason.THRESHOLD));
        assertThat(text(screen)).isEqualTo("Auto-compacting... (esc to cancel)");

        screen.onSessionEvent(new AgentSessionEvent.CompactionStart(CompactionReason.OVERFLOW));
        assertThat(text(screen))
            .isEqualTo("Context overflow detected, Auto-compacting... (esc to cancel)");

        screen.onSessionEvent(new AgentSessionEvent.CompactionEnd(
            CompactionReason.OVERFLOW, null, false, false, null));
        assertThat(screen.indicator()).isNull();
    }

    @Test
    void summarizationRetryScheduledShowsTheErrorAndTheRetryIndicator() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.SummarizationRetryScheduled(
            1, 2, 3000L, "summarization failed"));

        assertThat(screen.lastMessage())
            .isEqualTo(new ChatMessage.Error("Error: summarization failed"));
        assertThat(screen.indicator()).isInstanceOf(StatusIndicator.Retry.class);
    }

    @Test
    void summarizationRetryAttemptStartSwapsToTheCompactionIndicator() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.SummarizationRetryScheduled(
            1, 2, 3000L, "x"));
        screen.onSessionEvent(new AgentSessionEvent.SummarizationRetryAttemptStart(
            "compaction", "threshold"));
        assertThat(text(screen)).isEqualTo("Auto-compacting... (esc to cancel)");

        // 收尾的 finished 清的是 retry，槽里是 compaction ⇒ 守卫拦住（pi 同）
        screen.onSessionEvent(new AgentSessionEvent.SummarizationRetryFinished());
        assertThat(text(screen)).isEqualTo("Auto-compacting... (esc to cancel)");
    }

    @Test
    void summarizationRetryAttemptStartBranchesToTheBranchSummaryIndicator() {
        // ⚠️ 今天不可达：branch summary 在 pi-java 无实现（台账 B1），source 恒为
        // "compaction"。这里只钉文本，不假装这条路可达（docs/31 §8.38.7-D）。
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.SummarizationRetryAttemptStart(
            "branchSummary", null));

        assertThat(text(screen)).isEqualTo("Summarizing branch... (esc to cancel)");
        assertThat(screen.indicator().kind()).isEqualTo(StatusIndicator.Kind.BRANCH_SUMMARY);
    }

    @Test
    void onlyTheRetryIndicatorTicks() {
        var screen = new ChatScreen();
        assertThat(screen.hasTickingIndicator()).isFalse();

        screen.onSessionEvent(new AgentSessionEvent.AutoRetryStart(1, 3, 1000L, "x"));
        assertThat(screen.hasTickingIndicator()).isTrue();

        // 无倒计时的指示器不唤醒：inline 模式只在重试窗口里每秒重绘
        screen.onSessionEvent(new AgentSessionEvent.CompactionStart(CompactionReason.MANUAL));
        assertThat(screen.hasTickingIndicator()).isFalse();
    }

    @Test
    void unrelatedSessionEventsAreIgnored() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.AgentSettled());
        screen.onSessionEvent(new AgentSessionEvent.UserMessageReceived(null));

        assertThat(screen.indicator()).isNull();
        assertThat(screen.messages()).isEmpty();
    }

    @Test
    void theIndicatorWinsOverTheSnapshotInTheStatusBar() throws Exception {
        var screen = new ChatScreen();
        screen.updateSnapshot(new SessionSnapshot(
            "demo", "google/gemini-2.5-flash", "running",
            123L, 2, List.of(), List.of()));

        screen.onSessionEvent(new AgentSessionEvent.AutoRetryStart(1, 3, 4500L, "boom"));

        assertThat(renderRow(screen.statusBar(), 60)).contains("Retrying (1/3) in 5s...");
        assertThat(renderRow(screen.statusBar(), 60)).doesNotContain("demo");
    }

    /** Renders one row into a Buffer and returns it as plain text. */
    private static String renderRow(Element element, int width) throws Exception {
        var buffer = Buffer.empty(new Rect(0, 0, width, 1));
        var frame = Frame.forTesting(buffer);
        var focus = new FocusManager();
        markRenderThread(true);
        try {
            element.render(frame, new Rect(0, 0, width, 1),
                new DefaultRenderContext(focus, new EventRouter(focus, new ElementRegistry())));
        } finally {
            markRenderThread(false);
        }
        var out = new StringBuilder();
        for (int x = 0; x < width; x++) {
            var cell = buffer.get(x, 0);
            if (cell.isContinuation()) {
                continue;
            }
            out.append(cell.symbol());
        }
        return out.toString().stripTrailing();
    }

    /** TamboUI asserts element registration happens on the render thread. */
    private static void markRenderThread(boolean on) throws Exception {
        var method = dev.tamboui.tui.RenderThread.class.getDeclaredMethod(
            on ? "markAsRenderThread" : "clearRenderThread");
        method.setAccessible(true);
        method.invoke(null);
    }
}
