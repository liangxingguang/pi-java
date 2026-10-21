package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
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
 * docs/21（T1，B254）：{@link AgentSession#getUserMessagesForForking()} 修真规格
 * —— pi {@code getUserMessagesForForking}（agent-session.ts:4161-4178）：数据源＝
 * 会话文件<b>全分支</b>条目（非当前车道子集）、text 块空串拼接、空文本滤除、
 * 时间序（旧→新）。
 */
class AgentSessionForkMessagesTest {

    @TempDir
    Path tmp;

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

    private static Entry.Message userEntry(String id, String parentId,
                                           List<ContentBlock> content) {
        return new Entry.Message(id, 0, parentId, Instant.ofEpochMilli(1),
            new Message.UserMessage(content, null), null);
    }

    /** ①：多分支会话——他枝 user 消息也在列（pi getEntries 全文件语义）。 */
    @Test
    void forkMessagesSpanAllBranchesInPersistentSession() throws Exception {
        Path sessionDir = Files.createTempDirectory("pi-forkmsg-branch");
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-forkmsg", "--model", "hello",
            "--session-dir", sessionDir.toString()});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-forkmsg",
            List.of(reply("r1"), reply("r2"), reply("r3"))));
        var handle = PersistentSessionRepositories.jsonl(sessionDir);
        try (var session = AgentSession.create(args, handle, providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()),
                SettingsManager.load(args.projectTrustOverride()))) {
            session.processPrompt("first question").status();
            session.processPrompt("second question").status();
            String firstUserId = session.getUserMessagesForForking().get(0).entryId();
            // 导航回第一条 user（叶＝其父）再发第三问 ⇒ 会话文件出现分叉：
            // 旧枝 second/r2 仍在文件里，harness 单车道转录只剩 first/r1/third。
            session.navigateTree(firstUserId, false, null, false, null);
            session.processPrompt("third question").status();

            var messages = session.getUserMessagesForForking();
            assertThat(messages.stream().map(AgentSession.ForkMessage::text))
                .as("全分支＋提交序（pi getEntries 对位；单车道源会漏 second question）")
                .containsExactly("first question", "second question", "third question");
            // entryId 与条目一一对应（时间序旧→新）。
            var userIds = session.accumulatedEntries().stream()
                .filter(e -> e instanceof Entry.Message m && "user".equals(m.message().role()))
                .map(Entry::id).toList();
            assertThat(messages.stream().map(AgentSession.ForkMessage::entryId))
                .containsExactlyElementsOf(userIds);
        }
    }

    /** ②③④：空文本滤除、text 块空串拼接、时间序（无持久面 ⇒ harness 转录兜底）。 */
    @Test
    void emptyTextFilteredAndBlocksJoinWithoutSeparator() throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-forkmem", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-forkmem", List.of(reply("r1"))));
        try (var session = AgentSession.create(args, providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()))) {
            session.harness().seedTranscript(AgentHarness.DEFAULT_LANE, List.of(
                userEntry("u1", null, List.of(new ContentBlock.TextContent("older"))),
                // 纯 image 块 user 消息 ⇒ 空文本滤掉（pi if (text)）。
                userEntry("u2", "u1", List.of(new ContentBlock.ImageContent("image/png", "AAAA"))),
                // 多 text 块 ⇒ 空串拼接（pi contentText(content, "")），不是 "\n"。
                userEntry("u3", "u2", List.of(
                    new ContentBlock.TextContent("new"),
                    new ContentBlock.TextContent("er")))));

            var messages = session.getUserMessagesForForking();
            assertThat(messages.stream().map(AgentSession.ForkMessage::entryId))
                .containsExactly("u1", "u3");
            assertThat(messages.stream().map(AgentSession.ForkMessage::text))
                .containsExactly("older", "newer");
        }
    }
}
