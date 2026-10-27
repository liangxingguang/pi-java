package com.pijava.agent.hook;

import java.util.Map;

/**
 * Context passed to {@code before_tool} hook.
 *
 * @param parentToolCallId 批 27（B162；pi {@code ToolCallEventBase.parentToolCallId}，
 *                         types.ts:1165-1175）：嵌套调用（某工具经
 *                         {@code ctx.executeTool()} 发起）时为父调用 id，顶层调用为
 *                         {@code null}（≙ pi 的键省略）。
 */
public record ToolCallContext(String lane, String toolCallId, String toolName,
                               Map<String, Object> arguments, String parentToolCallId) {
    /** Defensively copies {@code arguments}. */
    public ToolCallContext {
        arguments = Map.copyOf(arguments);
    }

    /** 批 27 之前的四参形状（顶层调用无父 id；存量构造点零改签）。 */
    public ToolCallContext(String lane, String toolCallId, String toolName,
                           Map<String, Object> arguments) {
        this(lane, toolCallId, toolName, arguments, null);
    }
}
