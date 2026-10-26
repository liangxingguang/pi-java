package com.pijava.coding.agent.extension.mcp;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;

/**
 * 批 24（docs/35 T9）：{@code /mcp} 斜杠命令（pi {@code registerCommand("mcp", …)}，
 * {@code index.ts:1257-1344}）。
 *
 * <p>pi 的 {@code getArgumentCompletions}（:1261-1279）无 java 面对位
 * （{@link SlashCommand} 无补全钩）——窄差 D-new5 登记。</p>
 */
final class McpSlashCommand implements SlashCommand {

    private final McpManagerActions actions;

    McpSlashCommand(McpManagerActions actions) {
        this.actions = actions;
    }

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public String description() {
        return "Manage MCP servers: sign in, reconnect, enable or disable,"
            + " and change exposure";
    }

    @Override
    public String argumentHint() {
        return "";
    }

    @Override
    public CompletionStage<String> execute(String args, SlashContext context) {
        // pi handler 是 void——返回值不上屏（批 20 扩展命令面）；TUI 走 ui.custom
        // 阻塞本虚拟线程（批 20 I-1 的独立执行线程），非 TUI 走 notify。
        actions.runCommand(args);
        return CompletableFuture.completedFuture("");
    }
}
