package com.pijava.evals.harness;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 32（docs/44 §3.4）：{@link DocumentationVariants} 的三种结构错误、正常删除与
 * 变体期望校验。
 */
class DocumentationVariantsTest {

    private static final String FULL_PROMPT = "You are pi.\n<rules>\nrule line\n</rules>\n\n"
        + "<docs>\ndocs line\n</docs>\n\n<cwd>\n/workspace\n</cwd>";

    @Test
    void removesDocsButKeepsRulesAndCwd() {
        var without = DocumentationVariants.excludePiDocumentation(FULL_PROMPT);
        assertThat(without).doesNotContain("<docs>").doesNotContain("</docs>")
            .doesNotContain("docs line")
            .contains("\n<rules>\n").contains("\n<cwd>\n");
    }

    @Test
    void rejectsPromptWithoutDocsSection() {
        var prompt = "You are pi.\n<rules>\nrule line\n</rules>\n\n<cwd>\n/workspace\n</cwd>";
        assertThatThrownBy(() -> DocumentationVariants.excludePiDocumentation(prompt))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no Pi documentation section");
    }

    @Test
    void rejectsIncompleteDocsSection() {
        var prompt = "You are pi.\n<rules>\nrule line\n</rules>\n\n<docs>\ndocs line";
        assertThatThrownBy(() -> DocumentationVariants.excludePiDocumentation(prompt))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no complete Pi documentation section");
    }

    @Test
    void rejectsCwdBeforeDocs() {
        var prompt = "You are pi.\n<rules>\nrule line\n</rules>\n\n<cwd>\n/workspace\n</cwd>"
            + "\n<docs>\ndocs line\n</docs>";
        assertThatThrownBy(() -> DocumentationVariants.excludePiDocumentation(prompt))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no working-directory section");
    }

    @Test
    void excludesDocsWhenPresentButKeepsPromptWithoutDocs() {
        var prompt = "You are pi.\n<rules>\nrule line\n</rules>\n\n<cwd>\n/workspace\n</cwd>";
        assertThat(DocumentationVariants.excludePiDocumentationIfPresent(prompt))
            .isEqualTo(prompt);
        assertThat(DocumentationVariants.excludePiDocumentationIfPresent(FULL_PROMPT))
            .isEqualTo(DocumentationVariants.excludePiDocumentation(FULL_PROMPT));
    }

    @Test
    void withDocsVariantKeepsPromptUnchanged() {
        assertThat(DocumentationVariants.verifySystemPrompt(FULL_PROMPT, "with_docs", true))
            .isEqualTo(FULL_PROMPT);
    }

    @Test
    void withoutDocsVariantAcceptsExcludedPrompt() {
        var without = DocumentationVariants.excludePiDocumentation(FULL_PROMPT);
        assertThat(DocumentationVariants.verifySystemPrompt(without, "without_docs", false))
            .isEqualTo(without);
    }

    @Test
    void rejectsDocsPresenceMismatch() {
        assertThatThrownBy(() -> DocumentationVariants.verifySystemPrompt(
                "You are pi.\n<rules>\nrule line\n</rules>", "with_docs", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not match the with_docs eval variant");
    }

    @Test
    void rejectsMissingRules() {
        assertThatThrownBy(() -> DocumentationVariants.verifySystemPrompt(
                "<docs>\ndocs line\n</docs>", "with_docs", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("lost its rules");
    }

    @Test
    void nullExpectationSkipsVerification() {
        var garbage = "no sections at all";
        assertThat(DocumentationVariants.verifySystemPrompt(garbage, "any", null))
            .isEqualTo(garbage);
    }
}
