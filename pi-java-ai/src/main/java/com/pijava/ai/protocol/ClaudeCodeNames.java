package com.pijava.ai.protocol;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.pijava.ai.api.ToolDefinition;

/**
 * B81：OAuth（Claude Code 伪装）车道的身份常量与工具名双向映射 —— pi
 * {@code api/anthropic-messages.ts:96-132} 的逐字移植。
 *
 * <p>订阅 OAuth token 走 Claude Code 身份：固定伪装版本号、系统提示前缀、两枚身份 beta，
 * 以及把工具名映射成 Claude Code 2.x 的规范大小写（出站）/ 在当前工具集内回到真名
 * （入站）。非 OAuth 车道一切不变（{@code oauth=false} 时各方法都是恒等）。</p>
 */
final class ClaudeCodeNames {

    private ClaudeCodeNames() {
    }

    /** pi {@code anthropic-messages.ts:96} 的硬编码值（不读运行时版本）。 */
    static final String CLAUDE_CODE_VERSION = "2.1.280";

    /** OAuth 系统提示前缀（pi {@code :1172}）。ASCII 字面量，不过 surrogate 净化。 */
    static final String SYSTEM_PREFIX =
        "You are Claude Code, Anthropic's official CLI for Claude.";

    /** OAuth 身份 beta 对（pi {@code :1106}），恒在 beta 序列最前。 */
    static final List<String> OAUTH_BETAS =
        List.of("claude-code-20250219", "oauth-2025-04-20");

    /**
     * Claude Code 2.x 工具名（规范大小写），pi {@code :102-116} 的 17 枚常量表，
     * 来源 cchistory 2.1.11。键为小写名。
     */
    private static final Map<String, String> LOOKUP = Map.ofEntries(
        Map.entry("read", "Read"),
        Map.entry("write", "Write"),
        Map.entry("edit", "Edit"),
        Map.entry("bash", "Bash"),
        Map.entry("grep", "Grep"),
        Map.entry("glob", "Glob"),
        Map.entry("askuserquestion", "AskUserQuestion"),
        Map.entry("enterplanmode", "EnterPlanMode"),
        Map.entry("exitplanmode", "ExitPlanMode"),
        Map.entry("killshell", "KillShell"),
        Map.entry("notebookedit", "NotebookEdit"),
        Map.entry("skill", "Skill"),
        Map.entry("task", "Task"),
        Map.entry("taskoutput", "TaskOutput"),
        Map.entry("todowrite", "TodoWrite"),
        Map.entry("webfetch", "WebFetch"),
        Map.entry("websearch", "WebSearch"));

    /**
     * 出站（pi {@code toClaudeCodeName}，{@code :124}）：小写命中常量表 ⇒ CC 规范名；
     * 不命中（MCP 工具、占位符等）原样。非 OAuth 恒等。
     */
    static String toWire(String name, boolean oauth) {
        if (!oauth || name == null) {
            return name;
        }
        return LOOKUP.getOrDefault(name.toLowerCase(Locale.ROOT), name);
    }

    /**
     * 入站（pi {@code fromClaudeCodeName}，{@code :125-132}）：<b>只</b>在当前工具集里
     * 做大小写不敏感匹配，命中则回到本仓工具真名；不命中（模型乱开 {@code KillShell}
     * 之类）原样透传，交给工具分发层按未知名失败。非 OAuth 车道不调用。
     */
    static String fromWire(String name, List<ToolDefinition> currentTools) {
        if (currentTools != null) {
            for (ToolDefinition tool : currentTools) {
                if (tool.name().equalsIgnoreCase(name)) {
                    return tool.name();
                }
            }
        }
        return name;
    }
}
