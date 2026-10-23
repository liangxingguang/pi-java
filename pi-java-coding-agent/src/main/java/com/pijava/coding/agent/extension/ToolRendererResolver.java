package com.pijava.coding.agent.extension;

import java.util.function.Supplier;

/**
 * 批 16（T4a，docs/27）：pi {@code ToolRendererResolver}（types.ts:664-667）
 * 逐字语义——返回 {@code null} ≙ undefined（不管，落后续 resolver）；
 * {@code next.get()} ≙ {@code next()}（委托剩余 resolver 再落注册工具自身）。
 */
@FunctionalInterface
public interface ToolRendererResolver {

    /**
     * 选择 {@code toolName} 的渲染器。
     *
     * @param toolName 工具名（含未注册的工具——pi 注释逐字：resumed 会话里
     *                 服务器连接前的 MCP 工具调用也要能画）
     * @param next     委托链的其余部分
     * @return 渲染器；{@code null} ⇒ 不管
     */
    ToolCallRenderer resolve(String toolName, Supplier<ToolCallRenderer> next);
}
