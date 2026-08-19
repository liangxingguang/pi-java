package com.pijava.coding.agent.skill;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-6: FrontmatterParser — 前言解析。
 */
class FrontmatterParserTest {

    @Test
    void parsesNormalFrontmatter() throws Exception {
        var parsed = FrontmatterParser.parse("""
            ---
            name: code-review
            description: Run a code review.
            disable-model-invocation: true
            label: Review
            ---
            You review code.
            """);
        assertThat(parsed.frontmatter())
            .containsEntry("name", "code-review")
            .containsEntry("description", "Run a code review.")
            .containsEntry("disable-model-invocation", true)
            .containsEntry("label", "Review");
        assertThat(parsed.body().trim()).isEqualTo("You review code.");
    }

    @Test
    void noFrontmatterTreatsWholeFileAsBody() throws Exception {
        var parsed = FrontmatterParser.parse("Just a body without frontmatter.");
        assertThat(parsed.frontmatter()).isEmpty();
        assertThat(parsed.body()).contains("Just a body");
    }

    @Test
    void unclosedFrontmatterThrows() {
        assertThatThrownBy(() -> FrontmatterParser.parse("---\nname: x\n"))
            .isInstanceOf(FrontmatterParser.FrontmatterException.class);
    }

    @Test
    void emptyFrontmatterIsEmptyMap() throws Exception {
        var parsed = FrontmatterParser.parse("---\n---\nbody");
        assertThat(parsed.frontmatter()).isEmpty();
        assertThat(parsed.body()).isEqualTo("body\n");
    }

    @Test
    void unknownFieldsPreserved() throws Exception {
        var parsed = FrontmatterParser.parse("""
            ---
            name: x
            custom-field: some value
            ---
            """);
        assertThat(parsed.frontmatter()).containsEntry("custom-field", "some value");
    }
}
