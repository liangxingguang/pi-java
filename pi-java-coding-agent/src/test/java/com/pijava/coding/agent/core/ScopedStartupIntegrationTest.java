package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B137 装配集成：{@code --models} patterns → scope → 初始模型/显式级别 → Ctrl+P
 * 轮转；以及 docs/12 §4⑥ 的 resume 门（存量会话不应用 scoped 初始模型，scope
 * 列表本身仍装配）。
 */
class ScopedStartupIntegrationTest {

    private static ProviderRegistry fauxProviders() {
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.text("ok"));
        return providers;
    }

    private static ToolContext toolContext(Path cwd) {
        return new ToolContext(cwd.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());
    }

    private interface HomeRun {
        void run() throws Exception;
    }

    private static void withTempHome(Path home, HomeRun body) throws Exception {
        String saved = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            body.run();
        } finally {
            System.setProperty("user.home", saved);
        }
    }

    @Test
    void modelsFlagScopesStartupModelLevelAndCycling() throws Exception {
        var home = Files.createTempDirectory("pi-scoped-home");
        var tmp = Files.createTempDirectory("pi-scoped-work");
        withTempHome(home, () -> {
            Args args = ArgsParser.parse(new String[] {
                "--models", "gemini-2.5*:max", "--no-session",
                "--no-skills", "--no-extensions"});
            try (var session = AgentSession.create(args, fauxProviders(), toolContext(tmp))) {
                var first = session.harness().getModel();
                assertThat(first.provider()).isEqualTo("google");
                assertThat(session.scopedModels()).isNotEmpty();
                assertThat(session.scopedModels()).allSatisfy(sm ->
                    assertThat(sm.model().id().provider()).isEqualTo("google"));
                // 显式级别随初始模型落到 harness（pi scoped[0].thinkingLevel）。
                assertThat(session.harness().getThinkingLevel())
                    .isInstanceOf(ModelThinkingLevel.Enabled.class);
                var enabled = (ModelThinkingLevel.Enabled) session.harness().getThinkingLevel();
                assertThat(enabled.level().label()).isEqualTo("max");

                // Ctrl+P 在 scope 内正向轮转，显式级别跟随。
                session.cycleModelForward();
                var second = session.harness().getModel();
                assertThat(second.provider()).isEqualTo("google");
                assertThat(second).isNotEqualTo(first);
                assertThat(session.harness().getThinkingLevel())
                    .isInstanceOf(ModelThinkingLevel.Enabled.class);
            }
        });
    }

    @Test
    void resumedExistingSessionSkipsScopedInitialModelButKeepsScope() throws Exception {
        var home = Files.createTempDirectory("pi-scoped-home-resume");
        var freshDir = Files.createTempDirectory("pi-scoped-fresh");
        var resumeRoot = Files.createTempDirectory("pi-scoped-resume");
        String cwd = System.getProperty("user.dir");

        // 先在 resumeRoot 放一个带消息的存量会话。
        try (var handle = PersistentSessionRepositories.jsonl(resumeRoot)) {
            var stored = handle.create(cwd, null);
            stored.createLane("default", null);
            stored.appendEntry(new ProvisionedEntry<>(new Entry.Message(
                "m1", 0, null, null,
                new Message.UserMessage(List.of(new ContentBlock.TextContent("hello"))),
                null)), "default");
        }

        withTempHome(home, () -> {
            Args scoped = ArgsParser.parse(new String[] {
                "--models", "claude-*",
                "--no-skills", "--no-extensions",
                "--session-dir", resumeRoot.toString()});

            // 新目录（无存量）⇒ scope 初始模型生效：anthropic（claude-* 裸 id
            // 只在 anthropic 分片；anthropic/* 会经双车道 bleed 到 openrouter）。
            var freshArgs = ArgsParser.parse(new String[] {
                "--models", "claude-*",
                "--no-skills", "--no-extensions",
                "--session-dir", freshDir.toString()});
            try (var fresh = AgentSession.createWeb(
                    freshArgs, fauxProviders(), toolContext(Path.of(cwd)))) {
                assertThat(fresh.harness().getModel().provider()).isEqualTo("anthropic");
                assertThat(fresh.scopedModels()).isNotEmpty();
            }

            // 存量会话 ⇒ scope 不参与初始模型（回退 settings 默认 google），
            // 但 scope 已装配、Ctrl+P 仍在 anthropic 内轮转。
            try (var resumed = AgentSession.createWeb(
                    scoped, fauxProviders(), toolContext(Path.of(cwd)))) {
                assertThat(resumed.harness().getModel().provider()).isEqualTo("google");
                assertThat(resumed.scopedModels()).isNotEmpty();
                assertThat(resumed.scopedModels()).allSatisfy(sm ->
                    assertThat(sm.model().id().provider()).isEqualTo("anthropic"));
            }
        });
    }
}
