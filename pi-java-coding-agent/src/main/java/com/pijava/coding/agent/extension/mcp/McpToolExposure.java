package com.pijava.coding.agent.extension.mcp;

import com.pijava.agent.tool.ToolExposure;
import com.pijava.mcp.config.McpExposure;

/**
 * pi 的 exposure 映射（{@code core/extensions/mcp/tools.ts:45-47} 对位）：配置面
 * {@link McpExposure} → 运行面 {@link ToolExposure}（agent-core；批 30/B203 收编——
 * pi 的 {@code ToolExposure} 住 {@code ToolDefinition} 本体，java 对位即
 * {@link com.pijava.agent.tool.AgentTool#exposure()}）。
 *
 * <p>{@code codemode} and {@code deferred} both leave tools out of the codemode description;
 * they differ only in which tool the MCP extension activates to reach them.</p>
 */
public final class McpToolExposure {

    private McpToolExposure() {}

    /**
     * Tool exposure of an MCP exposure ({@code tools.ts:45-47}).
     *
     * @param exposure the configuration's exposure
     * @return the tool exposure
     */
    public static ToolExposure of(McpExposure exposure) {
        return switch (exposure) {
            case CODEMODE -> ToolExposure.DEFERRED;
            case DEFERRED -> ToolExposure.DEFERRED;
            case DIRECT -> ToolExposure.DIRECT;
            case HIDDEN -> ToolExposure.HIDDEN;
        };
    }
}
