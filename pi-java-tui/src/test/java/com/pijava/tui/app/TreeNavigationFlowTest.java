package com.pijava.tui.app;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.pijava.agent.entry.Entry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.StatusIndicator;
import com.pijava.tui.screen.CustomInstructionsScreen;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.SummaryChoiceScreen;
import com.pijava.tui.component.tree.FilterMode;
import com.pijava.tui.component.tree.TreeSelectorOverlay;
import com.pijava.tui.util.ClipboardSupport;
import com.pijava.tui.support.ScriptedChatProvider;
import com.pijava.tui.util.TuiEventDispatcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * docs/18（B245，§3.9）TUI 树导航流对拍（pi interactive-mode.ts:5560-5690）：
 * 不跑 runner，overlay 直驱（喂键 → isDone → apply，worker 结局经真 dispatcher
 * 排干后断言）。摘要/压缩的 LLM 调用由 {@link ScriptedChatProvider} 闩门控，
 * 时序确定。
 */
class TreeNavigationFlowTest {

    /** 探针扩展：session_before_tree 恒 cancel（④b）。 */
    public static final class CancelProbe implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public CancelProbe() {}

        @Override public String name() { return "cancel-probe"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.on(ExtensionEventSpec.SESSION_BEFORE_TREE,
                (e, c) -> new ExtensionResults.SessionBeforeTreeResult(
                    true, null, null, null, null));
        }
    }

    @TempDir
    Path tmp;

    private AgentSession session;
    private com.pijava.coding.agent.modes.InteractiveMode mode;
    private ChatScreen chatScreen;
    private TuiEventDispatcher dispatcher;
    private ScriptedChatProvider provider;
    private final AtomicReference<ScreenOverlay> overlayRef = new AtomicReference<>();
    private String targetEntryId; // 第二条 user entry（导航目标）
    private ClassLoader previousTccl;

    @AfterEach
    void tearDown() {
        ClipboardSupport.overrideForTest(null); // 防静态替换点跨用例泄漏
        if (previousTccl != null) {
            Thread.currentThread().setContextClassLoader(previousTccl);
            previousTccl = null;
        }
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private void newRig(boolean skipPrompt) throws Exception {
        newRig(skipPrompt, false);
    }

    private void newRig(boolean skipPrompt, boolean installCancelProbe) throws Exception {
        newRig(skipPrompt, installCancelProbe, true, null);
    }

    private void newRig(boolean skipPrompt, boolean installCancelProbe,
                        boolean drivePrompts, String treeFilterMode) throws Exception {
        newRig(skipPrompt, installCancelProbe, drivePrompts, treeFilterMode, false);
    }

    private void newRig(boolean skipPrompt, boolean installCancelProbe,
                        boolean drivePrompts, String treeFilterMode,
                        boolean noSession) throws Exception {
        // TCCL 注入：provider factory（恒——公共 create 的 ServiceLoader 发现通道）
        // ＋ cancel 探针扩展（按需）。
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.ai.provider.ProviderFactory"),
            ScriptedChatProvider.Factory.class.getName() + "\n");
        if (installCancelProbe) {
            Files.writeString(
                servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
                CancelProbe.class.getName() + "\n");
        }
        Path testClasses = Path.of(getClass().getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(
            new java.net.URL[] {tmp.toUri().toURL(), testClasses.toUri().toURL()},
            previousTccl));
        // FileSettingsStorage 在构造时捕获 user.home（CompactSlashCommandTest 先例）
        // —— 窗口只包住 create，设置读写全落临时 home，不碰真实用户目录。
        var home = Files.createTempDirectory("pi-flow-home");
        String settingsJson = null;
        if (skipPrompt && treeFilterMode != null) {
            settingsJson = "{\"branchSummary\":{\"skipPrompt\":true},\"treeFilterMode\":\""
                + treeFilterMode + "\"}";
        } else if (skipPrompt) {
            settingsJson = "{\"branchSummary\":{\"skipPrompt\":true}}";
        } else if (treeFilterMode != null) {
            settingsJson = "{\"treeFilterMode\":\"" + treeFilterMode + "\"}";
        }
        if (settingsJson != null) {
            var agentDir = Files.createDirectories(home.resolve(".pi-java/agent"));
            Files.writeString(agentDir.resolve("settings.json"), settingsJson);
        }
        var sessionDir = Files.createTempDirectory("pi-flow-session");
        var args = noSession
            ? ArgsParser.parse(new String[] {"--provider", "scripted", "--model", "hello",
                "--no-session"})
            : ArgsParser.parse(new String[] {"--provider", "scripted", "--model", "hello",
                "--session-dir", sessionDir.toString()});
        String savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            session = AgentSession.create(args);
        } finally {
            System.setProperty("user.home", savedHome);
        }
        // 未编目模型 window=0，阈值门会在 prompt 收尾抢先自动压缩（既有夹具先例）。
        session.services().settings().accessors().setCompactionEnabled(false);
        provider = ScriptedChatProvider.Factory.lastInstance();
        mode = new com.pijava.coding.agent.modes.InteractiveMode(session);
        chatScreen = new ChatScreen();
        dispatcher = new TuiEventDispatcher();
        overlayRef.set(null);
        if (drivePrompts) {
            provider.enqueue("r1");
            provider.enqueue("r2");
            session.processPrompt("first question").status();
            session.processPrompt("second question").status();
            targetEntryId = userEntryIds().get(1);
        }
    }

    private List<String> userEntryIds() {
        return session.accumulatedEntries().stream()
            .filter(e -> e instanceof Entry.Message m && "user".equals(m.message().role()))
            .map(Entry::id).toList();
    }

    private String currentLeaf() {
        return session.harness().snapshot(session.laneName()).leafId();
    }

    private TreeNavigationFlow newFlow() {
        // docs/19 §3.9 新签名：entry 树选择器（terminalRows/Cols 定值、真键位表）；
        // docs/21 T5：mode 注入（restore 的队列侧落点）。
        return new TreeNavigationFlow(session, mode, chatScreen, dispatcher,
            overlayRef::set, () -> 24, () -> 80, new KeybindingsManager());
    }

    /** docs/19：entry 树选择器的选中驱动——初始选中＝当前叶，UP 一步到导航目标。 */
    private void driveTargetEntry() {
        drive(overlayRef.get(), UP, ENTER);
    }

    private static final dev.tamboui.tui.event.KeyEvent ENTER =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ENTER);
    private static final dev.tamboui.tui.event.KeyEvent UP =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.UP);
    private static final dev.tamboui.tui.event.KeyEvent DOWN =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.DOWN);
    private static final dev.tamboui.tui.event.KeyEvent ESC =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE);
    private static final dev.tamboui.tui.event.KeyEvent CTRL_X =
        dev.tamboui.tui.event.KeyEvent.ofChar('x', dev.tamboui.tui.event.KeyModifiers.CTRL);

    /** 模仿 PiTuiApp 的 overlay 循环：喂键 → 定案 → 先清位再 apply → 排干。 */
    private void drive(ScreenOverlay overlay, dev.tamboui.tui.event.KeyEvent... keys) {
        assertThat(overlay).as("overlay 在位").isNotNull();
        for (var key : keys) {
            overlay.onKeyEvent(key);
        }
        assertThat(overlay.isDone()).as("overlay 定案").isTrue();
        overlayRef.set(null); // PiTuiApp 同款：apply 可链式开下一个（回调重填 ref）
        overlay.apply(session, s -> { });
        dispatcher.drain();
    }

    private List<String> messageTexts() {
        return chatScreen.messages().stream().map(m -> switch (m) {
            case ChatMessage.User u -> u.text();
            case ChatMessage.Assistant a -> ChatMessage.joinText(a.blocks());
            case ChatMessage.System s -> s.text();
            case ChatMessage.Error e -> e.message();
            case ChatMessage.ToolCall t -> t.name();
            case ChatMessage.ToolResult tr -> "";
            case ChatMessage.TurnSeparator ts -> ts.label();
            case ChatMessage.Markdown md -> md.text();
            case ChatMessage.Padded p -> "padded:" + p.pad();
        }).toList();
    }

    private void awaitText(String text) {
        awaitTrue(() -> messageTexts().stream().anyMatch(t -> t.contains(text)),
            "聊天区出现「" + text + "」");
    }

    private void awaitTrue(BooleanSupplier cond, String what) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            dispatcher.drain();
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        dispatcher.drain();
        fail("超时: " + what + "；当前消息=" + messageTexts());
    }

    // ═══════════════════════════════════════════════════════════
    // ① skipPrompt / 三选映射（pi :5590-5615）
    // ═══════════════════════════════════════════════════════════

    /** ①：skipPrompt=true ⇒ 不出三选，直接 summarize=false 导航。 */
    @Test
    void skipPromptTrueNavigatesDirectlyWithoutSummary() throws Exception {
        newRig(true);
        newFlow().begin();
        var tree = overlayRef.get();
        assertThat(tree).isInstanceOf(TreeSelectorOverlay.class);

        driveTargetEntry();
        awaitText("Navigated to selected point");

        assertThat(overlayRef.get()).as("skipPrompt ⇒ 不出三选").isNull();
        assertThat(session.accumulatedEntries().stream()
                .filter(e -> e instanceof Entry.BranchSummary))
            .as("summarize=false ⇒ 无摘要 entry").isEmpty();
        // 目标=第二条 user ⇒ editorText 回填（编辑器空，pi :5673-5675）。
        assertThat(chatScreen.inputText()).isEqualTo("second question");
        // 整表重建（pi :5671-5672）：只剩新叶 path 的气泡＋结果行
        // （打头是系统消息 entry 的弱形状渲染，ChatMessage.from 既有行为）。
        assertThat(messageTexts()).containsExactly(
            "Unknown message role: system", "first question", "r1",
            "Navigated to selected point");
    }

    /** ②a：三选 "No summary" ⇒ summarize=false 导航。 */
    @Test
    void choiceNoSummaryNavigatesWithoutSummary() throws Exception {
        newRig(false);
        newFlow().begin();
        driveTargetEntry();
        var choice = overlayRef.get();
        assertThat(choice).isInstanceOf(SummaryChoiceScreen.class);

        drive(choice, ENTER); // 行 0 = "No summary"
        awaitText("Navigated to selected point");

        assertThat(session.accumulatedEntries().stream()
                .filter(e -> e instanceof Entry.BranchSummary)).isEmpty();
        assertThat(chatScreen.inputText()).isEqualTo("second question");
    }

    /** ②b：三选 "Summarize" ⇒ 摘要 entry（pi 生成，fromHook=false）。 */
    @Test
    void choiceSummarizeWritesBranchSummary() throws Exception {
        newRig(false);
        provider.enqueue("branch sum");
        newFlow().begin();
        driveTargetEntry();
        drive(overlayRef.get(), DOWN, ENTER); // 行 1 = "Summarize"

        awaitText("Navigated to selected point");

        var summaries = session.accumulatedEntries().stream()
            .filter(e -> e instanceof Entry.BranchSummary).toList();
        assertThat(summaries).hasSize(1);
        var summary = (Entry.BranchSummary) summaries.get(0);
        assertThat(summary.fromHook()).isEqualTo(Boolean.FALSE);
        assertThat(summary.summary()).contains("branch sum");
    }

    /** ②c：三选 "Summarize with custom prompt" ⇒ 编辑器确认后带指令导航。 */
    @Test
    void choiceCustomPromptNavigatesWithInstructions() throws Exception {
        newRig(false);
        provider.enqueue("custom sum");
        newFlow().begin();
        driveTargetEntry();
        drive(overlayRef.get(), DOWN, DOWN, ENTER); // 行 2 = custom
        var editor = overlayRef.get();
        assertThat(editor).isInstanceOf(CustomInstructionsScreen.class);

        drive(editor,
            dev.tamboui.tui.event.KeyEvent.ofChar('f'),
            dev.tamboui.tui.event.KeyEvent.ofChar('o'),
            dev.tamboui.tui.event.KeyEvent.ofChar('c'),
            dev.tamboui.tui.event.KeyEvent.ofChar('u'),
            dev.tamboui.tui.event.KeyEvent.ofChar('s'),
            ENTER);
        awaitText("Navigated to selected point");

        assertThat(session.accumulatedEntries().stream()
                .filter(e -> e instanceof Entry.BranchSummary)).hasSize(1);
    }

    // ═══════════════════════════════════════════════════════════
    // ③ Esc 回退链（pi :5599/:5610）
    // ═══════════════════════════════════════════════════════════

    /** ③：编辑器 Esc ⇒ 回三选；三选 Esc ⇒ 重开树选择器并恢复选中。 */
    @Test
    void escFlowsBackToChoiceThenReopensTree() throws Exception {
        newRig(false);
        newFlow().begin();
        driveTargetEntry();
        drive(overlayRef.get(), DOWN, DOWN, ENTER); // → 编辑器
        assertThat(overlayRef.get()).isInstanceOf(CustomInstructionsScreen.class);

        drive(overlayRef.get(), ESC); // 编辑器取消 ⇒ 回三选（pi :5610 continue）
        assertThat(overlayRef.get()).isInstanceOf(SummaryChoiceScreen.class);

        drive(overlayRef.get(), ESC); // 三选 Esc ⇒ 重开树选择器（pi :5599）
        var reopened = overlayRef.get();
        assertThat(reopened).isInstanceOf(TreeSelectorOverlay.class);
        assertThat(((TreeSelectorOverlay) reopened).selectedEntryId())
            .as("恢复上次选中（pi initialSelectedId 对位）").isEqualTo(targetEntryId);
    }

    /** ③b：选中当前叶 ⇒ "Already at this point"，不进三选（pi :5574-5579）。 */
    @Test
    void selectingCurrentLeafIsANoop() throws Exception {
        newRig(false);
        newFlow().begin();
        drive(overlayRef.get(), ENTER); // 初始选中即当前叶（pi initialSelectedId ?? currentLeafId）

        assertThat(messageTexts()).anyMatch(t -> t.equals("Already at this point"));
        assertThat(overlayRef.get()).as("不进三选").isNull();
    }

    // ═══════════════════════════════════════════════════════════
    // ④ 中止与取消（pi :5659-5668）
    // ═══════════════════════════════════════════════════════════

    /** ④a：摘要中止 ⇒ "Branch summarization cancelled"＋重开树选择器。 */
    @Test
    void abortedSummaryShowsMessageAndReopensTree() throws Exception {
        newRig(false);
        provider.armSummaryGate();
        newFlow().begin();
        driveTargetEntry();
        drive(overlayRef.get(), DOWN, ENTER); // "Summarize"
        provider.awaitSummaryStarted();       // worker 已进摘要调用

        session.abortBranchSummary();
        provider.releaseSummary();
        awaitText("Branch summarization cancelled");

        assertThat(overlayRef.get()).as("中止 ⇒ 重开树选择器（pi :5659-5664）")
            .isInstanceOf(TreeSelectorOverlay.class);
        assertThat(session.accumulatedEntries().stream()
                .filter(e -> e instanceof Entry.BranchSummary))
            .as("摘要未落库").isEmpty();
    }

    /** ④b：扩展取消 ⇒ "Navigation cancelled"，叶不动。 */
    @Test
    void extensionCancelShowsMessage() throws Exception {
        newRig(false, true);
        String oldLeaf = currentLeaf();
        newFlow().begin();
        driveTargetEntry();
        drive(overlayRef.get(), ENTER); // 三选行 0 = "No summary"（cancel 不受 summarize 门限制）

        awaitText("Navigation cancelled");
        assertThat(currentLeaf()).isEqualTo(oldLeaf);
        assertThat(overlayRef.get()).as("取消不重开选择器（pi :5665-5668）").isNull();
    }

    // ═══════════════════════════════════════════════════════════
    // ⑤ 成功结局（pi :5671-5684）
    // ═══════════════════════════════════════════════════════════

    /** ⑤b：编辑器非空 ⇒ editorText 不回填（pi :5673 的 getText().trim() 门）。 */
    @Test
    void editorTextNotBackfilledWhenInputNotEmpty() throws Exception {
        newRig(true);
        chatScreen.insertText("draft");
        newFlow().begin();
        driveTargetEntry();
        awaitText("Navigated to selected point");

        assertThat(chatScreen.inputText()).isEqualTo("draft");
    }

    // ═══════════════════════════════════════════════════════════
    // ⑥ 运行中/压缩中双门（pi :5618-5629）
    // ═══════════════════════════════════════════════════════════

    /** ⑥a：运行中 ⇒ abort＋waitForIdle 后才 navigateTree（结局推序：无门错文即证）。 */
    @Test
    void runningSessionIsAbortedBeforeNavigation() throws Exception {
        newRig(true);
        provider.delayNextCall(600);
        session.processPrompt("third question"); // 不 .status()：让 run 挂着
        awaitTrue(() -> session.isRunning(), "run 起跑");

        newFlow().begin();
        driveTargetEntry();
        awaitText("Navigated to selected point");

        assertThat(session.isRunning()).isFalse();
        assertThat(messageTexts())
            .as("双门第一句错文不得出现（abort+waitForIdle 在导航前）")
            .noneMatch(t -> t.contains("Wait for the current response"));
    }

    /** ⑥c（docs/21 T5，D41 重裁）：运行中有排队 ⇒ restore 先行于 abort，
     *  排队文本进编辑器（pi :5619-5621）。
     *
     * <p>批 15 修真（批 14 登记的确定性红）：steer 必须在<b>首个 provider 调用
     * 发起之后</b>提交——引擎在 turn 装配边界 drain 转向队列，提交早于调用入口
     * 时消息被吸入当前 turn（队列瞬空，restore 门恒 false），这正是旧的 600ms
     * 版本在本机确定性红的根因；调用入口后 run 堵在 5s 延迟里，队列必存活到
     * 导航 worker 的 restore。</p> */
    @Test
    void queuedMessagesRestoreToEditorBeforeAbort() throws Exception {
        newRig(true);
        provider.delayNextCall(5_000);
        int baselineCalls = provider.callCount();
        session.processPrompt("third question"); // 不 .status()：让 run 挂着
        awaitTrue(() -> provider.callCount() > baselineCalls, "本次 run 的 provider 调用已发起");
        mode.submit("queued while navigating"); // run 堵在 provider 内 ⇒ steer 必排队

        newFlow().begin();
        driveTargetEntry();
        awaitText("Navigated to selected point");

        // restore 先占住编辑器 ⇒ editorText 回填门（pi :5673 isInputEmpty）不再生效，
        // 编辑器＝排队文本（非 "second question"）。
        assertThat(chatScreen.inputText()).isEqualTo("queued while navigating");
        assertThat(session.isRunning()).isFalse();
    }

    /** ⑥b：重查 isCompacting ⇒ 双门第二句错文逐字（pi :5624-5629）。 */
    @Test
    void compactingRecheckShowsVerbatimError() throws Exception {
        newRig(true);
        provider.armSummaryGate();
        // 显式 settings（AgentSessionCompactAbortTest 先例）：rig 关了自动压缩，
        // compact(String) 的 accessor 解析会带上 enabled=false。
        Thread.startVirtualThread(() -> session.compact(
            new com.pijava.agent.compaction.CompactionSettings(true, 16_384, 1)));
        provider.awaitSummaryStarted();
        assertThat(session.isCompacting()).isTrue();

        newFlow().begin();
        driveTargetEntry();
        awaitText("Wait for the current compaction or tree navigation to finish"
            + " before navigating the session tree.");

        provider.releaseSummary();
        awaitTrue(() -> !session.isCompacting(), "压缩收尾");
        assertThat(messageTexts()).noneMatch(t -> t.contains("Navigated to selected point"));
    }

    // ═══════════════════════════════════════════════════════════
    // ⑦ 摘要指示器（pi :5640＋finally）
    // ═══════════════════════════════════════════════════════════

    /** ⑦：摘要路径指示器置（BRANCH_SUMMARY）/清成对。 */
    @Test
    void branchSummaryIndicatorSetAndClearedAroundSummary() throws Exception {
        newRig(false);
        provider.armSummaryGate();
        newFlow().begin();
        driveTargetEntry();
        drive(overlayRef.get(), DOWN, ENTER); // "Summarize"
        provider.awaitSummaryStarted();       // 指示器 dispatch 必先于摘要调用入队

        dispatcher.drain();
        assertThat(chatScreen.indicator()).isNotNull();
        assertThat(chatScreen.indicator().kind())
            .isEqualTo(StatusIndicator.Kind.BRANCH_SUMMARY);

        provider.releaseSummary();
        awaitText("Navigated to selected point");
        assertThat(chatScreen.indicator()).as("finally 清指示器（pi :5684）").isNull();
    }

    // ═══════════════════════════════════════════════════════════
    // ⑧ docs/19 §3.9/§6.25：entry 树本体接线（空树／copy 三结局／filterMode 初值）
    // ═══════════════════════════════════════════════════════════

    /** ⑧a：空树 ⇒ "No entries in session"，不开 overlay（pi :5563-5566）。 */
    @Test
    void emptyTreeShowsMessageAndSkipsOverlay() throws Exception {
        newRig(false, false, false, null);
        newFlow().begin();
        assertThat(overlayRef.get()).as("空树不开选择器").isNull();
        assertThat(messageTexts()).anyMatch(t -> t.equals("No entries in session"));
    }

    /** ⑧b：copy 成功臂——文本进剪贴板＋状态文案逐字（pi :5604-5606）。 */
    @Test
    void copySuccessWritesClipboardAndShowsStatus() throws Exception {
        newRig(false);
        var captured = new AtomicReference<String>();
        ClipboardSupport.overrideForTest(captured::set);
        newFlow().begin();
        // 初始选中＝当前叶（asst-2，文本 "r2"）；ctrl+x 复制全文。
        overlayRef.get().onKeyEvent(CTRL_X);

        assertThat(captured.get()).isEqualTo("r2");
        assertThat(messageTexts())
            .anyMatch(t -> t.equals("Copied selected message to clipboard"));
        assertThat(overlayRef.get()).as("复制不关选择器（pi 同）").isNotNull();
    }

    /** ⑧c：copy 空文臂——"Selected entry has no text to copy"（pi :5597-5599）。 */
    @Test
    void copyEmptyTextShowsVerbatimError() throws Exception {
        newRig(false);
        provider.enqueue("");   // 空文本 assistant：当前叶恒显，copyText trim 空 ⇒ null
        session.processPrompt("third").status();
        newFlow().begin();
        overlayRef.get().onKeyEvent(CTRL_X);   // 初始选中即该叶

        assertThat(messageTexts())
            .anyMatch(t -> t.contains("Selected entry has no text to copy"));
    }

    /** ⑧d：copy 失败臂——剪贴板异常 ⇒ showError(e.getMessage())（pi :5605-5607）。 */
    @Test
    void copyFailureShowsErrorMessage() throws Exception {
        newRig(false);
        ClipboardSupport.overrideForTest(text -> {
            throw new java.io.IOException("boom-no-clipboard");
        });
        newFlow().begin();
        overlayRef.get().onKeyEvent(CTRL_X);

        assertThat(messageTexts()).anyMatch(t -> t.contains("boom-no-clipboard"));
    }

    /** ⑧f：--no-session 下 label 提交不炸渲染线程——错误转 showError（评审 I2）。 */
    @Test
    void labelEditOnNoSessionShowsErrorNotCrash() throws Exception {
        newRig(false, false, true, null, true);   // 无持久会话：harness 转录成树
        newFlow().begin();
        var tree = overlayRef.get();
        assertThat(tree).as("无持久会话也能开树（harness 转录兜底）").isNotNull();
        // shift+l（真实输入形态：无修饰大写）→ 输入 → Enter 提交。
        tree.onKeyEvent(dev.tamboui.tui.event.KeyEvent.ofChar('L'));
        tree.onKeyEvent(dev.tamboui.tui.event.KeyEvent.ofChar('x'));
        tree.onKeyEvent(ENTER);   // setLabel 抛 IllegalStateException ⇒ showError

        assertThat(messageTexts())
            .anyMatch(t -> t.contains("Cannot set labels without a persistent session"));
        assertThat(overlayRef.get()).as("选择器不被异常带走").isSameAs(tree);
    }

    /** ⑧e：treeFilterMode 初值读全局 settings，选择器内切换不回写（pi :5572）。 */
    @Test
    void treeFilterModeInitialValueComesFromSettings() throws Exception {
        newRig(false, false, true, "user-only");
        newFlow().begin();
        var tree = overlayRef.get();
        assertThat(tree).isInstanceOf(TreeSelectorOverlay.class);
        assertThat(((TreeSelectorOverlay) tree).filterMode())
            .as("settings 初值消费").isEqualTo(FilterMode.USER_ONLY);
        // 选择器内切回 default（ctrl+d）——settings 不回写。
        tree.onKeyEvent(dev.tamboui.tui.event.KeyEvent.ofChar('d',
            dev.tamboui.tui.event.KeyModifiers.CTRL));
        assertThat(((TreeSelectorOverlay) tree).filterMode()).isEqualTo(FilterMode.DEFAULT);
        assertThat(session.services().settings().accessors().getTreeFilterMode())
            .as("选择器内切换不回写 settings").isEqualTo("user-only");
    }
}
