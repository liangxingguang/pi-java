package com.pijava.agent.tool;

import java.util.List;

import com.pijava.agent.tool.builtin.*;

/**
 * Factory methods for creating tool sets.
 * Aligned with pi's tool creation patterns.
 */
public final class ToolSetFactory {
    private ToolSetFactory() {}

    /**
     * Create the full coding tool set: bash, read, write, edit, grep, ls, glob.
     *
     * @param cwd           working directory for tools (unused in factory;
     *                      ToolContext is resolved per-execution)
     * @param commandPrefix optional prefix prepended to every bash command
     */
    public static List<AgentTool<?, ?>> createCodingTools(String cwd, String commandPrefix) {
        return List.of(
            BashTool.create(commandPrefix),
            ReadTool.create(),
            WriteTool.create(),
            EditTool.create(),
            GrepTool.create(),
            LsTool.create(),
            GlobTool.create()
        );
    }

    /**
     * Create a read-only tool set: read, grep, ls, glob.
     * No mutation-capable tools included.
     */
    public static List<AgentTool<?, ?>> createReadOnlyTools(String cwd) {
        return List.of(
            ReadTool.create(),
            GrepTool.create(),
            LsTool.create(),
            GlobTool.create()
        );
    }
}
