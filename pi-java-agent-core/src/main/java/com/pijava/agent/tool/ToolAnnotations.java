package com.pijava.agent.tool;

import org.jspecify.annotations.Nullable;

/**
 * pi {@code ToolAnnotations}（{@code core/extensions/types.ts:522-532} 四 hint 逐字）——
 * 工具行为的布尔提示（例如来自 MCP 服务器）。缺席 ≙ 未知（{@code null}），
 * 不是 {@code false}。
 */
public record ToolAnnotations(
        @Nullable Boolean readOnlyHint,
        @Nullable Boolean destructiveHint,
        @Nullable Boolean idempotentHint,
        @Nullable Boolean openWorldHint) {
}
