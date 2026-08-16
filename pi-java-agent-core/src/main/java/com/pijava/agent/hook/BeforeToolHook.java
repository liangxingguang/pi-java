package com.pijava.agent.hook;

/** Hook triggered before executing a tool call. Can modify arguments or deny execution. */
@FunctionalInterface
public interface BeforeToolHook {
    /** Invoked before executing a tool call; may modify arguments or deny execution. */
    BeforeToolResult beforeTool(ToolCallContext ctx);
}
