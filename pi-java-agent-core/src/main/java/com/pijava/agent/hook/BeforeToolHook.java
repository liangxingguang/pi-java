package com.pijava.agent.hook;

/** Hook triggered before executing a tool call. Can modify arguments or deny execution. */
@FunctionalInterface
public interface BeforeToolHook {
    BeforeToolResult beforeTool(ToolCallContext ctx);
}
