package com.pijava.coding.agent.core;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionTreeEvent;
import com.pijava.coding.agent.extension.event.ExtensionResults.ExtensionTreeSummary;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeTreeResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.TreePreparation;
import com.pijava.coding.agent.support.RecordingChatProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_BEFORE_TREE;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_TREE;

/**
 * docs/18（B245）：{@code session_before_tree}/{@code session_tree} 经
 * {@link AgentSession#navigateTree} 的集成对拍（pi §1.5：branch-summary-extensions、
 * 3688-tree-cancel-compacting）。扩展经 TCCL 作用域 loader 注入
 * （{@link ForkExtensionWiringTest} 先例）；摘要 prompt 由 {@link RecordingChatProvider}
 * 记录（{@code <conversation>} 前缀＝生成器调用）。
 */
class SessionBeforeTreeTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static final List<SessionTreeEvent> TREE_EVENTS = new CopyOnWriteArrayList<>();
    static final Queue<SessionBeforeTreeResult> SCRIPT_H1 = new ConcurrentLinkedQueue<>();
    static final Queue<SessionBeforeTreeResult> SCRIPT_H2 = new ConcurrentLinkedQueue<>();
    static volatile TreePreparation capturedPreparation;
    static volatile boolean abortInHandler;
    static volatile boolean throwInHandler;

    /** 探针扩展：两个 before_tree 处理器（验最后发言者赢/异常隔离）＋一个 tree 观察器。 */
    public static final class Probe implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Probe() {}

        @Override public String name() { return "tree-probe"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.on(SESSION_BEFORE_TREE, (e, c) -> {
                EVENTS.add("h1");
                capturedPreparation = e.preparation();
                if (abortInHandler) {
                    e.signal().abort();
                }
                if (throwInHandler) {
                    throw new IllegalStateException("probe boom");
                }
                return SCRIPT_H1.poll();
            });
            ctx.on(SESSION_BEFORE_TREE, (e, c) -> {
                EVENTS.add("h2");
                return SCRIPT_H2.poll();
            });
            ctx.on(SESSION_TREE, (e, c) -> {
                TREE_EVENTS.add(e);
                return null;
            });
        }
    }

    @TempDir
    Path tmp;

    private ClassLoader previousTccl;

    @BeforeEach
    void installScopedLoader() throws Exception {
        EVENTS.clear();
        TREE_EVENTS.clear();
        SCRIPT_H1.clear();
        SCRIPT_H2.clear();
        capturedPreparation = null;
        abortInHandler = false;
        throwInHandler = false;
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            Probe.class.getName() + "\n");
        Path testClasses = Path.of(getClass().getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(
            new java.net.URL[] {tmp.toUri().toURL(), testClasses.toUri().toURL()},
            previousTccl));
    }

    @AfterEach
    void restoreLoader() {
        Thread.currentThread().setContextClassLoader(previousTccl);
    }

    private record Rig(AgentSession session, RecordingChatProvider recording)
            implements AutoCloseable {
        @Override
        public void close() {
            session.close();
        }
    }

    private Rig rig(Path sessionDir, boolean extensions) throws Exception {
        var recording = new RecordingChatProvider("capture");
        var argv = extensions
            ? new String[] {"--provider", "capture", "--model", "hello",
                "--session-dir", sessionDir.toString()}
            : new String[] {"--provider", "capture", "--model", "hello",
                "--session-dir", sessionDir.toString(), "--no-extensions"};
        var args = ArgsParser.parse(argv);
        // navigateTree 要持久仓（门）：走持久装配路径（NavigateTreeTest 先例）。
        var handle = PersistentSessionRepositories.jsonl(sessionDir);
        var session = AgentSession.create(args, handle, recording.register(),
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.load(args.projectTrustOverride()));
        // 未编目模型 window=0，阈值门会在 prompt 收尾抢先自动压缩（CompactSlashCommandTest 先例）。
        session.services().settings().compaction().setCompactionEnabled(false);
        return new Rig(session, recording);
    }

    private static String firstUserEntryId(AgentSession session) {
        return session.accumulatedEntries().stream()
            .filter(e -> e instanceof Entry.Message m && "user".equals(m.message().role()))
            .map(Entry::id).findFirst().orElseThrow();
    }

    private static AgentSession drivenSession(Rig rig) {
        rig.session().processPrompt("first question").status();
        rig.session().processPrompt("second question").status();
        return rig.session();
    }

    private static String leafId(AgentSession session) {
        return session.harness().snapshot(session.laneName()).leafId();
    }

    private static Object findField(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** ①：cancel ⇒ {@code (true,false,null,null)}、叶不动、token 清零可再导航（pi 3688 逐字）。 */
    @Test
    void cancelReturnsCancelledTrueLeafUnmovedAndTokenCleared() throws Exception {
        Path dir = Files.createTempDirectory("pi-tree-cancel");
        try (var rig = rig(dir, true)) {
            var session = drivenSession(rig);
            String target = firstUserEntryId(session);
            String oldLeaf = leafId(session);
            SCRIPT_H1.add(new SessionBeforeTreeResult(true, null, null, null, null));

            var nav = session.navigateTree(target, false, null, false, null);

            assertThat(nav.cancelled()).isTrue();
            assertThat(nav.aborted()).isFalse();
            assertThat(nav.editorText()).isNull();
            assertThat(nav.summaryEntry()).isNull();
            assertThat(leafId(session)).as("叶不动（pi :4032）").isEqualTo(oldLeaf);
            // preparation 载荷（pi :4006-4016：先取 options 值）
            assertThat(capturedPreparation.targetId()).isEqualTo(target);
            assertThat(capturedPreparation.oldLeafId()).isEqualTo(oldLeaf);
            assertThat(capturedPreparation.userWantsSummary()).isFalse();
            assertThat(TREE_EVENTS).as("取消 ⇒ session_tree 不发").isEmpty();
            // token finally 清零（pi :4155）：再导航正常进行（h1 脚本已空 ⇒ 不拦）。
            var again = session.navigateTree(target, false, null, false, null);
            assertThat(again.cancelled()).isFalse();
            assertThat(again.aborted()).isFalse();
        }
    }

    /** ②：扩展摘要（summarize=true）⇒ 默认生成器不被调、fromHook=true、文本/usage 全扩展值。 */
    @Test
    void extensionSummaryBypassesDefaultGeneratorWhenSummarizeTrue() throws Exception {
        Path dir = Files.createTempDirectory("pi-tree-extsum");
        try (var rig = rig(dir, true)) {
            var session = drivenSession(rig);
            String target = firstUserEntryId(session);
            int promptsBefore = rig.recording().userTexts().size();
            SCRIPT_H1.add(new SessionBeforeTreeResult(null,
                new ExtensionTreeSummary("EXT SUMMARY", null, com.pijava.ai.Usage.of(7, 3)),
                null, null, null));

            var nav = session.navigateTree(target, true, null, false, null);

            assertThat(nav.cancelled()).isFalse();
            var summary = nav.summaryEntry();
            assertThat(summary).isNotNull();
            assertThat(summary.fromHook()).as("pi fromHook").isEqualTo(Boolean.TRUE);
            assertThat(summary.summary()).as("扩展文本逐字（不加 PREAMBLE）")
                .isEqualTo("EXT SUMMARY");
            assertThat(summary.usage()).isEqualTo(com.pijava.ai.Usage.of(7, 3));
            // 默认生成器不被调：导航后无摘要 prompt（"<conversation>" 前缀）新发。
            assertThat(rig.recording().userTexts()
                    .subList(promptsBefore, rig.recording().userTexts().size()))
                .noneMatch(t -> t.startsWith("<conversation>"));
            // session_tree：newLeafId=摘要 entry id、fromExtension=true（pi :4140-4147）。
            assertThat(TREE_EVENTS).hasSize(1);
            var treeEvent = TREE_EVENTS.get(0);
            assertThat(treeEvent.newLeafId()).isEqualTo(summary.id());
            assertThat(treeEvent.summaryEntry()).isEqualTo(summary);
            assertThat(treeEvent.fromExtension()).isEqualTo(Boolean.TRUE);
        }
    }

    /** ③：summarize 门（pi :4036）——用户没要摘要 ⇒ 扩展摘要不生效。 */
    @Test
    void extensionSummaryIgnoredWhenUserDidNotAskForSummary() throws Exception {
        Path dir = Files.createTempDirectory("pi-tree-gate");
        try (var rig = rig(dir, true)) {
            var session = drivenSession(rig);
            String target = firstUserEntryId(session);
            SCRIPT_H1.add(new SessionBeforeTreeResult(null,
                new ExtensionTreeSummary("EXT SUMMARY", null, null), null, null, null));

            var nav = session.navigateTree(target, false, null, false, null);

            assertThat(nav.cancelled()).isFalse();
            assertThat(nav.summaryEntry()).isNull();
            assertThat(TREE_EVENTS).hasSize(1);
            assertThat(TREE_EVENTS.get(0).summaryEntry()).isNull();
            assertThat(TREE_EVENTS.get(0).fromExtension())
                .as("无摘要 ⇒ fromExtension=undefined（pi :4145）").isNull();
        }
    }

    /** ④：三覆盖不受 summarize 门限制——默认生成器收到覆盖指令；label 贴摘要 entry。 */
    @Test
    void overridesReachDefaultGeneratorAndLabelLandsOnSummaryEntry() throws Exception {
        Path dir = Files.createTempDirectory("pi-tree-override");
        try (var rig = rig(dir, true)) {
            var session = drivenSession(rig);
            String target = firstUserEntryId(session);
            SCRIPT_H1.add(new SessionBeforeTreeResult(
                null, null, "OVERRIDE FOCUS", Boolean.TRUE, "ext-label"));

            var nav = session.navigateTree(target, true, null, false, null);

            var summary = nav.summaryEntry();
            assertThat(summary).isNotNull();
            assertThat(summary.fromHook()).as("pi 生成 ⇒ fromHook=false").isEqualTo(Boolean.FALSE);
            // replace=true ⇒ 生成器 prompt 尾＝覆盖指令逐字、不含默认 prompt（pi :4044-4048）。
            assertThat(rig.recording().userTexts())
                .anyMatch(t -> t.startsWith("<conversation>") && t.endsWith("\n\nOVERRIDE FOCUS"));
            assertThat(rig.recording().userTexts())
                .noneMatch(t -> t.contains("Create a structured summary of this conversation branch"));
            // label 贴摘要 entry（pi :4118-4121）。
            assertThat(session.session().getLabel(summary.id())).isEqualTo("ext-label");
            assertThat(TREE_EVENTS.get(0).fromExtension()).isEqualTo(Boolean.FALSE);
        }
    }

    /** ⑤：最后发言者赢（pi :1099）——h2 的 label 覆盖 h1 的。 */
    @Test
    void lastSpeakerWinsAcrossHandlers() throws Exception {
        Path dir = Files.createTempDirectory("pi-tree-speaker");
        try (var rig = rig(dir, true)) {
            var session = drivenSession(rig);
            String target = firstUserEntryId(session);
            SCRIPT_H1.add(new SessionBeforeTreeResult(null, null, null, null, "first-label"));
            SCRIPT_H2.add(new SessionBeforeTreeResult(null, null, null, null, "second-label"));

            var nav = session.navigateTree(target, true, null, false, null);

            assertThat(nav.summaryEntry()).isNotNull();
            assertThat(session.session().getLabel(nav.summaryEntry().id()))
                .isEqualTo("second-label");
        }
    }

    /** ⑥：异常隔离——h1 抛 ⇒ emitError＋h2 照跑＋导航照常。 */
    @Test
    void handlerExceptionIsReportedAndNavigationProceeds() throws Exception {
        Path dir = Files.createTempDirectory("pi-tree-throw");
        try (var rig = rig(dir, true)) {
            var session = drivenSession(rig);
            String target = firstUserEntryId(session);
            var sessionEvents = new CopyOnWriteArrayList<AgentSessionEvent>();
            session.subscribe(sessionEvents::add);
            throwInHandler = true;

            var nav = session.navigateTree(target, false, null, false, null);

            assertThat(nav.cancelled()).isFalse();
            assertThat(EVENTS).as("h1 抛后 h2 照跑").contains("h1", "h2");
            assertThat(sessionEvents)
                .anyMatch(e -> e instanceof AgentSessionEvent.ExtensionError);
        }
    }

    /** ⑦：--no-extensions ⇒ 零发射、导航照常（pi 无扩展＝runner 空表）。 */
    @Test
    void noExtensionsFiresNothingAndNavigationWorks() throws Exception {
        Path dir = Files.createTempDirectory("pi-tree-noext");
        try (var rig = rig(dir, false)) {
            var session = drivenSession(rig);
            String target = firstUserEntryId(session);

            var nav = session.navigateTree(target, false, null, false, null);

            assertThat(nav.cancelled()).isFalse();
            assertThat(EVENTS).isEmpty();
            assertThat(TREE_EVENTS).isEmpty();
        }
    }

    /** ⑧：session_tree 载荷——无摘要时 newLeafId＝导航后当前叶、oldLeafId＝旧叶。 */
    @Test
    void sessionTreeCarriesNavigatedLeafIds() throws Exception {
        Path dir = Files.createTempDirectory("pi-tree-payload");
        try (var rig = rig(dir, true)) {
            var session = drivenSession(rig);
            String target = firstUserEntryId(session);
            String oldLeaf = leafId(session);

            var nav = session.navigateTree(target, false, null, false, null);

            assertThat(nav.cancelled()).isFalse();
            assertThat(TREE_EVENTS).hasSize(1);
            var e = TREE_EVENTS.get(0);
            // user 消息目标 ⇒ 新叶=其父（pi :4094-4099）；newLeafId＝导航后当前叶（pi :4141）。
            assertThat(e.newLeafId()).isEqualTo(leafId(session));
            assertThat(e.newLeafId()).isNotEqualTo(target);
            assertThat(e.oldLeafId()).isEqualTo(oldLeaf);
            assertThat(e.summaryEntry()).isNull();
            assertThat(e.fromExtension()).isNull();
        }
    }

    /** ⑨：摘要被中止 ⇒ {@code (true,true,null,null)}（对照 ① 的 {@code (true,false,…)}）。 */
    @Test
    void abortedBranchSummaryYieldsCancelledAndAborted() throws Exception {
        Path dir = Files.createTempDirectory("pi-tree-abort");
        try (var rig = rig(dir, true)) {
            var session = drivenSession(rig);
            String target = firstUserEntryId(session);
            String oldLeaf = leafId(session);
            abortInHandler = true; // h1 在事件里中止摘要令牌（pi 的 signal.abort() 同位）
            int promptsBefore = rig.recording().userTexts().size();

            var nav = session.navigateTree(target, true, null, false, null);

            assertThat(nav.cancelled()).isTrue();
            assertThat(nav.aborted()).isTrue();
            assertThat(nav.summaryEntry()).isNull();
            assertThat(leafId(session)).as("摘要未落库、叶不动").isEqualTo(oldLeaf);
            assertThat(TREE_EVENTS).isEmpty();
            // 终审 Important ③：token 已中止 ⇒ 预检拦在 generate 前，不发起 LLM 调用。
            assertThat(rig.recording().userTexts()
                    .subList(promptsBefore, rig.recording().userTexts().size()))
                .noneMatch(t -> t.startsWith("<conversation>"));
        }
    }

    /** ⑪（终审 Important ②）：扩展 summary 对象文本为 null ⇒ 跳过默认生成器且不产摘要。 */
    @Test
    void nullExtensionSummaryTextSkipsGeneratorAndWritesNoEntry() throws Exception {
        Path dir = Files.createTempDirectory("pi-tree-nullsum");
        try (var rig = rig(dir, true)) {
            var session = drivenSession(rig);
            String target = firstUserEntryId(session);
            int promptsBefore = rig.recording().userTexts().size();
            SCRIPT_H1.add(new SessionBeforeTreeResult(null,
                new ExtensionTreeSummary(null, null, null), null, null, null));

            var nav = session.navigateTree(target, true, null, false, null);

            assertThat(nav.cancelled()).isFalse();
            // pi：extensionSummary 非 null ⇒ 默认生成器门关闭（!extensionSummary，:4054）。
            assertThat(rig.recording().userTexts()
                    .subList(promptsBefore, rig.recording().userTexts().size()))
                .noneMatch(t -> t.startsWith("<conversation>"));
            // pi：if (summaryText) falsy ⇒ 不建摘要 entry（:4107）。
            assertThat(nav.summaryEntry()).isNull();
            assertThat(TREE_EVENTS.get(0).fromExtension()).isNull(); // pi :4145
        }
    }

    /** ⑫（终审 Important ②）：空串文本/标签走 pi truthiness——不建 entry、不贴 label。 */
    @Test
    void emptySummaryTextAndEmptyLabelAreFalsyLikePi() throws Exception {
        Path dir = Files.createTempDirectory("pi-tree-empty");
        try (var rig = rig(dir, true)) {
            var session = drivenSession(rig);
            String target = firstUserEntryId(session);
            SCRIPT_H1.add(new SessionBeforeTreeResult(null,
                new ExtensionTreeSummary("", null, null), null, null, ""));

            var nav = session.navigateTree(target, true, null, false, null);

            assertThat(nav.summaryEntry())
                .as("pi if (summaryText)（:4107）：空串不建摘要 entry").isNull();
            assertThat(session.session().getLabel(target))
                .as("pi if (label)（:4118/:4125）：空串不贴 label").isNull();
        }
    }

    /** ⑩：模型门（pi :3985-3987）——summarize=true 且无模型 ⇒ 错文逐字。 */
    @Test
    void summarizeWithoutModelThrowsVerbatim() throws Exception {
        Path dir = Files.createTempDirectory("pi-tree-nomodel");
        try (var rig = rig(dir, true)) {
            var session = drivenSession(rig);
            String target = firstUserEntryId(session);
            // 装配链恒解析出模型 ⇒ null 模型只能反射进 lane（setModel(null) 会在
            // RunLifecycle.recordModelChange 写 model_change entry 时 NPE，非缝）；
            // lane 字段挂在 AgentHarness 的父类上 ⇒ 沿类层次找。
            var lane = findField(session.harness(), "lane");
            setField(lane, "model", null);

            assertThatThrownBy(() -> session.navigateTree(target, true, null, false, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No model available for summarization");
        }
    }
}
