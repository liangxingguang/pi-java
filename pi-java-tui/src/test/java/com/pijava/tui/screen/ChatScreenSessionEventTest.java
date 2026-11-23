package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.harness.SessionSnapshot;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.core.AgentSessionEvent.CompactionReason;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.MetaKind;
import com.pijava.tui.component.PendingQueueView;
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
 * 聊天区追加）都在差分边界之外（原 docs/31 §8.32.5 第 3 条）⇒ L5 全绿不构成
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
            CompactionReason.OVERFLOW, null, false, false, null, null));
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
        // "compaction"。这里只钉文本，不假装这条路可达（原 docs/31 §8.38.7-D）。
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.SummarizationRetryAttemptStart(
            "branchSummary", null));

        assertThat(text(screen)).isEqualTo("Summarizing branch... (esc to cancel)");
        assertThat(screen.indicator().kind()).isEqualTo(StatusIndicator.Kind.BRANCH_SUMMARY);
    }

    @Test
    void onlyAnimatedIndicatorsTick() {
        var screen = new ChatScreen();
        assertThat(screen.hasTickingIndicator()).isFalse();

        screen.onSessionEvent(new AgentSessionEvent.AutoRetryStart(1, 3, 1000L, "x"));
        assertThat(screen.hasTickingIndicator()).isTrue();

        // L7 W1（docs/22 §4.2）：pi 四 kind 全是 Loader ⇒ 全带 spinner ⇒ 全唤醒
        // （inline 12.5Hz 重绘本就是 pi 行为；倒计时按帧重算取值不变）。
        screen.onSessionEvent(new AgentSessionEvent.CompactionStart(CompactionReason.MANUAL));
        assertThat(screen.hasTickingIndicator()).isTrue();

        // 清空后不再唤醒。
        screen.clearIndicator(StatusIndicator.Kind.COMPACTION);
        assertThat(screen.hasTickingIndicator()).isFalse();
    }

    @Test
    void unrelatedSessionEventsAreIgnored() {
        var screen = new ChatScreen();
        // 批 2：新事件面（漏斗五帧＋agent_settled{aborted}）落 default 忽略。
        // （L7 W1：TurnStart 置 working 指示器，另有专测——不再算「无关」。）
        var user = new com.pijava.ai.message.Message.UserMessage(
            List.of(new com.pijava.ai.message.ContentBlock.TextContent("hi")));
        screen.onSessionEvent(new AgentSessionEvent.AgentSettled(false));
        screen.onSessionEvent(new AgentSessionEvent.AgentStart());
        screen.onSessionEvent(new AgentSessionEvent.MessageStart(user));
        screen.onSessionEvent(new AgentSessionEvent.MessageEnd(user));

        assertThat(screen.indicator()).isNull();
        assertThat(screen.messages()).isEmpty();
    }

    // ── L7 W1（docs/22 §6）：working 指示器 ──────────────────────

    @Test
    void turnStartShowsTheWorkingIndicatorWithDefaultMessage() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.TurnStart());

        var working = (StatusIndicator.Working) screen.indicator();
        assertThat(working.kind()).isEqualTo(StatusIndicator.Kind.WORKING);
        assertThat(working.textAt(0L)).isEqualTo("Working");
        assertThat(working.frames()).isEqualTo(com.pijava.tui.component.LoaderSpinner.DEFAULT_FRAMES);
        assertThat(working.spinnerFrameAt(working.startNanos() + 0L))
            .isEqualTo(com.pijava.tui.component.LoaderSpinner.DEFAULT_FRAMES.get(0));
        assertThat(screen.hasTickingIndicator()).isTrue();
    }

    @Test
    void turnStartKeepsAnActiveWorkingIndicatorInsteadOfRestartingTheAnimation() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.TurnStart());
        var first = (StatusIndicator.Working) screen.indicator();

        screen.onSessionEvent(new AgentSessionEvent.TurnStart());

        assertThat(screen.indicator()).isSameAs(first);
    }

    @Test
    void agentEndClearsWorkingButNotAConcurrentCompaction() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.TurnStart());
        screen.onSessionEvent(new AgentSessionEvent.AgentEnd(List.of(), false));
        assertThat(screen.indicator()).isNull();

        // compaction 在 agent_end 期间并发：kind 守卫拦住（pi clearStatusIndicator("working")）
        screen.onSessionEvent(new AgentSessionEvent.CompactionStart(CompactionReason.OVERFLOW));
        screen.onSessionEvent(new AgentSessionEvent.AgentEnd(List.of(), false));
        assertThat(text(screen))
            .isEqualTo("Context overflow detected, Auto-compacting... (esc to cancel)");
    }

    @Test
    void invisibleWorkingClearsEveryIndicatorOnTurnStart() {
        var screen = new ChatScreen();
        screen.setWorkingVisible(false);
        screen.onSessionEvent(new AgentSessionEvent.AutoRetryStart(1, 3, 1000L, "x"));

        // pi turn_start 的 else 臂：workingVisible=false ⇒ clearStatusIndicator() 全清。
        screen.onSessionEvent(new AgentSessionEvent.TurnStart());
        assertThat(screen.indicator()).isNull();

        screen.setWorkingVisible(true);
        screen.onSessionEvent(new AgentSessionEvent.TurnStart());
        assertThat(screen.indicator().kind()).isEqualTo(StatusIndicator.Kind.WORKING);
    }

    @Test
    void extensionWorkingMessageAndFramesFlowIntoTheIndicator() {
        var screen = new ChatScreen();
        screen.setWorkingMessage("Summarizing");
        screen.setWorkingIndicator(new com.pijava.coding.agent.extension.WorkingIndicatorOptions(
            List.of("●"), 120L));

        screen.onSessionEvent(new AgentSessionEvent.TurnStart());

        var working = (StatusIndicator.Working) screen.indicator();
        assertThat(working.textAt(0L)).isEqualTo("Summarizing");
        assertThat(working.frames()).containsExactly("●");
        assertThat(working.intervalMs()).isEqualTo(120L);
        // 单帧静态：任意时刻取值不变（pi 无定时器分支）。
        assertThat(working.spinnerFrameAt(working.startNanos() + 999_999_999L)).isEqualTo("●");
    }

    @Test
    void emptyFramesHideTheSpinnerSymbolButKeepTheMessage() {
        var screen = new ChatScreen();
        screen.setWorkingIndicator(new com.pijava.coding.agent.extension.WorkingIndicatorOptions(
            List.of(), 80L));
        screen.onSessionEvent(new AgentSessionEvent.TurnStart());

        var working = (StatusIndicator.Working) screen.indicator();
        assertThat(working.textAt(0L)).isEqualTo("Working");
        assertThat(working.spinnerFrameAt(working.startNanos() + 500_000_000L)).isEmpty();
        // 无动画 ⇒ 不唤醒（inline 无需周期重绘）。
        assertThat(screen.hasTickingIndicator()).isFalse();
    }

    @Test
    void workingMessageSwapKeepsTheAnimationStart() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.TurnStart());
        var before = (StatusIndicator.Working) screen.indicator();

        screen.setWorkingMessage("Thinking");
        var after = (StatusIndicator.Working) screen.indicator();

        assertThat(after.textAt(0L)).isEqualTo("Thinking");
        assertThat(after.startNanos()).isEqualTo(before.startNanos());
    }

    @Test
    void resetWorkingStateRestoresDefaultsAndTheInterruptSuffix() {
        var screen = new ChatScreen();
        screen.setInterruptHint("ctrl+c");
        screen.setWorkingMessage("custom");
        screen.setWorkingVisible(false);
        screen.setWorkingIndicator(new com.pijava.coding.agent.extension.WorkingIndicatorOptions(
            List.of("▸"), 50L));
        screen.onSessionEvent(new AgentSessionEvent.TurnStart());
        assertThat(screen.indicator()).isNull();   // invisible ⇒ 清

        screen.setWorkingVisible(true);
        screen.onSessionEvent(new AgentSessionEvent.TurnStart());
        // pi resetExtensionUI（:2673-2682）的 quirk 逐字：复位后 message 带中断后缀。
        screen.resetExtensionUi();
        var working = (StatusIndicator.Working) screen.indicator();
        assertThat(working.textAt(0L)).isEqualTo("Working (ctrl+c to interrupt)");
        assertThat(working.frames())
            .isEqualTo(com.pijava.tui.component.LoaderSpinner.DEFAULT_FRAMES);
    }

    @Test
    void theWorkingIndicatorWinsOverTheSnapshotInTheStatusBar() throws Exception {
        var screen = new ChatScreen();
        screen.updateSnapshot(new SessionSnapshot(
            "demo", "google/gemini-2.5-flash", "running",
            123L, 2, List.of(), List.of()));
        screen.onSessionEvent(new AgentSessionEvent.TurnStart());

        var row = renderRow(screen.statusBar(), 60);
        assertThat(row).contains("Working");
        // spinner 帧在场（braille 10 帧之一），且静态 ● running 不再出现在状态栏。
        var braille = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";
        assertThat(braille.chars().anyMatch(c -> row.indexOf(Character.toString(c)) >= 0)).isTrue();
        assertThat(row).doesNotContain("demo");
        assertThat(row).doesNotContain("\u25CF running");
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

    @Test
    void pendingQueueViewHoldsUpdateTextsAndRendersOnlyWhenNonEmpty() {
        var view = new PendingQueueView();
        assertThat(view.isEmpty()).isTrue();
        assertThat(view.render()).isNull();

        view.update(List.of("a", "b"), List.of("c"));
        assertThat(view.steering()).containsExactly("a", "b");
        assertThat(view.followUp()).containsExactly("c");
        assertThat(view.isEmpty()).isFalse();
    }

    @Test
    void queueUpdateRendersPendingRowsAndEmptyUpdateRemovesThem() throws Exception {
        // B175（原 docs/26 §3.6）：pending 区在聊天区与编辑器之间。
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.QueueUpdate(
            List.of("turn here"), List.of("summarize later")));

        var rows = renderText(screen.render(), 100, 40);
        assertThat(rows).anyMatch(r -> r.contains("Steering: turn here"));
        assertThat(rows).anyMatch(r -> r.contains("Follow-up: summarize later"));

        screen.onSessionEvent(new AgentSessionEvent.QueueUpdate(List.of(), List.of()));
        rows = renderText(screen.render(), 100, 40);
        assertThat(rows)
            .noneMatch(r -> r.contains("Steering") || r.contains("Follow-up"));
    }

    @Test
    void flashStatusAppendsDimSystemBubble() {
        var screen = new ChatScreen();
        screen.onSessionEvent(new AgentSessionEvent.FlashStatus(
            "Queued message for after compaction"));

        assertThat(screen.lastMessage())
            .isEqualTo(new ChatMessage.System(
                "Queued message for after compaction", MetaKind.GENERIC));
    }

    /** Renders the tree into a buffer and returns its rows as plain text. */
    private static List<String> renderText(Element element, int width, int height)
            throws Exception {
        var buffer = Buffer.empty(new Rect(0, 0, width, height));
        var frame = Frame.forTesting(buffer);
        var focus = new FocusManager();
        markRenderThread(true);
        try {
            element.render(frame, new Rect(0, 0, width, height),
                new DefaultRenderContext(focus, new EventRouter(focus, new ElementRegistry())));
        } finally {
            markRenderThread(false);
        }
        var rows = new ArrayList<String>();
        for (int y = 0; y < height; y++) {
            var row = new StringBuilder();
            for (int x = 0; x < width; x++) {
                var cell = buffer.get(x, y);
                if (!cell.isContinuation()) {
                    row.append(cell.symbol());
                }
            }
            rows.add(row.toString().stripTrailing());
        }
        return rows;
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
