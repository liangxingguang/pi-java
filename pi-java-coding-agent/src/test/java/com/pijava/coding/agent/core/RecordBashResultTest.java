package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.ContextEntries;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ShellResult;
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

/**
 * #57（docs/25 §3.3 ⑤⑦⑨）：pi {@code recordBashResult}
 * （agent-session.ts:3878-3899）—— 空闲即写（＋落盘），运行中暂存、
 * settled flush（pi {@code _pendingBashMessages}/agent_end，:421,3925-3940）。
 */
class RecordBashResultTest {

    @TempDir Path tmp;

    private static final ShellResult OK =
        new ShellResult("hello\n", 0, false, false, 1, 6);

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

    /** 持久装配路径（jsonl 仓）—— 空闲臂的「即刻落盘」要持久会话才可见。 */
    private AgentSession persistentRig(Path sessionDir, List<List<StreamEvent>> scripted,
                                       long delayMs) throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-bash-rec", "--model", "hello",
            "--session-dir", sessionDir.toString()});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-bash-rec", scripted, delayMs));
        var handle = PersistentSessionRepositories.jsonl(sessionDir);
        return AgentSession.create(args, handle, providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.load(args.projectTrustOverride()));
    }

    private static List<Entry.BashExecution> bashEntries(AgentSession session) {
        return session.accumulatedEntries().stream()
            .filter(e -> e instanceof Entry.BashExecution)
            .map(e -> (Entry.BashExecution) e)
            .toList();
    }

    @Test
    void idleRecordAppendsAndPersistsImmediately() throws Exception {
        Path dir = Files.createTempDirectory("pi-bash-rec-idle");
        try (var session = persistentRig(dir, List.of(reply("r")), 0)) {
            session.recordBashResult("echo hello", OK, false);

            var bash = bashEntries(session);
            assertThat(bash).hasSize(1);
            assertThat(bash.getFirst().command()).isEqualTo("echo hello");
            assertThat(bash.getFirst().output()).isEqualTo("hello\n");
            assertThat(bash.getFirst().exitCode()).isZero();
            assertThat(bash.getFirst().excludeFromContext()).isNull();

            // pi appendMessage 即刻落盘 ⇒ 不等 run 收尾，文件里已经读得到。
            assertThat(Files.readString(sessionFile(dir)))
                .contains("\"role\":\"bashExecution\"")
                .contains("\"command\":\"echo hello\"");
        }
    }

    @Test
    void excludeFromContextEntryIsPersistedButNotProjected() throws Exception {
        Path dir = Files.createTempDirectory("pi-bash-rec-excl");
        try (var session = persistentRig(dir, List.of(reply("r")), 0)) {
            session.recordBashResult("secret", OK, true);

            var bash = bashEntries(session);
            assertThat(bash).hasSize(1);
            assertThat(bash.getFirst().excludeFromContext()).isTrue();
            // 投影（= LLM 上下文）里不出现 —— pi convertToLlm 的 !! 支。
            assertThat(ContextEntries.toMessages(
                session.accumulatedEntries())).isEmpty();
        }
    }

    @Test
    void recordDuringRunIsDeferredAndFlushedOnSettle() throws Exception {
        Path dir = Files.createTempDirectory("pi-bash-rec-run");
        // 事件间隔 100ms，给「运行中」窗口（RpcConcurrentPromptRoutingTest 同款手法）。
        try (var session = persistentRig(dir, List.of(reply("r1")), 100)) {
            var pending = session.processPrompt("go");
            awaitRunning(session);

            session.recordBashResult("echo hello", OK, false);
            assertThat(bashEntries(session))
                .as("运行中暂存（pi _pendingBashMessages）：settled 前不进转录")
                .isEmpty();

            pending.status();
            var entries = session.accumulatedEntries();
            var bash = bashEntries(session);
            assertThat(bash).hasSize(1);
            int bashIndex = entries.indexOf(bash.getFirst());
            int lastMessageIndex = -1;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i) instanceof Entry.Message) {
                    lastMessageIndex = i;
                }
            }
            assertThat(bashIndex)
                .as("agent_end 时 flush ⇒ 排在本轮全部消息之后（保 tool_use/tool_result 次序）")
                .isGreaterThan(lastMessageIndex);
            // 落盘同样发生在 settled 的 flush 里。
            assertThat(Files.readString(sessionFile(dir)))
                .contains("\"role\":\"bashExecution\"");
        }
    }

    /** 会话文件可能落在日期/cwd 子目录里 —— 递归找唯一的 .jsonl。 */
    private static Path sessionFile(Path dir) throws Exception {
        try (var walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".jsonl"))
                .findFirst().orElseThrow();
        }
    }

    private static void awaitRunning(AgentSession session) throws Exception {
        var lane = session.laneName();
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline
                && !session.harness().isRunning(lane)) {
            Thread.sleep(5);
        }
        assertThat(session.harness().isRunning(lane))
            .as("驱动线程已进入运行窗口").isTrue();
    }
}
