package com.pijava.agent.tool.builtin;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.TruncationUtils;

/**
 * Shell command execution tool.
 * Aligned with pi's {@code createBashTool}（anchor ea448f454，批 3/docs/22 §1）。
 *
 * <p>Schema: { command: String, timeout?: Number } —— timeout 小数秒、无默认、
 * 上限 2147483.647 s。描述/schema/snippet/截断 notice 全部逐字；非零退出 resolve
 * {@code isError:true} 而非 throw（bash.ts:355-364）。骨架与 powershell 共享
 * {@link ShellToolSupport}（B243 重构，pi {@code createShellToolDefinition} 对位）。</p>
 *
 * <h4>Security model (Phase 2b)</h4>
 * Phase 2b runs commands directly via {@code ProcessBuilder} with no sandbox.
 * The {@code ApprovalHandler} callback (§2.4) is the primary safeguard.
 */
public final class BashTool {

    /** pi bash.ts:47（{@code bashToolSystemPromptContribution.guidelines}）逐字。 */
    static final List<String> PI_GUIDELINES =
        List.of("You can inspect PI_* environment variables for current model and session details.");

    /** pi {@code bashToolConfig}（bash.ts:417-425）逐字。 */
    static final ShellToolConfig BASH_CONFIG = new ShellToolConfig(
        "bash", "bash", "bash", "$",
        "Execute bash commands (ls, grep, find, etc.)",
        PI_GUIDELINES, "pi-bash");

    private BashTool() {}

    public record BashInput(String command, Optional<Double> timeoutSeconds) {}
    public record BashDetails(TruncationUtils.TruncationResult truncation, String fullOutputPath) {}

    /** Create the bash tool with no command prefix. */
    public static AgentTool<BashInput, BashDetails> create() {
        return create(null);
    }

    /** Create the bash tool with an optional command prefix. */
    public static AgentTool<BashInput, BashDetails> create(String commandPrefix) {
        // pi bash.ts:250：exposeSessionEnvironment 默认 true。
        return create(commandPrefix, true);
    }

    /**
     * Create the bash tool（pi {@code createBashTool} 全参形）。
     *
     * @param exposeSessionEnvironment PI_* 注入开关（pi bash.ts:221-222）
     */
    public static AgentTool<BashInput, BashDetails> create(
            String commandPrefix, boolean exposeSessionEnvironment) {
        return ShellToolSupport.create(BASH_CONFIG, commandPrefix, exposeSessionEnvironment,
            Function.identity(), null);
    }
}
