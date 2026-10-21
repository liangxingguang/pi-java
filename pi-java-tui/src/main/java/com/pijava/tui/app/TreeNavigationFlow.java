package com.pijava.tui.app;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.agent.harness.SessionSnapshot;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.component.StatusIndicator;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.CustomInstructionsScreen;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.screen.SummaryChoiceScreen;
import com.pijava.tui.screen.TreeSelectorScreen;
import com.pijava.tui.util.TuiEventDispatcher;

/**
 * {@code /tree} 树导航编排（docs/18 §3.9，B245）—— pi 树选择器 onSelect 回调
 * （interactive-mode.ts:5560-5690）的对位：
 *
 * <ol>
 *   <li>树选择器（lane 列表形，窄差 D37）；选中＝当前叶 ⇒ "Already at this point"
 *       （pi :5578 文案逐字）；</li>
 *   <li>{@code branchSummary.skipPrompt} 为 true ⇒ 直进导航（pi :5590 门）；</li>
 *   <li>否则三选对话框（Esc ⇒ 重开树选择器，pi :5599）⇒ 可选自定义指令编辑器
 *       （取消 ⇒ 回三选，pi :5610 continue）；</li>
 *   <li>导航整体跑 <b>worker 虚拟线程</b>（裁决④：摘要生成是同步阻塞 LLM 调用，
 *       绝不可跑渲染线程）：运行中先 abort＋waitForIdle（pi :5619-5621）、重查
 *       isCompacting（pi :5624-5629）、摘要期指示器置/清（pi :5640＋finally）、
 *       全部 UI 写经 dispatcher 回渲染线程。</li>
 * </ol>
 *
 * <p>Esc 换绑不移植（裁决③）：java 的 Esc＝INTERRUPT⇒{@code session.abort()}，
 * 首步即 {@code abortBranchSummary()}，摘要期行为已与 pi 换绑后等价。
 * {@code restoreQueuedMessagesToEditor} 未随本批（无既有取用面，随窄差登记）。</p>
 */
public final class TreeNavigationFlow {

    private final AgentSession session;
    private final ChatScreen chatScreen;
    private final TuiEventDispatcher dispatcher;
    private final Supplier<SessionSnapshot> snapshot;
    private final Consumer<ScreenOverlay> openOverlay;

    /**
     * @param session     当前会话（构造时捕获；切换会话后应重建 flow）
     * @param chatScreen  聊天区（全部写入须在渲染线程——内部经 dispatcher 排回）
     * @param dispatcher  跨线程派发器
     * @param snapshot    树选择器的 lane 列表来源（现取，重开时反映新叶）
     * @param openOverlay overlay 开关（{@code PiTuiApp.openOverlay} 对位）
     */
    public TreeNavigationFlow(AgentSession session, ChatScreen chatScreen,
                              TuiEventDispatcher dispatcher,
                              Supplier<SessionSnapshot> snapshot,
                              Consumer<ScreenOverlay> openOverlay) {
        this.session = session;
        this.chatScreen = chatScreen;
        this.dispatcher = dispatcher;
        this.snapshot = snapshot;
        this.openOverlay = openOverlay;
    }

    /** {@code /tree} 入口：开树选择器（导航模式）。 */
    public void begin() {
        openTree(null);
    }

    /** 重开树选择器并恢复选中（pi {@code showTreeSelector(entryId)}，:5599/:5663）。 */
    public void reopen(String selectedId) {
        openTree(selectedId);
    }

    private void openTree(String initialSelectedId) {
        var snap = snapshot.get();
        if (snap == null) {
            chatScreen.appendSystemText("No session snapshot yet.");
            return;
        }
        openOverlay.accept(new TreeSelectorScreen(snap, initialSelectedId,
            this::onTreeSelected,
            () -> { })); // Esc：pi 的 onCancel 只关选择器，无后续
    }

    private void onTreeSelected(String entryId) {
        // pi :5574-5579：选中当前叶 ⇒ no-op（文案逐字【模型可见】）。
        if (entryId.equals(currentLeafId())) {
            chatScreen.appendSystemText("Already at this point");
            return;
        }
        // pi :5590：skipPrompt 门（branchSummary.skipPrompt 默认 false）。
        if (session.services().settings().accessors().getBranchSummarySkipPrompt()) {
            startNavigation(entryId, false, null);
            return;
        }
        openSummaryChoice(entryId);
    }

    private void openSummaryChoice(String entryId) {
        openOverlay.accept(new SummaryChoiceScreen(choice -> {
            if (choice == null) {
                reopen(entryId); // pi :5599：Esc ⇒ 重开树选择器
                return;
            }
            switch (choice) {
                case NO_SUMMARY -> startNavigation(entryId, false, null);
                case SUMMARIZE -> startNavigation(entryId, true, null);
                case CUSTOM -> openOverlay.accept(new CustomInstructionsScreen(text -> {
                    if (text == null) {
                        openSummaryChoice(entryId); // pi :5610：编辑器取消 ⇒ 回三选
                    } else {
                        startNavigation(entryId, true, text);
                    }
                }));
            }
        }));
    }

    /**
     * worker 虚拟线程上的导航本体（裁决④）。pi :5618-5684 逐字序：abort＋
     * 重查 ⇒ 指示器 ⇒ navigateTree ⇒ 结局四分（aborted/cancelled/成功/catch）
     * ＋ finally 清指示器。
     */
    private void startNavigation(String entryId, boolean wantsSummary,
                                 String customInstructions) {
        Thread.startVirtualThread(() -> {
            if (session.isRunning()) { // pi :5619-5621
                session.abort();
                session.harness().waitForIdle(session.laneName()); // pi 的 await abort()
            }
            if (session.isCompacting()) { // pi :5624-5629 重查（错文第二句逐字【模型可见】）
                dispatcher.dispatch(() -> chatScreen.showError(
                    "Wait for the current compaction or tree navigation to finish"
                        + " before navigating the session tree."));
                return;
            }
            if (wantsSummary) {
                dispatcher.dispatch(chatScreen::showBranchSummaryIndicator); // pi :5640
            }
            try {
                var nav = session.navigateTree(entryId, wantsSummary, customInstructions,
                    false, null);
                dispatcher.dispatch(() -> {
                    if (nav.aborted()) { // pi :5659-5664
                        chatScreen.appendSystemText("Branch summarization cancelled");
                        reopen(entryId);
                        return;
                    }
                    if (nav.cancelled()) { // pi :5665-5668
                        chatScreen.appendSystemText("Navigation cancelled");
                        return;
                    }
                    // pi :5671-5672：整表重建。
                    chatScreen.rebuildTranscript(session.accumulatedEntries(), currentLeafId());
                    if (nav.editorText() != null && chatScreen.isInputEmpty()) {
                        chatScreen.insertText(nav.editorText()); // pi :5673-5675
                    }
                    chatScreen.appendSystemText("Navigated to selected point");
                });
            } catch (Exception e) {
                dispatcher.dispatch(() -> chatScreen.showError(
                    e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
            } finally {
                if (wantsSummary) {
                    dispatcher.dispatch(() ->
                        chatScreen.clearIndicator(StatusIndicator.Kind.BRANCH_SUMMARY));
                }
            }
        });
    }

    private String currentLeafId() {
        return session.harness().snapshot(session.laneName()).leafId();
    }
}
