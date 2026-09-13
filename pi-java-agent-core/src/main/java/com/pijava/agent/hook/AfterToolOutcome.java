package com.pijava.agent.hook;

import com.pijava.agent.tool.ToolResult;

/**
 * {@code after_tool} 链跑完后的定局（pi 的 {@code FinalizedToolCallOutcome} 的两字段投影）。
 *
 * <p>{@code isError} 不是从 {@code ToolResult} 里读的 —— pi 把它放在结果<b>外面</b>
 * （{@code agent-loop.ts:728-729} 初值 {@code false}，钩子补丁可改 {@code :752}），
 * 因为 {@code ToolResult} 上没有这个维度（工具失败走抛异常）。</p>
 */
public record AfterToolOutcome(ToolResult<?> result, boolean isError) {}
