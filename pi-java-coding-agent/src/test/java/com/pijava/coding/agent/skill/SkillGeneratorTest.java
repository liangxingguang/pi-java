package com.pijava.coding.agent.skill;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-27: SkillGenerator — 名称校验、提示构造、markdown 提取。
 */
class SkillGeneratorTest {

    @Test
    void validatesSkillNames() {
        assertThat(SkillGenerator.isValidName("code-review")).isTrue();
        assertThat(SkillGenerator.isValidName("a-b-c")).isTrue();
        assertThat(SkillGenerator.isValidName("123abc")).isTrue();

        assertThat(SkillGenerator.isValidName("-lead")).isFalse();
        assertThat(SkillGenerator.isValidName("trailing-")).isFalse();
        assertThat(SkillGenerator.isValidName("UPPER")).isFalse();
        assertThat(SkillGenerator.isValidName("a b")).isFalse();
        assertThat(SkillGenerator.isValidName("x".repeat(65))).isFalse();
        assertThat(SkillGenerator.isValidName(null)).isFalse();
    }

    @Test
    void promptContainsMetadataAndInstructions() {
        var prompt = SkillGenerator.prompt("review", "Run a code review");
        assertThat(prompt).contains("name: review")
            .contains("description: Run a code review")
            .contains("frontmatter")
            .contains("Do not wrap the output in code")
            .contains("fences and do not add commentary");
    }

    @Test
    void extractMarkdownStripsCodeFences() {
        var raw = "```markdown\n---\nname: x\n---\nbody\n```\n";
        assertThat(SkillGenerator.extractMarkdown(raw)).isEqualTo("---\nname: x\n---\nbody\n");
    }

    @Test
    void extractMarkdownPassesThroughPlainText() {
        assertThat(SkillGenerator.extractMarkdown("plain output")).isEqualTo("plain output\n");
    }
}
