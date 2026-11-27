package com.pijava.coding.agent.codemode;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolExposure;
import com.pijava.agent.tool.ToolNamespace;
import com.pijava.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Model-visible description tests for {@code tool.ts:118-275}. */
class CodemodeToolDescriptionTest {

    @Test
    void introAndGlobalsAreVerbatim() {
        String description = CodemodeDescription.empty();
        assertThat(description).startsWith(
            "Run JavaScript that calls other tools. The input is raw JavaScript (not JSON, no code fence), "
                + "run as an async function body in a QuickJS sandbox: top-level `await` and `return` work. "
                + "No Node, file system, network, or timers.");
        assertThat(description).contains("`ALL_TOOLS`, `await searchTools(query, { limit?, namespace? })`");
        assertThat(description).contains("`models.list(provider?)`");
        assertThat(description).doesNotContain("models`: classifiers");
        assertThat(CodemodeDescription.SNIPPET).isEqualTo("Run JavaScript that calls other tools");
        assertThat(CodemodeDescription.GUIDELINE).isEqualTo(
            "Use codemode to batch independent tool calls (Promise.allSettled), chain them, or filter large output, "
                + "instead of many separate calls.");
    }

    @Test
    void catalogGroupsNamespacesAndSkipsDeferred() {
        AgentTool<?, ?> direct = tool("read", "Read a file", ToolExposure.DIRECT, null);
        AgentTool<?, ?> deferred = tool("slow", "Deferred tool", ToolExposure.DEFERRED, null);
        AgentTool<?, ?> mcp = tool("mcp__docs__search", "Search docs", ToolExposure.CODEMODE,
            new ToolNamespace("mcp__docs", List.of("docs"), "Docs server", null));
        String description = CodemodeDescription.describe(List.of(direct, deferred, mcp),
            Map.of("mcp__docs__search", new ToolNamespace("mcp__docs", List.of("docs"), "Docs server", null)),
            Set.of("slow"), Map.of(), null);
        assertThat(description).contains("Nested tools:", "## mcp__docs", "### `read`",
            "### `mcp__docs__search`");
        assertThat(description).doesNotContain("Deferred tool", "### `slow`");
    }

    @Test
    void inlineBudgetDropsExpensiveSectionsAndMarksListing() {
        AgentTool<?, ?> first = tool("a", "A", ToolExposure.CODEMODE, null);
        AgentTool<?, ?> second = tool("b", "B", ToolExposure.CODEMODE, null);
        String unbudgeted = CodemodeDescription.describe(List.of(first, second), Map.of(), Set.of(),
            Map.of(), null);
        assertThat(unbudgeted).contains("### `a`", "### `b`");

        // 每个 section 的成本按 ceil(长度/4) 计；10 tokens 连最省的 section 也放不下。
        String budgeted = CodemodeDescription.describe(List.of(first, second), Map.of(), Set.of(),
            Map.of(), 10);
        assertThat(budgeted).contains("Nested tools:").doesNotContain("### `a`", "### `b`");

        AgentTool<?, ?> mcp = tool("mcp__docs__search", "Search docs", ToolExposure.CODEMODE,
            new ToolNamespace("mcp__docs", List.of("docs"), "Docs server", null));
        String namespaced = CodemodeDescription.describe(List.of(mcp),
            Map.of("mcp__docs__search", mcp.namespace()), Set.of(), Map.of(), 10);
        assertThat(namespaced).contains("## mcp__docs (tools not listed)")
            .doesNotContain("### `mcp__docs__search`");
    }

    @Test
    void scriptCallAnnotationUsesRenderedOutputType() {
        AgentTool<?, ?> tool = tool("read", "Read file", ToolExposure.DIRECT, null);
        assertThat(CodemodeDescription.describeScriptCall(tool)).isEqualTo(
            "Read file\n\nCodemode: `tools.read(args)` resolves to a string.");
    }

    private static AgentTool<?, ?> tool(String name, String description, ToolExposure exposure,
                                        ToolNamespace namespace) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return description; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }
            @Override public ToolExposure exposure() { return exposure; }
            @Override public ToolNamespace namespace() { return namespace; }
            @Override public ToolResult<Object> execute(String id, Object params,
                    com.pijava.ai.AbortSignal signal, com.pijava.agent.tool.ToolUpdateCallback<Object> update,
                    com.pijava.agent.tool.ToolContext context) { return ToolResult.success(""); }
        };
    }
}
