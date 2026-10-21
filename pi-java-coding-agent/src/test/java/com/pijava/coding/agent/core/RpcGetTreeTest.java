package com.pijava.coding.agent.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
import com.pijava.coding.agent.rpc.JsonlWriter;
import com.pijava.coding.agent.rpc.RpcDispatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/19 裁决③修真回归：RPC {@code get_tree} 数据源＝{@code session.getTree()}
 * （持久会话**全分支**＋解析 label，pi {@code sessionManager.getTree()} 对位），
 * 不再是 harness 单车道转录＋label 恒 null。持久 jsonl 仓（navigateTree 的门要求），
 * 故落在同包夹具（{@code PersistentSessionRepositories} 包私有）。
 */
class RpcGetTreeTest {

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

    @Test
    void getTreeServesAllBranchesWithResolvedLabels() throws Exception {
        Path sessionDir = Files.createTempDirectory("pi-rpc-tree-sessions");
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-rpc-tree", "--model", "hello",
            "--session-dir", sessionDir.toString()});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-rpc-tree",
            List.of(reply("one"), reply("two"), reply("three"))));
        var handle = PersistentSessionRepositories.jsonl(sessionDir);
        try (var session = AgentSession.create(args, handle, providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()),
                SettingsManager.load(args.projectTrustOverride()))) {
            var out = new ByteArrayOutputStream();
            var dispatcher = new RpcDispatcher(session, new JsonlWriter(out), args);

            session.processPrompt("first").status();
            session.processPrompt("second").status();

            String firstUser = session.accumulatedEntries().stream()
                .filter(e -> e instanceof Entry.Message m && "user".equals(m.message().role()))
                .map(Entry::id).findFirst().orElseThrow();
            session.setLabel(firstUser, "marked");
            // 造第二分支：回首条 user 的父级再跑一轮（harness 转录只剩新分支——旧实现的缺口）。
            session.navigateTree(firstUser, false, null, false, null);
            session.processPrompt("third").status();

            dispatcher.handleLine("{\"id\":\"4\",\"type\":\"get_tree\"}");

            String output = out.toString(StandardCharsets.UTF_8);
            assertThat(output).contains("\"command\":\"get_tree\",\"success\":true");
            var mapper = new ObjectMapper();
            JsonNode tree = null;
            for (String line : output.lines().toList()) {
                var node = mapper.readTree(line);
                if ("response".equals(node.path("type").asText())
                        && "get_tree".equals(node.path("command").asText())) {
                    tree = node.path("data").path("tree");
                }
            }
            assertThat(tree).isNotNull();
            assertThat(tree.isArray() && !tree.isEmpty()).isTrue();
            // label 解析进节点（旧实现恒 null）。
            assertThat(tree.toString()).contains("\"label\":\"marked\"");
            // 全分支：节点数＝持久会话全条目数（旧实现只有当前车道转录）。
            assertThat(countJsonNodes(tree)).isEqualTo(session.accumulatedEntries().size());
        }
    }

    private static int countJsonNodes(JsonNode nodes) {
        int count = 0;
        for (var node : nodes) {
            count++;
            count += countJsonNodes(node.path("children"));
        }
        return count;
    }
}
