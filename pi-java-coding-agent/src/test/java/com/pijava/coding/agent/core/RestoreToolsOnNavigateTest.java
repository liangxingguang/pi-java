package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #77（docs/10 §4）：pi {@code _restoreToolsFromTranscript}
 * （agent-session.ts:1809-1820，调用点 {@code navigateTree:4138}）——
 * 树导航后按新叶最新系统消息的 {@code toolsAdded} 恢复激活装配。
 *
 * <p>素材构造：{@code appendLaneEntry} 注入带 {@code toolsAdded} 的系统消息
 * （生产路径上 registry 单调、只在装配时注册全量，故「注册表按名过滤」
 * 与 pi 的 {@code _isAllowedTool} 门同义；未注册名静默落 —— pi 的
 * {@code _pendingToolNames} 晚注册臂随 B221 ⑫b）。</p>
 */
class RestoreToolsOnNavigateTest {

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

    private AgentSession persistentRig(Path sessionDir) throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-restore", "--model", "hello",
            "--session-dir", sessionDir.toString()});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-restore", List.of(reply("r"))));
        var handle = PersistentSessionRepositories.jsonl(sessionDir);
        return AgentSession.create(args, handle, providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.load(args.projectTrustOverride()));
    }

    private static ToolDefinition toolDef(String name) {
        return new ToolDefinition(name, name + " tool", Map.of());
    }

    /** 追加一条 entry 并落盘（navigateTree 的目标表读持久仓）。 */
    private static void appendAndPersist(AgentSession session, Entry entry) {
        session.harness().appendLaneEntry(session.laneName(), entry);
        SessionPersistence.persistPending(session, session.session(), session.laneName());
    }

    /** 注入一条带 toolsAdded 的系统消息，返回其 entry id。 */
    private static String injectSystemMessage(AgentSession session, String id,
                                              List<ToolDefinition> toolsAdded) {
        appendAndPersist(session, new Entry.Message(id, 0, null, Instant.now(),
            new Message.SystemMessage("", Instant.now(), Map.of(), toolsAdded, List.of()), null));
        return id;
    }

    /**
     * 在目标之后再垫一条 user 条目 —— pi 的 noop 门（agent-session.ts:3989-3991）：
     * 导航目标即当前叶 ⇒ 提前返回，走不到 restore（本夹具第一版就踩在这里）。
     */
    private static void padTip(AgentSession session, String id) {
        appendAndPersist(session, new Entry.Message(id, 0, null, Instant.now(),
            new Message.UserMessage(List.of(new ContentBlock.TextContent("tip"))), null));
    }

    private static List<String> activeNames(AgentSession session) {
        return session.harness().getActiveTools().stream().map(AgentTool::name).sorted().toList();
    }

    @Test
    void navigateRestoresActiveToolsFromLeafSystemMessage() throws Exception {
        Path dir = Files.createTempDirectory("pi-restore-tools");
        try (var session = persistentRig(dir)) {
            var initial = activeNames(session);
            assertThat(initial).as("默认编码装配不止一件").hasSizeGreaterThan(1);
            String sysId = injectSystemMessage(session, "sys-nav", List.of(toolDef("read")));
            assertThat(activeNames(session))
                .as("追加本身不恢复 —— 恢复只在 navigateTree 后发生（pi :4138）")
                .isEqualTo(initial);
            padTip(session, "u-tip");

            var nav = session.navigateTree(sysId, false, null, false, null);

            assertThat(nav.cancelled()).isFalse();
            assertThat(activeNames(session))
                .as("按新叶系统消息的 toolsAdded 恢复激活装配")
                .containsExactly("read");
        }
    }

    @Test
    void navigateDropsUnregisteredToolNames() throws Exception {
        Path dir = Files.createTempDirectory("pi-restore-tools-ghost");
        try (var session = persistentRig(dir)) {
            String sysId = injectSystemMessage(session, "sys-ghost",
                List.of(toolDef("read"), toolDef("no-such-tool")));
            padTip(session, "u-tip");

            var nav = session.navigateTree(sysId, false, null, false, null);

            assertThat(nav.cancelled()).isFalse();
            assertThat(activeNames(session))
                .as("未注册名静默落（pi 的 _pendingToolNames 晚注册臂随 B221 ⑫b）")
                .containsExactly("read");
        }
    }

    @Test
    void navigateToALeafWithoutSystemMessageKeepsActiveTools() throws Exception {
        Path dir = Files.createTempDirectory("pi-restore-tools-bare");
        try (var session = persistentRig(dir)) {
            var initial = activeNames(session);
            // 裸 user 条目：路径上没有任何系统消息（pi if (!current) return ⇒ 不动）。
            appendAndPersist(session, new Entry.Message("u-nav", 0, null, Instant.now(),
                new Message.UserMessage(List.of(new ContentBlock.TextContent("hello"))), null));
            padTip(session, "u-tip");

            var nav = session.navigateTree("u-nav", false, null, false, null);

            assertThat(nav.cancelled()).isFalse();
            assertThat(activeNames(session))
                .as("新叶路径无系统消息 ⇒ 激活装配不动")
                .isEqualTo(initial);
        }
    }
}
