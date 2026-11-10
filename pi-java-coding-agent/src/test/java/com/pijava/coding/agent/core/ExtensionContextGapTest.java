package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import com.pijava.agent.harness.CompactionAbortToken;
import com.pijava.agent.harness.Context;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.DefaultExtensionContext;
import com.pijava.coding.agent.extension.ExtensionCompleteOptions;
import com.pijava.coding.agent.extension.ExtensionModelInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/50 B284：{@code ExtensionContext} 新增 10 成员的落地测试——
 * 未绑定错文、模型解析、上下文用量、补全缝与中止、只读查询。
 */
class ExtensionContextGapTest {

    @TempDir Path tmp;

    @Test
    void unboundContextThrowsForEachNewMember() {
        var ctx = new DefaultExtensionContext(null, new SkillManager());
        assertThatThrownBy(ctx::getModel)
            .hasMessage("extension context not bound: getModel");
        assertThat(ctx.modelRegistry()).isNotNull();
        assertThatThrownBy(() -> ctx.modelRegistry().find("anthropic", "claude-sonnet-4-5"))
            .hasMessage("extension context not bound: modelRegistry.find");
        assertThatThrownBy(ctx::getContextUsage)
            .hasMessage("extension context not bound: getContextUsage");
        assertThatThrownBy(() -> ctx.compact(null))
            .hasMessage("extension context not bound: compact");
        assertThatThrownBy(ctx::reload).hasMessage("extension context not bound: reload");
        assertThatThrownBy(ctx::shutdown).hasMessage("extension context not bound: shutdown");
        assertThatThrownBy(ctx::isIdle).hasMessage("extension context not bound: isIdle");
        assertThatThrownBy(ctx::getSystemPrompt)
            .hasMessage("extension context not bound: getSystemPrompt");
        assertThatThrownBy(ctx::isProjectTrusted)
            .hasMessage("extension context not bound: isProjectTrusted");
        assertThatThrownBy(() -> ctx.newSession(null))
            .hasMessage("extension context not bound: newSession");
    }

    @Test
    void catalogModelResolvesAndEmptySessionUsageIsZero() throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "anthropic", "--model", "claude-sonnet-4-5",
            "--no-session", "--no-mcp"});
        try (var session = AgentSession.create(args, InMemorySessionRepository.create(),
                ProviderRegistry.create(), toolContext(),
                SettingsManager.withStorage(new InMemorySettingsStorage()))) {
            var ctx = session.extensionShortcutContext();
            var model = ctx.getModel().orElseThrow();
            assertThat(model.provider()).isEqualTo("anthropic");
            assertThat(model.contextWindow()).isPositive();

            var usage = ctx.getContextUsage().orElseThrow();
            assertThat(usage.contextWindow()).isEqualTo(model.contextWindow());
            assertThat(usage.tokens()).isZero();
            assertThat(usage.percent()).isZero();

            assertThat(ctx.getSystemPrompt()).isNotNull();
            assertThat(ctx.isIdle()).isTrue();
            // 信任门取值随宿主设置（环境相关），只验同源。
            assertThat(ctx.isProjectTrusted())
                .isEqualTo(session.services().settings().isProjectTrusted());
        }
    }

    @Test
    void registryFindAndCompleteDelegateToHostStream() throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux", "--model", "hello", "--no-session", "--no-mcp"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.text("ok"));
        try (var session = AgentSession.create(args, InMemorySessionRepository.create(),
                providers, toolContext(),
                SettingsManager.withStorage(new InMemorySettingsStorage()))) {
            var ctx = session.extensionShortcutContext();
            var model = new ExtensionModelInfo(ModelId.of("faux", "hello"), "hello", 1000, 100);
            assertThat(ctx.modelRegistry().hasConfiguredAuth(model)).isFalse();

            var response = ctx.modelRegistry().complete(model,
                new Context("sys", List.of(userMessage("hi")), List.of()),
                ExtensionCompleteOptions.defaults());
            assertThat(response.stopReason()).isEqualTo(StopReason.STOP);
            assertThat(response.content().toString()).contains("ok");
        }
    }

    @Test
    void abortedSignalReturnsAbortedMessageWithoutStreaming() throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux", "--model", "hello", "--no-session", "--no-mcp"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.text("never"));
        try (var session = AgentSession.create(args, InMemorySessionRepository.create(),
                providers, toolContext(),
                SettingsManager.withStorage(new InMemorySettingsStorage()))) {
            var ctx = session.extensionShortcutContext();
            var model = new ExtensionModelInfo(ModelId.of("faux", "hello"), "hello", 1000, 100);
            var token = new CompactionAbortToken();
            token.abort();
            var response = ctx.modelRegistry().complete(model,
                new Context("sys", List.of(userMessage("hi")), List.of()),
                new ExtensionCompleteOptions(OptionalInt.of(1), java.util.Optional.empty(),
                    java.util.Optional.empty(), java.util.Optional.empty(), token));
            assertThat(response.stopReason()).isEqualTo(StopReason.ABORTED);
            assertThat(response.content()).isEmpty();
        }
    }

    private ToolContext toolContext() {
        return new ToolContext(tmp.toString(), Map.of(), new DefaultShellExecutor(),
            new DefaultFileSystem());
    }

    private static Message.UserMessage userMessage(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }
}
