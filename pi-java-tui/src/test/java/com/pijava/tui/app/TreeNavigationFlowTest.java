package com.pijava.tui.app;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.LaneInfo;
import com.pijava.agent.harness.SessionSnapshot;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.protocol.AbstractChatApi;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderFactory;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
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
import com.pijava.tui.screen.TreeSelectorScreen;
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

    /**
     * provider SPI 工厂（ServiceLoader 发现，TCCL 作用域注入——公共
     * {@code AgentSession.create(args)} 的 defaultProviders 会捡到；实例经
     * {@link #lastInstance} 回取）。tui 模块摸不到包级的
     * {@code PersistentSessionRepositories}，持久仓装配只能走公共 create。
     */
    public static final class ScriptedChatProviderFactory implements ProviderFactory {
        static volatile ScriptedChatProvider lastInstance;

        /** ServiceLoader 无参构造。 */
        public ScriptedChatProviderFactory() {}

        @Override public Provider create() {
            var p = new ScriptedChatProvider("scripted");
            lastInstance = p;
            return p;
        }

        @Override public String providerName() { return "scripted"; }
    }

    /**
     * 剧本 provider：逐 call 消费回复队列（空 ⇒ "ok"）；{@code <conversation>}
     * 请求＝摘要类调用（分支摘要/压缩同形），可被闩住等测试放行。
     */
    static final class ScriptedChatProvider implements Provider {
        private final String name;
        private final Queue<String> replies = new ConcurrentLinkedQueue<>();
        private volatile CountDownLatch summaryStarted;
        private volatile CountDownLatch summaryGate;
        private volatile long nextDelayMs;

        ScriptedChatProvider(String name) {
            this.name = name;
        }

        void enqueue(String text) {
            replies.add(text);
        }

        void delayNextCall(long ms) {
            nextDelayMs = ms;
        }

        void armSummaryGate() {
            summaryStarted = new CountDownLatch(1);
            summaryGate = new CountDownLatch(1);
        }

        void awaitSummaryStarted() throws InterruptedException {
            assertThat(summaryStarted.await(15, TimeUnit.SECONDS)).as("摘要调用发起").isTrue();
        }

        void releaseSummary() {
            summaryGate.countDown();
        }

        @Override public String name() { return name; }
        @Override public String displayName() { return name; }
        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(com.pijava.ai.api.ChatApi.class);
        }

        @Override
        public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            return apiType.cast(new Api(this));
        }

        @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }

        private static final class Api extends AbstractChatApi {
            private final ScriptedChatProvider provider;

            Api(ScriptedChatProvider provider) {
                this.provider = provider;
            }

            @Override public String apiName() { return "scripted"; }
            @Override protected boolean resolvesRequestOptions() { return false; }

            @Override
            protected void streamInternal(StreamRequest request,
                    java.util.concurrent.SubmissionPublisher<StreamEvent> publisher) {
                boolean summarization = false;
                for (Message message : request.messages()) {
                    if (message instanceof Message.UserMessage user
                            && ChatMessage.joinText(user.content()).contains("<conversation>")) {
                        summarization = true;
                    }
                }
                if (summarization) {
                    var started = provider.summaryStarted;
                    if (started != null) {
                        started.countDown();
                    }
                    var gate = provider.summaryGate;
                    if (gate != null) {
                        try {
                            gate.await(15, TimeUnit.SECONDS); // 防挂死：超时放行
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                long delay = provider.nextDelayMs;
                provider.nextDelayMs = 0;
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                String text = provider.replies.poll();
                if (text == null) {
                    text = "ok";
                }
                var msg = AssistantMessage.empty().withContent(List.of(
                    new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
                publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
                publisher.submit(new StreamEvent.TextDelta(0, text,
                    msg.withStopReason((StopReason) null)));
                publisher.submit(StreamEvent.StreamDone.settle(StopReason.STOP, msg));
            }
        }
    }

    @TempDir
    Path tmp;

    private AgentSession session;
    private ChatScreen chatScreen;
    private TuiEventDispatcher dispatcher;
    private ScriptedChatProvider provider;
    private final AtomicReference<ScreenOverlay> overlayRef = new AtomicReference<>();
    private String targetEntryId; // 第二条 user entry（导航目标）
    private ClassLoader previousTccl;

    @AfterEach
    void tearDown() {
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
        // TCCL 注入：provider factory（恒——公共 create 的 ServiceLoader 发现通道）
        // ＋ cancel 探针扩展（按需）。
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.ai.provider.ProviderFactory"),
            ScriptedChatProviderFactory.class.getName() + "\n");
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
        if (skipPrompt) {
            var agentDir = Files.createDirectories(home.resolve(".pi-java/agent"));
            Files.writeString(agentDir.resolve("settings.json"),
                "{\"branchSummary\":{\"skipPrompt\":true}}");
        }
        var sessionDir = Files.createTempDirectory("pi-flow-session");
        var args = ArgsParser.parse(new String[] {"--provider", "scripted", "--model", "hello",
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
        provider = ScriptedChatProviderFactory.lastInstance;
        provider.enqueue("r1");
        provider.enqueue("r2");
        chatScreen = new ChatScreen();
        dispatcher = new TuiEventDispatcher();
        overlayRef.set(null);
        session.processPrompt("first question").status();
        session.processPrompt("second question").status();
        targetEntryId = userEntryIds().get(1);
    }

    private List<String> userEntryIds() {
        return session.accumulatedEntries().stream()
            .filter(e -> e instanceof Entry.Message m && "user".equals(m.message().role()))
            .map(Entry::id).toList();
    }

    private String currentLeaf() {
        return session.harness().snapshot(session.laneName()).leafId();
    }

    private SessionSnapshot snapshotForFlow() {
        return new SessionSnapshot("s", "m", "idle", 0L, 0, List.of(),
            List.of(new LaneInfo(session.laneName(), currentLeaf(), null),
                new LaneInfo("alt", targetEntryId, null)));
    }

    private TreeNavigationFlow newFlow() {
        return new TreeNavigationFlow(session, chatScreen, dispatcher,
            this::snapshotForFlow, overlayRef::set);
    }

    private static final dev.tamboui.tui.event.KeyEvent ENTER =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ENTER);
    private static final dev.tamboui.tui.event.KeyEvent DOWN =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.DOWN);
    private static final dev.tamboui.tui.event.KeyEvent ESC =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE);

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
        assertThat(tree).isInstanceOf(TreeSelectorScreen.class);

        drive(tree, ENTER); // "alt" 行字母序在 "main" 前 ⇒ 默认选中即导航目标
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
        drive(overlayRef.get(), ENTER);
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
        drive(overlayRef.get(), ENTER);
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
        drive(overlayRef.get(), ENTER);
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
        drive(overlayRef.get(), ENTER);
        drive(overlayRef.get(), DOWN, DOWN, ENTER); // → 编辑器
        assertThat(overlayRef.get()).isInstanceOf(CustomInstructionsScreen.class);

        drive(overlayRef.get(), ESC); // 编辑器取消 ⇒ 回三选（pi :5610 continue）
        assertThat(overlayRef.get()).isInstanceOf(SummaryChoiceScreen.class);

        drive(overlayRef.get(), ESC); // 三选 Esc ⇒ 重开树选择器（pi :5599）
        var reopened = overlayRef.get();
        assertThat(reopened).isInstanceOf(TreeSelectorScreen.class);
        assertThat(((TreeSelectorScreen) reopened).selectedEntryId())
            .as("恢复上次选中（pi initialSelectedId 对位）").isEqualTo(targetEntryId);
    }

    /** ③b：选中当前叶 ⇒ "Already at this point"，不进三选（pi :5574-5579）。 */
    @Test
    void selectingCurrentLeafIsANoop() throws Exception {
        newRig(false);
        newFlow().begin();
        drive(overlayRef.get(), DOWN, ENTER); // 行 1 = 当前车道（叶=当前叶）

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
        drive(overlayRef.get(), ENTER);
        drive(overlayRef.get(), DOWN, ENTER); // "Summarize"
        provider.awaitSummaryStarted();       // worker 已进摘要调用

        session.abortBranchSummary();
        provider.releaseSummary();
        awaitText("Branch summarization cancelled");

        assertThat(overlayRef.get()).as("中止 ⇒ 重开树选择器（pi :5659-5664）")
            .isInstanceOf(TreeSelectorScreen.class);
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
        drive(overlayRef.get(), ENTER);
        drive(overlayRef.get(), ENTER); // "No summary"（cancel 不受 summarize 门限制）

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
        drive(overlayRef.get(), ENTER);
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
        drive(overlayRef.get(), ENTER);
        awaitText("Navigated to selected point");

        assertThat(session.isRunning()).isFalse();
        assertThat(messageTexts())
            .as("双门第一句错文不得出现（abort+waitForIdle 在导航前）")
            .noneMatch(t -> t.contains("Wait for the current response"));
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
        drive(overlayRef.get(), ENTER);
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
        drive(overlayRef.get(), ENTER);
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
}
