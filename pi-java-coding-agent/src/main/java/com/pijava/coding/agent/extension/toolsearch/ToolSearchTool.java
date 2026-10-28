package com.pijava.coding.agent.extension.toolsearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.extension.ExtensionToolInfo;
import com.pijava.coding.agent.extension.ToolExposure;

/**
 * The {@code tool_search} tool（pi {@code extensions/tool-search/tool.ts:159-247}
 * 逐字）：BM25 检索未声明工具（codemode/deferred exposure），命中即并入活跃集，
 * 下一次模型请求携带其声明（经 toolsAdded 写进 transcript，随 /tree、resume、
 * fork 在分支上存活）。
 *
 * <p>自身 exposure 是 {@code model-only}（pi :231 逐字注释 "Searching is not
 * something scripts need; it changes what the model sees."）——可被声明，永不可被
 * 其他工具经 {@code ctx.executeTool()} 调用。</p>
 */
public final class ToolSearchTool implements AgentTool<Map<String, Object>, ToolSearchTool.Details> {

    /** pi {@code TOOL_SEARCH_TOOL_NAME}（:20）。 */
    public static final String NAME = "tool_search";

    /** pi {@code DEFAULT_TOOL_SEARCH_LIMIT}（:21）。 */
    public static final int DEFAULT_LIMIT = 8;

    /**
     * pi {@code TOOL_SEARCH_DESCRIPTION}（:216-220 逐字，模型可见面）。不列可搜索
     * 工具/命名空间——MCP 连接时描述不变（缓存友好，:216-219 注释）。
     */
    public static final String DESCRIPTION = "# Tool discovery\n"
        + "\n"
        + "Searches over deferred tool metadata with BM25 and exposes matching tools for the next model call.\n"
        + "\n"
        + "Some of the tools, such as tools of MCP servers, may not have been provided to you upfront, "
        + "and you should use this tool (`tool_search`) to search for the required tools. "
        + "For MCP tool discovery, always use `tool_search`.";

    /** pi {@code promptSnippet}（:226 逐字）。 */
    public static final String PROMPT_SNIPPET =
        "Search for tools that are not loaded yet and load the matches";

    /**
     * pi {@code toolSearchSchema}（:159-166 的 TypeBox 展开形）。<b>身份判定的
     * 锚</b>：{@link #isToolSearchTool} 用本对象的引用相等（pi :168-171 的
     * {@code tool.parameters === toolSearchSchema} 对位）。
     */
    public static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "query", Map.of("type", "string",
                "description", "Search query for deferred tools."),
            "limit", Map.of("type", "number",
                "description", "Maximum number of tools to return. Defaults to 8.")),
        "required", List.of("query"));

    /** pi {@code ToolSearchToolDetails}（:178-181）：本次调用加载的工具名。 */
    public record Details(List<String> loaded) {

        /** Defensive copy. */
        public Details {
            loaded = List.copyOf(loaded);
        }
    }

    /**
     * pi {@code ToolSearchToolOptions.tools}（:182-189）——扩展 API 的三方法投影
     * （{@code getAllTools/getActiveTools/setActiveTools}）。
     */
    public interface SearchTarget {

        /** All registered tools (with exposure/namespace metadata). */
        List<ExtensionToolInfo> getAllTools();

        /** Names of the currently active tools. */
        List<String> getActiveTools();

        /** Replace the active tool set (loads land in the transcript). */
        void setActiveTools(List<String> toolNames);
    }

    /**
     * pi {@code isToolSearchTool}（:168-171 逐字）：同名不算——schema 对象引用
     * 相等才算本工具（同名第三方工具不得被 MCP 自动激活臂误认）。
     */
    public static boolean isToolSearchTool(ExtensionToolInfo tool) {
        return NAME.equals(tool.name()) && tool.parameters() == SCHEMA;
    }

    /** pi {@code isSearchable}（:191-194 逐字）。 */
    static boolean isSearchable(ToolExposure exposure) {
        return exposure == ToolExposure.CODEMODE || exposure == ToolExposure.DEFERRED;
    }

    /**
     * pi {@code searchAndLoad}（:196-214 逐字）：候选＝codemode/deferred 且未激活；
     * 命中即并入活跃集（只增不减）。
     */
    static List<ExtensionToolInfo> searchAndLoad(SearchTarget tools, String query, int limit) {
        var active = tools.getActiveTools();
        var candidates = new ArrayList<ExtensionToolInfo>();
        for (var tool : tools.getAllTools()) {
            if (isSearchable(tool.exposure()) && !active.contains(tool.name())) {
                candidates.add(tool);
            }
        }
        var documents = candidates.stream()
            .map(tool -> ToolSearchDocument.of(tool, tool.namespace())).toList();
        var matches = new Bm25Ranker().rank(query, documents, limit);
        if (!matches.isEmpty()) {
            var next = new ArrayList<>(active);
            for (var match : matches) {
                next.add(match.name());
            }
            tools.setActiveTools(next);
        }
        var loaded = new ArrayList<ExtensionToolInfo>();
        for (var match : matches) {
            var found = candidates.stream()
                .filter(tool -> tool.name().equals(match.name())).findFirst();
            loaded.add(found.orElse(new ExtensionToolInfo(match.name(), "", List.of())));
        }
        return loaded;
    }

    private final SearchTarget tools;

    public ToolSearchTool(SearchTarget tools) {
        this.tools = tools;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String label() {
        return NAME; // pi :224（label 与 name 同串）
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public String promptSnippet() {
        return PROMPT_SNIPPET;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return SCHEMA;
    }

    @Override
    public ExecutionMode executionMode() {
        // pi 无显式档位（默认并行批的一员）；搜索只改活跃集，幂等可并行。
        return new ExecutionMode.Parallel();
    }

    /** pi {@code execute}（:233-246 逐字——校验两错文＋返回文本模板）。 */
    @Override
    public ToolResult<Details> execute(String toolCallId, Map<String, Object> params,
            AbortSignal signal, ToolUpdateCallback<Details> onUpdate, ToolContext context) {
        var query = params.get("query") instanceof String q ? q : "";
        if (query.trim().isEmpty()) {
            throw new IllegalArgumentException("query must not be empty");
        }
        int max = DEFAULT_LIMIT;
        if (params.get("limit") instanceof Number number) {
            double value = number.doubleValue();
            // pi 的 Number.isInteger(max) || max <= 0 校验
            if (value != Math.rint(value) || value <= 0 || value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("limit must be a positive integer");
            }
            max = (int) value;
        }
        var loaded = tools != null ? searchAndLoad(tools, query, max) : List.<ExtensionToolInfo>of();
        String text;
        if (loaded.isEmpty()) {
            text = "No matching tools found.";
        } else {
            var lines = new StringBuilder("Loaded ").append(loaded.size())
                .append(loaded.size() == 1 ? " tool" : " tools")
                .append(". They are available from your next call:\n");
            for (var i = 0; i < loaded.size(); i++) {
                var tool = loaded.get(i);
                if (i > 0) {
                    lines.append('\n');
                }
                // pi :243：description.trim().split(/\r?\n/)[0]
                lines.append("- ").append(tool.name()).append(": ")
                    .append(tool.description().trim().split("\r?\n")[0]);
            }
            text = lines.toString();
        }
        return new ToolResult<>(List.of(new ContentBlock.TextContent(text)),
            new Details(loaded.stream().map(ExtensionToolInfo::name).toList()),
            null, false, List.of());
    }
}
