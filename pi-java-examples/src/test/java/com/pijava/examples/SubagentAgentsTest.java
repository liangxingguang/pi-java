package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** docs/54 §2.3：subagent/agents 验收——frontmatter 解析、发现/去重/覆盖、格式。 */
class SubagentAgentsTest {

    @TempDir Path tmp;

    private static Path writeAgent(Path dir, String file, String content) throws Exception {
        Files.createDirectories(dir);
        var p = dir.resolve(file);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void parseToolList() {
        assertThat(SubagentAgents.parseToolList(List.of("read", "bash")))
            .containsExactly("read", "bash");
        assertThat(SubagentAgents.parseToolList("read, bash ,grep"))
            .containsExactly("read", "bash", "grep");
        assertThat(SubagentAgents.parseToolList(42)).isEmpty();
    }

    @Test
    void parseFrontmatterFourKeys() {
        var fm = SubagentAgents.parseFrontmatter("""
            ---
            name: reviewer
            description: Reviews code
            tools: [read, bash]
            model: gpt-5.2
            unknown: ignored
            ---
            You are a reviewer.
            """);
        assertThat(fm).containsEntry("name", "reviewer")
            .containsEntry("description", "Reviews code")
            .containsEntry("model", "gpt-5.2");
        assertThat((List<String>) fm.get("tools")).containsExactly("read", "bash");
        assertThat(fm).doesNotContainKey("unknown");
    }

    @Test
    void loadAgentRequiresNameAndDescription() throws Exception {
        var agent = SubagentAgents.loadAgent(writeAgent(tmp.resolve("agents"), "a.md", """
            ---
            name: helper
            description: Helps out
            ---
            You are helpful.
            """), "user");
        assertThat(agent).isNotNull();
        assertThat(agent.name()).isEqualTo("helper");
        assertThat(agent.systemPrompt()).isEqualTo("You are helpful.");
        assertThat(agent.source()).isEqualTo("user");

        var invalid = SubagentAgents.loadAgent(writeAgent(tmp.resolve("agents"), "b.md",
            "---\nname: x\n---\nbody"), "user");
        assertThat(invalid).isNull();
    }

    @Test
    void discoverAgentsProjectOverridesUser() throws Exception {
        var user = tmp.resolve("u");
        var project = tmp.resolve("p");
        writeAgent(user, "a.md", "---\nname: shared\ndescription: from user\n---\nuser body");
        writeAgent(user, "only-user.md", "---\nname: ou\ndescription: user only\n---\nx");
        writeAgent(project, "a.md", "---\nname: shared\ndescription: from project\n---\nproj body");

        var agents = SubagentAgents.discoverAgents(
            SubagentAgents.AgentScope.BOTH, user, project);

        assertThat(agents).hasSize(2);
        var shared = agents.stream().filter(a -> a.name().equals("shared")).findFirst().orElseThrow();
        assertThat(shared.description()).isEqualTo("from project");
        assertThat(shared.source()).isEqualTo("project");
    }

    @Test
    void findNearestProjectAgentsDir() throws Exception {
        var base = tmp.resolve("repo").resolve(".pi").resolve("agents");
        writeAgent(base, "x.md", "---\nname: x\ndescription: d\n---\nx");
        var cwd = tmp.resolve("repo").resolve("src").resolve("deep");
        Files.createDirectories(cwd);

        assertThat(SubagentAgents.findNearestProjectAgentsDir(cwd, ".pi"))
            .isEqualTo(base);
    }

    @Test
    void formatAgentList() {
        assertThat(SubagentAgents.formatAgentList(List.of())).isEqualTo("none");
        var a = new SubagentAgents.AgentConfig("rev", "Reviews", List.of(), null, "user",
            "/tmp/rev.md", "");
        assertThat(SubagentAgents.formatAgentList(List.of(a)))
            .isEqualTo("rev (user): Reviews");
    }
}
