package com.pijava.coding.agent.extension;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.pijava.agent.skill.Skill;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.skill.SkillSource;
import com.pijava.coding.agent.core.SessionServices;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.core.source.SourceInfo;
import com.pijava.coding.agent.prompt.PromptTemplateRegistry;
import com.pijava.coding.agent.prompt.PromptTemplates;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 23（docs/34 T4，E5-20）：扩展 {@code ctx.getCommands()}——pi
 * agent-session.ts:3361-3381 三源序逐字（扩展编号名 → 模板 → {@code skill:}
 * 前缀技能），无内置命令。
 */
class ExtensionGetCommandsTest {

    private static final SourceInfo STAMP = new SourceInfo("builtin:ext-a", "builtin",
        SourceInfo.Scope.TEMPORARY, SourceInfo.Origin.TOP_LEVEL, null);

    private static SlashCommand cmd(String name) {
        return new SlashCommand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "desc-" + name;
            }

            @Override
            public String argumentHint() {
                return "";
            }

            @Override
            public CompletableFuture<String> execute(String args, SlashContext context) {
                return CompletableFuture.completedFuture("ran");
            }
        };
    }

    @Test
    void threeSourceOrderWithInvocationNames() {
        var registry = CommandRegistry.withBuiltins(); // 内置不入列（pi 语义）
        registry.extensionView("builtin:ext-a", STAMP).register(cmd("hello"));
        registry.extensionView("/ext/b.jar", null).register(cmd("dup"));
        registry.extensionView("/ext/c.jar", null).register(cmd("dup"));

        var templates = new PromptTemplateRegistry();
        var templateStamp = new SourceInfo("/home/u/.pi-java/agent/prompts/t.md", "local",
            SourceInfo.Scope.USER, SourceInfo.Origin.TOP_LEVEL, null);
        templates.register(new PromptTemplates.PromptTemplate("review", "Review it",
            "body", null, "/home/u/.pi-java/agent/prompts/t.md", templateStamp));

        var skills = new SkillManager();
        skills.register(new Skill() {
            @Override
            public String name() {
                return "demo";
            }

            @Override
            public String label() {
                return "demo";
            }

            @Override
            public String description() {
                return "Demo skill";
            }

            @Override
            public String systemPrompt() {
                return "";
            }

            @Override
            public SkillSource sourceInfo() {
                return SkillSource.USER;
            }

            @Override
            public Path filePath() {
                return Path.of("/home/u/.pi-java/agent/skills/demo/SKILL.md");
            }

            @Override
            public Path baseDir() {
                return Path.of("/home/u/.pi-java/agent/skills/demo");
            }
        });

        var services = new SessionServices(null, null, null, null, null, registry,
            null, templates);
        var ctx = new DefaultExtensionContext(services, skills,
            ExtensionUI.noop(), new ExtensionEventBus());

        var commands = ctx.getCommands();
        assertThat(commands.stream().map(c -> c.name()))
            .containsExactly("hello", "dup:1", "dup:2", "review", "skill:demo");
        assertThat(commands.stream().map(c -> c.source()))
            .containsExactly("extension", "extension", "extension", "prompt", "skill");
        assertThat(commands.get(0).sourceInfo().source()).isEqualTo("builtin");
        assertThat(commands.get(3).sourceInfo().scope())
            .isEqualTo(SourceInfo.Scope.USER);
        assertThat(commands.get(4).sourceInfo().scope())
            .isEqualTo(SourceInfo.Scope.USER);
        // per-extension 委托同面。
        assertThat(ctx.forExtension("x", "builtin:x").getCommands())
            .hasSameSizeAs(commands);
    }
}
