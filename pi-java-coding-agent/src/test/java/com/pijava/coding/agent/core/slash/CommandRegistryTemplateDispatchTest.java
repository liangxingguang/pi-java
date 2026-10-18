package com.pijava.coding.agent.core.slash;

import java.nio.file.Files;
import java.util.Map;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.prompt.PromptTemplates.PromptTemplate;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B247（docs/13 §3.5）：已注册模板名不是注册命令 —— dispatch 透传为 prompt
 *（返回 {@code null}），由 {@code AgentSession.expandUserPrompt} 里批 4 的
 * {@code PromptTemplateExpansion} 接手（pi：提交处理器无模板分支，落普通
 * prompt，agent-session.ts:2009 展开）。窄差 D3：未识别 slash 仍 Unknown
 * command（pi 任何 {@code /xxx} 都落 prompt）。
 */
class CommandRegistryTemplateDispatchTest {

    @Test
    void registeredTemplateNamePassesThroughAsPrompt() throws Exception {
        try (var session = createSession()) {
            session.services().promptTemplates().register(
                new PromptTemplate("review", "Review code", "body $1"));

            assertThat(session.services().slashCommands()
                    .dispatch("/review src", SlashContext.of(session)))
                .as("已注册模板名 ⇒ 非命令，透传为 prompt")
                .isNull();
        }
    }

    @Test
    void unknownSlashStillReportsUnknown() throws Exception {
        try (var session = createSession()) {
            var result = session.services().slashCommands()
                .dispatch("/nope", SlashContext.of(session))
                .toCompletableFuture().join();
            // 窄差 D3：仅放行 /skill: 前缀与已注册模板名。
            assertThat(result).contains("Unknown command: /nope");
        }
    }

    @Test
    void nullSessionContextFallsBackToUnknownWithoutNpe() {
        var registry = CommandRegistry.withBuiltins();

        var result = registry.dispatch("/review", SlashContext.of(null))
            .toCompletableFuture().join();
        assertThat(result).contains("Unknown command: /review");
    }

    @Test
    void builtinWinsOverSameNamedTemplate() throws Exception {
        try (var session = createSession()) {
            // pi 同形：模板名撞内置不去重，提交时内置先匹配胜出。
            session.services().promptTemplates().register(
                new PromptTemplate("model", "shadow", "body"));

            var result = session.services().slashCommands()
                .dispatch("/model", SlashContext.of(session))
                .toCompletableFuture().join();
            assertThat(result).isEqualTo(CommandRegistry.UI_MODEL_SELECTOR);
        }
    }

    /** 隔离 user.home 的 {@code --no-session} 夹具会话（同 RPC 测试路）。 */
    private static AgentSession createSession() throws Exception {
        var cwd = Files.createTempDirectory("pi-java-tpl-dispatch-cwd");
        var home = Files.createTempDirectory("pi-java-tpl-dispatch-home");
        String savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var args = ArgsParser.parse(new String[] {"--no-session"});
            return AgentSession.create(args, ProviderRegistry.create(),
                new ToolContext(cwd.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()));
        } finally {
            System.setProperty("user.home", savedHome);
        }
    }
}
