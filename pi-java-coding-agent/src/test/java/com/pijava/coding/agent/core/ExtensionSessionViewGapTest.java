package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;

import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.DefaultExtensionContext;
import com.pijava.coding.agent.extension.ExtensionSessionView;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/50 B285：{@link ExtensionSessionView} 新增 9 成员的落地测试——
 * 未绑定错文与宿主同源（{@code --no-session} 走转录兜底）。
 */
class ExtensionSessionViewGapTest {

    @TempDir Path tmp;

    @Test
    void unboundViewThrowsForEveryMember() {
        var view = new DefaultExtensionContext(null, new SkillManager()).sessionManager();
        assertThatThrownBy(view::getEntries).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(view::getBranch).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> view.getEntry("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> view.getLabel("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(view::getLeafEntry).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(view::getSessionFile).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(view::getCwd).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(view::getSessionId).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(view::getLeafId).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(view::getTree).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptySessionViewMirrorsHost() throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "anthropic", "--model", "claude-sonnet-4-5",
            "--no-session", "--no-mcp"});
        try (var session = AgentSession.create(args, InMemorySessionRepository.create(),
                ProviderRegistry.create(), toolContext(),
                SettingsManager.withStorage(new InMemorySettingsStorage()))) {
            ExtensionSessionView view = session.extensionShortcutContext().sessionManager();
            assertThat(view.getCwd()).isEqualTo(session.cwd());
            assertThat(view.getSessionId()).isEqualTo(session.sessionId());
            assertThat(view.getSessionFile()).isNull();
            assertThat(view.getEntries()).isEmpty();
            assertThat(view.getBranch()).isEmpty();
            assertThat(view.getTree()).isEmpty();
            assertThat(view.getLeafId()).isNull();
            assertThat(view.getLeafEntry()).isNull();
            assertThat(view.getEntry("missing")).isNull();
            assertThat(view.getLabel("missing")).isNull();
        }
    }

    private ToolContext toolContext() {
        return new ToolContext(tmp.toString(), Map.of(), new DefaultShellExecutor(),
            new DefaultFileSystem());
    }
}
