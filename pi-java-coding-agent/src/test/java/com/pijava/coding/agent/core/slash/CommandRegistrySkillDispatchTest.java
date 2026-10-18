package com.pijava.coding.agent.core.slash;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B244（docs/12 §3.2）：{@code /skill:<name>} 不是注册命令 —— dispatch 透传为
 * prompt（返回 {@code null}），由 {@code AgentSession.expandUserPrompt} 里
 * 批 4 的展开器接手（pi：提交处理器无 skill 分支，落普通 prompt）。
 */
class CommandRegistrySkillDispatchTest {

    @Test
    void skillCommandWithArgsPassesThroughAsPrompt() {
        var registry = CommandRegistry.withBuiltins();

        assertThat(registry.dispatch("/skill:foo bar", SlashContext.of(null)))
            .as("/skill: 前缀 ⇒ 非命令，透传为 prompt")
            .isNull();
    }

    @Test
    void bareSkillPrefixPassesThroughAsPrompt() {
        var registry = CommandRegistry.withBuiltins();

        assertThat(registry.dispatch("/skill:", SlashContext.of(null)))
            .as("空名也透传（展开器原文透传，批 4 语义）")
            .isNull();
    }

    @Test
    void otherUnknownCommandsStillReportUnknown() {
        var registry = CommandRegistry.withBuiltins();

        var result = registry.dispatch("/nope", SlashContext.of(null))
            .toCompletableFuture().join();
        // 窄差 D3：pi 对任何未识别 slash 都落 prompt；java 仅放行 /skill:，
        // 其余仍报 Unknown command（既有 UX）。
        assertThat(result).contains("Unknown command: /nope");
    }

    @Test
    void builtinCommandsAreUnaffected() {
        var registry = CommandRegistry.withBuiltins();

        var result = registry.dispatch("/model", SlashContext.of(null))
            .toCompletableFuture().join();
        assertThat(result).isEqualTo(CommandRegistry.UI_MODEL_SELECTOR);
    }
}
