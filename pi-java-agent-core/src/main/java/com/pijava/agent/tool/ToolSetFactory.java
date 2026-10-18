package com.pijava.agent.tool;

import java.util.List;

import com.pijava.agent.tool.builtin.BashTool;
import com.pijava.agent.tool.builtin.EditTool;
import com.pijava.agent.tool.builtin.FindTool;
import com.pijava.agent.tool.builtin.GrepTool;
import com.pijava.agent.tool.builtin.LsTool;
import com.pijava.agent.tool.builtin.PowerShellTool;
import com.pijava.agent.tool.builtin.ReadTool;
import com.pijava.agent.tool.builtin.WriteTool;

/**
 * Factory methods for creating tool sets.
 * Aligned with pi's tool creation patterns.
 */
public final class ToolSetFactory {
    private ToolSetFactory() {}

    /**
     * pi {@code createAllTools} 八件注册面（tools/index.ts:213-224，B243）：read, bash,
     * powershell, edit, write, grep, find, ls。
     *
     * <p>⚠️ <b>注册≠激活</b>：pi 的 {@code DEFAULT_TOOL_NAMES} 恒
     * {@code [read,bash,edit,write]}（settings-manager.ts:83，无平台分支）——
     * powershell 全平台注册、显式激活（{@code --tools +powershell}/settings/
     * setActiveTools，激活通道 docs/11 已落）。本工厂只交注册面；激活面与
     * allowlist/exclude 注册面过滤在 coding-agent 的 {@code ToolSelection}。</p>
     *
     * @param commandPrefix optional prefix prepended to every bash command
     */
    public static List<AgentTool<?, ?>> createAllTools(String commandPrefix) {
        return List.of(
            ReadTool.create(),
            BashTool.create(commandPrefix),
            PowerShellTool.create(),
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
