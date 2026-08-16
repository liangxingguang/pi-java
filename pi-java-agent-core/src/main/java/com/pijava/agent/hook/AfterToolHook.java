package com.pijava.agent.hook;

/** Hook triggered after a tool completes execution. Can modify the result. */
@FunctionalInterface
public interface AfterToolHook {
    /** Invoked after a tool completes; may return a modified result. */
    com.pijava.agent.tool.ToolResult<?> afterTool(ToolResultContext ctx);
}
