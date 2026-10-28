package com.pijava.coding.agent.extension.toolsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.coding.agent.extension.ExtensionToolInfo;
import com.pijava.coding.agent.extension.ToolExposure;

import org.junit.jupiter.api.Test;

/**
 * 批 27（B162/T5+T7）：tool-search 的 pi 对位断言
 * （{@code test/tool-search.test.ts:18-126} 与 agent-session-mcp.test.ts 的
 * 文案断言镜像）。
 */
class ToolSearchTest {

    // ── pi :18-23 tokenize ───────────────────────────────────────────

    @Test
    void tokenizeSplitsCamelAndSnakeAndFoldsPlurals() {
        assertThat(ToolSearchTokenizer.tokenize("listIssues for the GitHub_repo"))
            .containsExactly("list", "issue", "git", "hub", "repo");
    }

    @Test
    void tokenizeDropsStopWordsAndStems() {
        assertThat(ToolSearchTokenizer.tokenize("The searches and the boxes"))
            .containsExactly("search", "box");
    }

    // ── pi :25-65 Bm25Ranker ─────────────────────────────────────────

    private static ExtensionToolInfo tool(String name, String description,
                                          Map<String, Object> parameters) {
        return new ExtensionToolInfo(name, description, List.of(), ToolExposure.DEFERRED,
            parameters, null);
    }

    /** pi 夹具：list_issues 的 state 属性描述含 "closed"。 */
    private static Map<String, Object> issuesSchema() {
        return Map.of("type", "object",
            "properties", Map.of("state", Map.of("type", "string",
                "description", "Filter by state (open or closed)")));
    }

    @Test
    void ranksByRelevanceAndHonorsLimit() {
        var docs = List.of(
            ToolSearchDocument.of(tool("read", "Read a file", Map.of("type", "object")), null),
            ToolSearchDocument.of(tool("list_issues", "List issues and search issues",
                issuesSchema()), null),
            ToolSearchDocument.of(tool("close_issue", "Close an issue", Map.of("type", "object")),
                null));

        // BM25 长度归一：close_issue 文档短（6 词）而 issue 词频与 list_issues 接近
        // ⇒ 短文档胜出（手工核算：0.774 > 0.710）。钉住排序确定性＋limit 截断。
        var matches = new Bm25Ranker().rank("issues", docs, 8);
        assertThat(matches).extracting(Bm25Ranker.Match::name)
            .containsExactly("close_issue", "list_issues");

        assertThat(new Bm25Ranker().rank("issues", docs, 1))
            .extracting(Bm25Ranker.Match::name).containsExactly("close_issue");
    }

    @Test
    void propertyNamesAndDescriptionsAreSearchable() {
        var docs = List.of(
            ToolSearchDocument.of(tool("read", "Read a file", Map.of("type", "object")), null),
            ToolSearchDocument.of(tool("list_issues", "List issues", issuesSchema()), null));

        assertThat(new Bm25Ranker().rank("closed", docs, 8))
            .extracting(Bm25Ranker.Match::name).containsExactly("list_issues");
    }

    @Test
    void unknownAndEmptyQueriesReturnNothing() {
        var docs = List.of(
            ToolSearchDocument.of(tool("list_issues", "List issues", issuesSchema()), null));

        assertThat(new Bm25Ranker().rank("zzzzqqqq", docs, 8)).isEmpty();
        assertThat(new Bm25Ranker().rank("the and of", docs, 8)).isEmpty();
        // pi 测试：无同义词（"tickets" 找不到 "issues"）
        assertThat(new Bm25Ranker().rank("tickets", docs, 8)).isEmpty();
    }

    @Test
    void namespaceTextIsIndexed() {
        var doc = ToolSearchDocument.of(tool("get_pod", "Get a pod", Map.of("type", "object")),
            new com.pijava.coding.agent.core.ToolDefinitions.Namespace(
                "mcp__k8s", "Kubernetes cluster", null));

        assertThat(new Bm25Ranker().rank("kubernetes", List.of(doc), 8))
            .extracting(Bm25Ranker.Match::name).containsExactly("get_pod");
    }

    // ── 工具本体（校验/文案/details/候选过滤） ────────────────────────

    /** 假 SearchTarget：可编程工具集＋活跃集。 */
    private static final class FakeTarget implements ToolSearchTool.SearchTarget {
        final List<ExtensionToolInfo> all = new ArrayList<>();
        final List<String> active = new ArrayList<>();

        @Override public List<ExtensionToolInfo> getAllTools() {
            return all;
        }

        @Override public List<String> getActiveTools() {
            return active;
        }

        @Override public void setActiveTools(List<String> toolNames) {
            active.clear();
            active.addAll(toolNames);
        }
    }

    private static ToolSearchTool.Details run(ToolSearchTool tool, Map<String, Object> params) {
        return tool.execute("c/1", params, null, null, null).details();
    }

    @Test
    void rejectsEmptyQueryAndBadLimit() {
        var tool = new ToolSearchTool(new FakeTarget());

        assertThatThrownBy(() -> run(tool, Map.of("query", "  ")))
            .hasMessage("query must not be empty");
        assertThatThrownBy(() -> run(tool, Map.of("query", "x", "limit", 0)))
            .hasMessage("limit must be a positive integer");
        assertThatThrownBy(() -> run(tool, Map.of("query", "x", "limit", 1.5)))
            .hasMessage("limit must be a positive integer");
    }

    @Test
    void loadsMatchesIntoTheActiveSetAndReportsThem() {
        var target = new FakeTarget();
        target.all.add(tool("mcp__docs__search", "Search the docs.", issuesSchema()));
        target.all.add(new ExtensionToolInfo("read", "Read a file", List.of(),
            ToolExposure.DIRECT, Map.of("type", "object"), null));
        target.active.add("read");
        var tool = new ToolSearchTool(target);

        var result = tool.execute("c/1", Map.of("query", "docs search"), null, null, null);

        // pi 文案逐字（agent-session-mcp.test.ts:961-963 同断言）
        assertThat(((com.pijava.ai.message.ContentBlock.TextContent)
                result.content().get(0)).text())
            .isEqualTo("Loaded 1 tool. They are available from your next call:\n"
                + "- mcp__docs__search: Search the docs.");
        assertThat(result.details().loaded()).containsExactly("mcp__docs__search");
        // 命中并入活跃集（只增不减）
        assertThat(target.active).containsExactly("read", "mcp__docs__search");
    }

    @Test
    void directAndActiveToolsAreNotCandidates() {
        var target = new FakeTarget();
        target.all.add(new ExtensionToolInfo("read", "Read a file", List.of(),
            ToolExposure.DIRECT, Map.of("type", "object"), null));
        target.all.add(tool("mcp__docs__search", "Search the docs", issuesSchema()));
        target.active.add("read");
        target.active.add("mcp__docs__search"); // 已激活 ⇒ 非候选
        var tool = new ToolSearchTool(target);

        var result = tool.execute("c/1", Map.of("query", "read search docs"), null, null, null);

        // pi 文案逐字（agent-session-mcp.test.ts:993 同断言）
        assertThat(((com.pijava.ai.message.ContentBlock.TextContent)
                result.content().get(0)).text()).isEqualTo("No matching tools found.");
        assertThat(result.details().loaded()).isEmpty();
    }

    @Test
    void pluralizedLoadedText() {
        var target = new FakeTarget();
        target.all.add(tool("list_issues", "List issues", issuesSchema()));
        target.all.add(tool("close_issue", "Close an issue", Map.of("type", "object")));
        var tool = new ToolSearchTool(target);

        var result = tool.execute("c/1", Map.of("query", "issue"), null, null, null);

        assertThat(((com.pijava.ai.message.ContentBlock.TextContent)
                result.content().get(0)).text()).startsWith("Loaded 2 tools.");
        assertThat(result.details().loaded()).hasSize(2);
    }

    @Test
    void identityRequiresSchemaObjectIdentity() {
        // pi :168-171：同名第三方工具不算本工具
        var foreign = new ExtensionToolInfo("tool_search", "impostor", List.of(),
            ToolExposure.MODEL_ONLY, Map.of("type", "object"), null);
        var genuine = new ExtensionToolInfo("tool_search", "real", List.of(),
            ToolExposure.MODEL_ONLY, ToolSearchTool.SCHEMA, null);

        assertThat(ToolSearchTool.isToolSearchTool(foreign)).isFalse();
        assertThat(ToolSearchTool.isToolSearchTool(genuine)).isTrue();
    }
}
