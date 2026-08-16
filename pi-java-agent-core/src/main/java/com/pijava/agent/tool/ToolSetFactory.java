package com.pijava.agent.tool;

import java.util.List;

import com.pijava.agent.tool.builtin.BashTool;
import com.pijava.agent.tool.builtin.EditTool;
import com.pijava.agent.tool.builtin.GlobTool;
import com.pijava.agent.tool.builtin.GrepTool;
import com.pijava.agent.tool.builtin.LsTool;
import com.pijava.agent.tool.builtin.ReadTool;
import com.pijava.agent.tool.builtin.WriteTool;

/**
 * Factory methods for creating tool sets.
 * Aligned with pi's tool creation patterns.
 */
public final class ToolSetFactory {
    private ToolSetFactory() {}

    /**
     * Create the full coding tool set: bash, read, write, edit, grep, ls, glob.
     *
     * @param commandPrefix optional prefix prepended to every bash command
     */
    public static List<AgentTool<?, ?>> createCodingTools(String commandPrefix) {
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
     */
    public static List<AgentTool<?, ?>> createReadOnlyTools() {
        return List.of(
            ReadTool.create(),
            GrepTool.create(),
            LsTool.create(),
            GlobTool.create()
        );
    }
}
