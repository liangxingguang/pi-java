package com.pijava.agent.tool;

import org.jspecify.annotations.Nullable;

/**
 * pi {@code ToolNamespace}（{@code core/extensions/types.ts:534-543} 逐字）——
 * 工具所属的组，例如它的 MCP 服务器（tool_search 的文档构建按组聚合）。
 *
 * @param name         组名（如 {@code mcp__docs}）
 * @param description  组描述（MCP 服务器配置 description）
 * @param instructions 组指令（MCP 服务器的 instructions）
 */
public record ToolNamespace(
        String name,
        @Nullable String description,
        @Nullable String instructions) {
}
