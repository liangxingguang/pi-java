package com.pijava.agent.hook;

/** Hook triggered after a tool completes execution. Can patch the result field-by-field. */
@FunctionalInterface
public interface AfterToolHook {
    /**
     * Invoked after a tool completes; returns an {@link AfterToolPatch} merged
     * field-by-field into the result ({@code null} = leave unchanged) — pi's
     * {@code Partial<AgentToolResult>} contract, {@code agent-loop.ts:744-752}.
     */
    AfterToolPatch afterTool(ToolResultContext ctx);
}
