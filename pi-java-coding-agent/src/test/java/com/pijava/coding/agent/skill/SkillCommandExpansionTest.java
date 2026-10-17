package com.pijava.coding.agent.skill;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.pijava.agent.skill.SkillSource;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * map #62：pi {@code _expandSkillCommand}（agent-session.ts:2147-2171）对位夹具。
 */
class SkillCommandExpansionTest {

    private static MarkdownSkill skill(Path dir, String name) throws Exception {
        Path file = dir.resolve(name).resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            ---
            name: %s
            description: test skill
            ---
            Body line one.
            Body line two.
            """.formatted(name));
        return new MarkdownSkill(name, null, "test skill", "eager body",
            file.getParent(), false, SkillSource.EXPLICIT, file);
    }

    @Test
    void nonSkillCommandPassesThrough() throws Exception {
        var dir = Files.createTempDirectory("skill-exp");
        var skills = Map.<String, com.pijava.agent.skill.Skill>of("demo", skill(dir, "demo"));
        assertThat(SkillCommandExpansion.expand("hello world", skills)).isEqualTo("hello world");
        assertThat(SkillCommandExpansion.expand("/other:demo", skills)).isEqualTo("/other:demo");
    }

    @Test
    void unknownSkillPassesThrough() throws Exception {
        var dir = Files.createTempDirectory("skill-exp");
        var skills = Map.<String, com.pijava.agent.skill.Skill>of("demo", skill(dir, "demo"));
        assertThat(SkillCommandExpansion.expand("/skill:nope", skills)).isEqualTo("/skill:nope");
    }

    @Test
    void expandsToSkillBlockWithoutArgs() throws Exception {
        var dir = Files.createTempDirectory("skill-exp");
        var skill = skill(dir, "demo");
        var skills = Map.<String, com.pijava.agent.skill.Skill>of("demo", skill);

        var expanded = SkillCommandExpansion.expand("/skill:demo", skills);

        // pi :2161-2163 组块逐字：重读文件剥前言（不是加载期正文快照）。
        assertThat(expanded).isEqualTo(
            "<skill name=\"demo\" location=\"" + skill.filePath() + "\">\n"
                + "References are relative to " + skill.baseDir() + ".\n\n"
                + "Body line one.\nBody line two.\n</skill>");
    }

    @Test
    void expandsWithArgsAppended() throws Exception {
        var dir = Files.createTempDirectory("skill-exp");
        var skill = skill(dir, "demo");
        var skills = Map.<String, com.pijava.agent.skill.Skill>of("demo", skill);

        var expanded = SkillCommandExpansion.expand("/skill:demo   do the thing  ", skills);

        assertThat(expanded).endsWith("</skill>\n\ndo the thing");
        assertThat(expanded).startsWith("<skill name=\"demo\"");
    }

    @Test
    void unreadableFilePassesThrough() throws Exception {
        var dir = Files.createTempDirectory("skill-exp");
        var skill = skill(dir, "demo");
        Files.delete(skill.filePath());
        var skills = Map.<String, com.pijava.agent.skill.Skill>of("demo", skill);

        // pi :2164-2170：emitError + 原文透传（java 侧先 LOG，事件随扩展包）。
        assertThat(SkillCommandExpansion.expand("/skill:demo", skills)).isEqualTo("/skill:demo");
    }
}
