package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.Message;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.DefaultExtensionContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 批 28（docs/40 T2，裁决③）：{@code ExtensionContext.cwd()} 与
 * {@code sessionManager()}（{@link ExtensionSessionView} ≙ pi ReadonlySessionManager）
 * 的两臂——有宿主委托宿主会话；无宿主抛 {@link IllegalStateException}
 * （requireToolControl 同文，不落 process.cwd()）。
 */
class ExtensionContextCwdSessionViewTest {

    @TempDir Path tmp;

    @Test
    void unboundContextThrowsWithRequireToolControlText() {
        var ctx = new DefaultExtensionContext(null, new SkillManager());
        assertThatThrownBy(ctx::cwd)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("tool control requires a bound session; none is available yet");
        assertThat(ctx.sessionManager()).isNotNull();
        assertThatThrownBy(() -> ctx.sessionManager().getEntries())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("tool control requires a bound session; none is available yet");
        // per-extension 委托同臂
        var perExt = ctx.forExtension("probe");
        assertThatThrownBy(perExt::cwd).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> perExt.sessionManager().getEntries())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void boundContextDelegatesToHostSession() throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux", "--model", "hello", "--no-session", "--no-mcp"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.text("ok"));
        var session = AgentSession.create(args, InMemorySessionRepository.create(),
            providers, new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.withStorage(new InMemorySettingsStorage()));
        try (session) {
            var ctx = session.extensionShortcutContext();
            assertThat(ctx).isNotNull();
            assertThat(ctx.cwd()).isEqualTo(tmp.toString());

            // F1 臂：--no-session 无持久面 ⇒ harness 转录兜底（entries 随 prompt 增长）
            var result = session.processPrompt("hello there", PromptConfig.defaults());
            assertThat(result.status().exitCode()).isZero();
            List<Entry> entries = ctx.sessionManager().getEntries();
            assertThat(entries).anyMatch(e -> e instanceof Entry.Message m
                && m.message() instanceof Message.UserMessage u
                && u.content().toString().contains("hello there"));

            // 快照拷贝：两次调用不是同一列表实例
            assertThat(ctx.sessionManager().getEntries()).isNotSameAs(entries);
        }
    }
}
