package com.pijava.coding.agent.skill;

import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.agent.skill.SkillSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-6: MarkdownSkillLoader — 校验规则逐条覆盖。
 */
class MarkdownSkillLoaderTest {

    @TempDir
    Path tmp;

    private final MarkdownSkillLoader loader = new MarkdownSkillLoader();

    @Test
    void validSkillLoads() throws Exception {
        var file = write("my-skill.md", """
            ---
            name: my-skill
            description: A skill.
            label: My Skill
            ---
            Do the thing.
            """);
        var result = loader.loadFile(file, SkillSource.USER);
        assertThat(result.skill()).isPresent();
        var skill = result.skill().get();
        assertThat(skill.name()).isEqualTo("my-skill");
        assertThat(skill.label()).isEqualTo("My Skill");
        assertThat(skill.description()).isEqualTo("A skill.");
        assertThat(skill.systemPrompt()).contains("Do the thing.");
        assertThat(skill.baseDir()).isEqualTo(file.getParent());
        assertThat(skill.sourceInfo()).isEqualTo(SkillSource.USER);
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void invalidNamesAreSkipped() throws Exception {
        // 大写 / 下划线 / 连续 -- / 前后 -
        String[] bad = {"Bad-Name", "bad_name", "bad--name", "-bad", "bad-"};
        for (var name : bad) {
            var file = write(name + ".md",
                "---\nname: " + name + "\ndescription: x\n---\nbody");
            var result = loader.loadFile(file, SkillSource.USER);
            assertThat(result.skill()).as("name=%s", name).isEmpty();
            assertThat(result.diagnostics()).anyMatch(d -> "error".equals(d.level()));
        }
    }

    @Test
    void longNameIsSkipped() throws Exception {
        var name = "a".repeat(65);
        var file = write("long.md",
            "---\nname: " + name + "\ndescription: x\n---\nbody");
        var result = loader.loadFile(file, SkillSource.USER);
        assertThat(result.skill()).isEmpty();
    }

    @Test
    void missingNameFallsBackToParentDirName() throws Exception {
        var dir = Files.createDirectory(tmp.resolve("my-dir"));
        var file = dir.resolve("SKILL.md");
        Files.writeString(file, "---\ndescription: x\n---\nbody");
        var result = loader.loadFile(file, SkillSource.USER);
        assertThat(result.skill()).isPresent();
        assertThat(result.skill().get().name()).isEqualTo("my-dir");
    }

    @Test
    void missingOrEmptyDescriptionIsSkipped() throws Exception {
        for (var desc : new String[] {null, "", "   "}) {
            var content = desc == null
                ? "---\nname: x\n---\nbody"
                : "---\nname: x\ndescription: " + desc + "\n---\nbody";
            var file = write("d.md", content);
            var result = loader.loadFile(file, SkillSource.USER);
            assertThat(result.skill()).isEmpty();
            assertThat(result.diagnostics()).anyMatch(d -> "error".equals(d.level()));
        }
    }

    @Test
    void longDescriptionIsSkipped() throws Exception {
        var desc = "d".repeat(1025);
        var file = write("dl.md", "---\nname: x\ndescription: " + desc + "\n---\nbody");
        var result = loader.loadFile(file, SkillSource.USER);
        assertThat(result.skill()).isEmpty();
    }

    @Test
    void disableModelInvocationIsFlagged() throws Exception {
        var file = write("dm.md",
            "---\nname: x\ndescription: x\ndisable-model-invocation: true\n---\nbody");
        assertThat(loader.loadFile(file, SkillSource.USER).skill().get()
            .disableModelInvocation()).isTrue();
    }

    @Test
    void missingLabelFallsBackToName() throws Exception {
        var file = write("l.md", "---\nname: x\ndescription: x\n---\nbody");
        var skill = loader.loadFile(file, SkillSource.USER).skill().get();
        assertThat(skill.label()).isEqualTo("x");
    }

    @Test
    void invalidFrontmatterIsSkipped() throws Exception {
        var file = write("bad.md", "---\nname: x\n");
        var result = loader.loadFile(file, SkillSource.USER);
        assertThat(result.skill()).isEmpty();
        assertThat(result.diagnostics()).anyMatch(d -> "error".equals(d.level()));
    }

    private Path write(String name, String content) throws Exception {
        var file = tmp.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
