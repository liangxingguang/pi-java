package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-6: --no-skills 时 SkillManager 为空。
 */
class SkillsDisabledTest {

    @TempDir
    Path tmp;

    @Test
    void noSkillsLeavesSkillManagerEmpty() throws Exception {
        // 用户技能目录预置一个技能
        var agentDir = Files.createDirectories(tmp.resolve("agent"));
        var skillsDir = Files.createDirectories(agentDir.resolve("skills"));
        Files.writeString(skillsDir.resolve("SKILL.md"),
            "---\nname: user-skill\ndescription: u\n---\nbody");

        var args = ArgsParser.parse(new String[] {
            "--provider", "faux", "--model", "hello", "--no-session",
            "--no-skills"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.text("hi"));

        try (var session = AgentSession.create(args, providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()))) {
            assertThat(session.harness().skillManager().all()).isEmpty();
        }
    }
}
