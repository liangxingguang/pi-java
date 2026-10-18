package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B1（map #25）：pi {@code navigateTree}（agent-session.ts:3964-4156）对位集成夹具。
 * 持久会话（{@code --session-dir}）＋ FauxProvider：两轮 prompt 造分叉素材，
 * 第三轮 faux 响应当分支摘要的 LLM 回答。
 */
class NavigateTreeTest {

    @TempDir Path tmp;

    private static List<StreamEvent> reply(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private record Rig(AgentSession session, ProviderRegistry providers) implements AutoCloseable {
        @Override
        public void close() {
            session.close();
        }
    }

    private Rig rig(Path sessionDir, List<List<StreamEvent>> scripted) throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-tree", "--model", "hello",
            "--session-dir", sessionDir.toString()});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-tree", scripted));
        // navigateTree 要持久仓（AgentSession.navigateTree 的门）：走持久装配路径，
        // 而不是 create(args, providers, toolContext) 的 InMemorySessionRepository 形。
        var handle = PersistentSessionRepositories.jsonl(sessionDir);
        var session = AgentSession.create(args, handle, providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.load(args.projectTrustOverride()));
        return new Rig(session, providers);
    }

    private static List<Entry> entries(AgentSession session) {
        return session.accumulatedEntries();
    }

    private static String firstUserEntryId(AgentSession session) {
        return entries(session).stream()
            .filter(e -> e instanceof Entry.Message m && "user".equals(m.message().role()))
            .map(Entry::id).findFirst().orElseThrow();
    }

    @Test
    void navigatingToAUserEntryMovesLeafToParentAndBackfillsEditorText() throws Exception {
        Path dir = Files.createTempDirectory("pi-nav-basic");
        try (var rig = rig(dir, List.of(reply("r1"), reply("r2")))) {
            var session = rig.session();
            session.processPrompt("first question").status();
            session.processPrompt("second question").status();
            String target = firstUserEntryId(session);

            var nav = session.navigateTree(target, false, null, false, null);

            assertThat(nav.cancelled()).isFalse();
            // pi :4094-4099：user 消息目标 ⇒ 叶=父、editorText=消息文本（回填编辑器）。
            assertThat(nav.editorText()).isEqualTo("first question");
            assertThat(nav.summaryEntry()).isNull();
            // 目标即当前叶 ⇒ 不动（pi :3989-3991）。
            var noop = session.navigateTree(
                entries(session).getLast().id(), false, null, false, null);
            assertThat(noop.cancelled()).isFalse();
            assertThat(noop.editorText()).isNull();
        }
    }

    @Test
    void unknownEntryThrowsVerbatim() throws Exception {
        Path dir = Files.createTempDirectory("pi-nav-unknown");
        try (var rig = rig(dir, List.of(reply("r1")))) {
            var session = rig.session();
            session.processPrompt("hi").status();
            assertThatThrownBy(() ->
                session.navigateTree("no-such-entry", false, null, false, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Entry no-such-entry not found");
        }
    }

    @Test
    void summarizingNavigationWritesABranchSummaryEntry() throws Exception {
        Path dir = Files.createTempDirectory("pi-nav-summary");
        try (var rig = rig(dir, List.of(reply("r1"), reply("r2"), reply("## Goal\nbranch work")))) {
            var session = rig.session();
            session.processPrompt("first question").status();
            session.processPrompt("second question").status();
            String target = firstUserEntryId(session);

            var nav = session.navigateTree(target, true, null, false, "explored");

            assertThat(nav.cancelled()).isFalse();
            var summary = nav.summaryEntry();
            assertThat(summary).isNotNull();
            assertThat(summary.summary()).startsWith(
                "The user explored a different conversation branch before returning here.");
            assertThat(summary.summary()).contains("## Goal\nbranch work");
            assertThat(summary.fromHook()).isEqualTo(Boolean.FALSE);
            // label 贴摘要 entry（pi :4118-4121）。
            assertThat(session.harness() != null
                && summary.id() != null).isTrue();
            // 摘要 entry 成为新叶后的上下文种子：车道 transcript 以摘要打头。
            var transcript = session.harness().snapshot(session.laneName()).transcript();
            assertThat(transcript).isNotEmpty();
            assertThat(transcript.stream().anyMatch(e -> e.id().equals(summary.id()))).isTrue();
        }
    }
}
