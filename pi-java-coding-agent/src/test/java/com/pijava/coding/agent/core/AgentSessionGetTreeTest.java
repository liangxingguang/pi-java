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

/**
 * {@code AgentSession.getTree()/getLabel/setLabel}（docs/19 §3.4，pi
 * {@code sessionManager.getTree()/appendLabelChange} 对位）集成夹具：
 * 持久 jsonl 会话＋FauxProvider。
 */
class AgentSessionGetTreeTest {

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

    private AgentSession rig(Path sessionDir, List<List<StreamEvent>> scripted) throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-tree", "--model", "hello",
            "--session-dir", sessionDir.toString()});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-tree", scripted));
        var handle = PersistentSessionRepositories.jsonl(sessionDir);
        return AgentSession.create(args, handle, providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.load(args.projectTrustOverride()));
    }

    private static String firstUserEntryId(AgentSession session) {
        return session.accumulatedEntries().stream()
            .filter(e -> e instanceof Entry.Message m && "user".equals(m.message().role()))
            .map(Entry::id).findFirst().orElseThrow();
    }

    @Test
    void linearChainIsASingleRootWithTimestampOrderedChildren() throws Exception {
        Path dir = Files.createTempDirectory("pi-gettree-linear");
        try (var session = rig(dir, List.of(reply("r1"), reply("r2")))) {
            session.processPrompt("first").status();
            session.processPrompt("second").status();

            var tree = session.getTree();

            assertThat(tree).hasSize(1);
            // 链上每个节点至多一个孩子，且时间戳不下降（pi :110-117 升序）。
            var node = tree.get(0);
            var previous = node.entry().timestamp();
            int depth = 0;
            while (!node.children().isEmpty()) {
                assertThat(node.children()).hasSize(1);
                node = node.children().get(0);
                assertThat(node.entry().timestamp()).isAfterOrEqualTo(previous);
                previous = node.entry().timestamp();
                depth++;
            }
            // 两轮 prompt ⇒ 链上至少 user/assistant ×2。
            assertThat(depth).isGreaterThanOrEqualTo(3);
            // 全树条目数＝持久层条目数（全分支 OLDEST_FIRST，pi getEntries 对位）。
            assertThat(countNodes(tree)).isEqualTo(session.accumulatedEntries().size());
        }
    }

    @Test
    void labelsResolveOnNodesAndClearRemovesBoth() throws Exception {
        Path dir = Files.createTempDirectory("pi-gettree-label");
        try (var session = rig(dir, List.of(reply("r1")))) {
            session.processPrompt("hi").status();
            String target = firstUserEntryId(session);

            assertThat(session.getLabel(target)).isNull();
            session.setLabel(target, "important");

            var node = findNode(session.getTree(), target);
            assertThat(node.label()).isEqualTo("important");
            assertThat(node.labelTimestamp()).isNotNull();
            assertThat(session.getLabel(target)).isEqualTo("important");

            // 重命名：最新一条赢，时间戳跟着换。
            session.setLabel(target, "renamed");
            var renamed = findNode(session.getTree(), target);
            assertThat(renamed.label()).isEqualTo("renamed");
            assertThat(renamed.labelTimestamp())
                .isAfterOrEqualTo(node.labelTimestamp());

            // 清除：值与时间戳同删。
            session.setLabel(target, null);
            var cleared = findNode(session.getTree(), target);
            assertThat(cleared.label()).isNull();
            assertThat(cleared.labelTimestamp()).isNull();
            assertThat(session.getLabel(target)).isNull();
        }
    }

    @Test
    void navigationCreatesASecondBranchChildInTheTree() throws Exception {
        Path dir = Files.createTempDirectory("pi-gettree-branch");
        try (var session = rig(dir, List.of(reply("r1"), reply("r2"), reply("r3")))) {
            session.processPrompt("first").status();
            session.processPrompt("second").status();
            String target = firstUserEntryId(session);
            // 回到首条 user 的父级 ⇒ 再跑一轮形成分支。
            session.navigateTree(target, false, null, false, null);
            session.processPrompt("third").status();

            var tree = session.getTree();

            assertThat(countNodes(tree)).isEqualTo(session.accumulatedEntries().size());
            // 存在某个节点有两个孩子（分支点），孩子按时间戳升序。
            var branchPoint = findBranchPoint(tree);
            assertThat(branchPoint).isNotNull();
            assertThat(branchPoint.children().get(0).entry().timestamp())
                .isBeforeOrEqualTo(branchPoint.children().get(1).entry().timestamp());
        }
    }

    @Test
    void emptySessionYieldsEmptyTree() throws Exception {
        Path dir = Files.createTempDirectory("pi-gettree-empty");
        try (var session = rig(dir, List.of())) {
            assertThat(session.getTree()).isEmpty();
        }
    }

    private static int countNodes(List<SessionTreeNode> nodes) {
        int count = 0;
        var stack = new java.util.ArrayDeque<>(nodes);
        while (!stack.isEmpty()) {
            var node = stack.pop();
            count++;
            stack.addAll(node.children());
        }
        return count;
    }

    private static SessionTreeNode findNode(List<SessionTreeNode> nodes, String entryId) {
        var stack = new java.util.ArrayDeque<>(nodes);
        while (!stack.isEmpty()) {
            var node = stack.pop();
            if (node.entry().id().equals(entryId)) {
                return node;
            }
            stack.addAll(node.children());
        }
        throw new AssertionError("entry not in tree: " + entryId);
    }

    private static SessionTreeNode findBranchPoint(List<SessionTreeNode> nodes) {
        var stack = new java.util.ArrayDeque<>(nodes);
        while (!stack.isEmpty()) {
            var node = stack.pop();
            if (node.children().size() > 1) {
                return node;
            }
            stack.addAll(node.children());
        }
        return null;
    }
}
