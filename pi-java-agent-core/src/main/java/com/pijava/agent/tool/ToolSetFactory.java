package com.pijava.agent.tool;

import java.util.List;

import com.pijava.agent.tool.builtin.BashTool;
import com.pijava.agent.tool.builtin.EditTool;
import com.pijava.agent.tool.builtin.FindTool;
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
     * Create the full coding tool set, in pi {@code createAllTools} order
     * （index.ts:213-224：read, bash, edit, write, grep, find, ls；
     * powershell 不随默认集——B241 随 B236 批）。
     *
     * @param commandPrefix optional prefix prepended to every bash command
     */
    public static List<AgentTool<?, ?>> createCodingTools(String commandPrefix) {
        return List.of(
            ReadTool.create(),
            BashTool.create(commandPrefix),
            EditTool.create(),
            WriteTool.create(),
            GrepTool.create(),
            FindTool.create(),
            LsTool.create()
        );
    }

    /**
     * Create a read-only tool set, in pi {@code createReadOnlyTools} order
     * （index.ts:205-212：read, grep, find, ls）。
     */
    public static List<AgentTool<?, ?>> createReadOnlyTools() {
        return List.of(
            ReadTool.create(),
            GrepTool.create(),
            FindTool.create(),
            LsTool.create()
        );
    }
}
