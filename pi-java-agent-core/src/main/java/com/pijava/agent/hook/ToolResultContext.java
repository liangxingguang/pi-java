package com.pijava.agent.hook;

import java.util.Map;

import com.pijava.agent.tool.ToolResult;

/**
 * Context passed to {@code after_tool} hook — 对应 pi 的
 * {@code AfterToolCallContext}（pi 把 {@code result} 与 {@code isError} 并列传给钩子，
 * {@code agent-loop.ts:736-741}；Java 侧沿用既有的扁平字段命名）。工具抛异常时 {@code isError=true}
 * 且 {@code result} 是转换后的错误结果 —— **钩子照样会跑**（pi 在
 * {@code executePreparedToolCall} 的 catch 里转结果，{@code :708-714}）。
 *
 * @param arguments docs/14：本次调用的**改写后**参数（pi {@code ToolResultEventBase.input}，
 *                  types.ts:1247 —— pi 传的是 {@code prepared.args}）。兼容构造器给 null。
 */
public record ToolResultContext(String lane, String toolCallId, String toolName,
                                 ToolResult<?> result, boolean isError,
                                 Map<String, Object> arguments) {

    /** Defensively copies {@code arguments} when non-null. */
    public ToolResultContext {
        if (arguments != null) {
            arguments = Map.copyOf(arguments);
        }
    }

    /** docs/14 之前的五参形状（无参数载荷；存量构造点零改签）。 */
    public ToolResultContext(String lane, String toolCallId, String toolName,
                             ToolResult<?> result, boolean isError) {
        this(lane, toolCallId, toolName, result, isError, null);
    }
}
