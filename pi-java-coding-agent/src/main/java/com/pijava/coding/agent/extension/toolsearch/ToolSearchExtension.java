package com.pijava.coding.agent.extension.toolsearch;

import com.pijava.coding.agent.core.ToolDefinitions;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.ToolExposure;

/**
 * 批 27（B162/T7）：内置 tool-search 扩展（pi
 * {@code extensions/tool-search/index.ts:1-18} 全文对位）——只做一件事：注册
 * {@code tool_search} 工具（{@code defaultActive: false}，注册即不激活）。
 *
 * <p>pi 的 {@code builtInExtensions} 条目是 {@code { name: "tool-search",
 * replaceable: true, builtin: true }}（extensions/index.ts:11）；java 经
 * ServiceLoader 自动发现（{@code builtin:tool-search} 身份，{@code McpExtension}
 * 先例）。激活途径：{@code --tools} / {@code defaultTools} setting /
 * {@code setActiveTools()} / MCP 的 ensureDiscoveryActive（裁决⑥）。</p>
 *
 * <p>replaceable 对位：同名 {@code tool_search} 的第三方注册经 ToolRegistry
 * 同名替换顶替（撞名旁表见批 20/B237）；isToolSearchTool 的 schema 引用判等
 * 保证 MCP 臂不误认顶替者（pi :168-171 注释同语义）。</p>
 */
public final class ToolSearchExtension implements PiExtension {

    /** 内置名（pi extensions/index.ts:11 的 {@code name: "tool-search"}）。 */
    public static final String NAME = "tool-search";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Built-in tool_search tool (BM25 discovery over deferred tools)";
    }

    @Override
    public void register(ExtensionContext ctx) {
        // pi index.ts:12-16 逐字：registerTool({ ...createToolSearchToolDefinition({ tools: pi }),
        // defaultActive: false })。java：工具进注册表＋元数据进定义表（model-only
        // exposure＋defaultActive=false ⇒ 装配期自动激活臂跳过，AgentSession
        // isActivatedOnRegistration 门）。
        var context = ctx;
        ctx.tools().register(new ToolSearchTool(new ToolSearchTool.SearchTarget() {
            @Override
            public java.util.List<com.pijava.coding.agent.extension.ExtensionToolInfo> getAllTools() {
                return context.getAllTools();
            }

            @Override
            public java.util.List<String> getActiveTools() {
                return context.getActiveTools();
            }

            @Override
            public void setActiveTools(java.util.List<String> toolNames) {
                context.setActiveTools(toolNames);
            }
        }));
        ctx.toolDefinitions().register(ToolSearchTool.NAME,
            ToolDefinitions.Entry.of(ToolExposure.MODEL_ONLY, false));
    }
}
