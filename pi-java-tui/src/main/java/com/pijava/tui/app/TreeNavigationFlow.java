package com.pijava.tui.app;

import java.util.function.Consumer;
import java.util.function.IntSupplier;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.tui.component.StatusIndicator;
import com.pijava.tui.component.tree.FilterMode;
import com.pijava.tui.component.tree.TreeSelectorOverlay;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.CustomInstructionsScreen;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.screen.SummaryChoiceScreen;
import com.pijava.tui.util.ClipboardSupport;
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
    private final Consumer<ScreenOverlay> openOverlay;
    private final IntSupplier terminalRows;
    private final IntSupplier terminalCols;
    private final KeybindingsManager keybindings;

    /**
     * @param session      当前会话（构造时捕获；切换会话后应重建 flow）
     * @param chatScreen   聊天区（全部写入须在渲染线程——内部经 dispatcher 排回）
     * @param dispatcher   跨线程派发器
     * @param openOverlay  overlay 开关（{@code PiTuiApp.openOverlay} 对位）
     * @param terminalRows 终端行数源（maxVisibleLines，pi {@code ui.terminal.rows}）
     * @param terminalCols 终端列数源（水平视口宽度）
     * @param keybindings  键位表（app.tree.*／app.message.copy）
     */
    public TreeNavigationFlow(AgentSession session, ChatScreen chatScreen,
                              TuiEventDispatcher dispatcher,
                              Consumer<ScreenOverlay> openOverlay,
                              IntSupplier terminalRows, IntSupplier terminalCols,
                              KeybindingsManager keybindings) {
        this.session = session;
        this.chatScreen = chatScreen;
        this.dispatcher = dispatcher;
        this.openOverlay = openOverlay;
        this.terminalRows = terminalRows;
        this.terminalCols = terminalCols;
        this.keybindings = keybindings;
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
        // docs/19 §3.9：选择器本体＝entry 树（D37 收编；pi showTreeSelector
        // 的唯一构造点 interactive-mode.ts:5559-5707 对位）。
        var tree = session.getTree();
        if (tree.isEmpty()) {   // pi :5563-5566：空树不打开
            chatScreen.appendSystemText("No entries in session");
            return;
        }
        // pi :5572：初值过滤读全局 settings（选择器内切换不回写；非法值回退
        // default——pi settings-manager 读时校验对位）。
        var mode = FilterMode.parse(
            session.services().settings().accessors().getTreeFilterMode());
        openOverlay.accept(new TreeSelectorOverlay(
            tree, treeLeafId(), terminalRows.getAsInt(),
            initialSelectedId, mode,
            this::onTreeSelected,
            () -> { }, // Esc：pi 的 onCancel 只关选择器，无后续
            session::setLabel, this::onCopySelected, keybindings, terminalCols));
    }

    /**
     * 树选择器语义叶（pi {@code getLeafId()} 的 UX 对位）：java 每回合把
     * {@code usage} 记账行链上车道（D6 既有方言，选择器里恒隐），pi 无此
     * 条目——pi 的「当前位置」对位到 java 的**最近一条非 usage 条目**。
     * 不折算的后果：/tree 初始选中恒落在叶（usage）的可见祖先上，直接
     * Enter 不会触发 "Already at this point" 而是弹三选（与 pi 主流分叉）。
     */
    private String treeLeafId() {
        String leaf = currentLeafId();
        if (leaf == null) {
            return null;
        }
        var byId = new java.util.HashMap<String, com.pijava.agent.entry.Entry>();
        for (var entry : session.accumulatedEntries()) {
            byId.put(entry.id(), entry);
        }
        String current = leaf;
        while (current != null) {
            var entry = byId.get(current);
            if (entry == null || !(entry instanceof com.pijava.agent.entry.Entry.Usage)) {
                return current;
            }
            current = entry.parentId();
        }
        return null;
    }

    /** pi onCopy（:5595-5607）逐字三结局【模型可见】。 */
    private void onCopySelected(String text) {
        if (text == null) {
            chatScreen.showError("Selected entry has no text to copy");
            return;
        }
        try {
            ClipboardSupport.copy(text);
            chatScreen.appendSystemText("Copied selected message to clipboard");
        } catch (Exception e) {
            chatScreen.showError(e.getMessage());
        }
    }

    private void onTreeSelected(String entryId) {
        // pi :5574-5579：选中当前叶 ⇒ no-op（文案逐字【模型可见】）。
        // 叶取树选择器语义叶（usage 记账行折算——见 treeLeafId）。
        if (entryId.equals(treeLeafId())) {
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
