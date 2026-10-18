package com.pijava.coding.agent.core.slash;

import java.util.List;

import com.pijava.coding.agent.core.source.SourceInfo;
import com.pijava.coding.agent.prompt.PromptTemplates.PromptTemplate;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B247（docs/13 §3.3）：prompt template 补全条目 —— pi
 * {@code interactive-mode.ts:787-792}（无条件注册、{@code [tag]} 前缀、
 * argumentHint 透传）。
 */
class PromptTemplateItemsTest {

    @Test
    void listsEveryTemplateUnconditionallyWithTags() {
        var entries = PromptTemplateItems.list(List.of(
            template("alpha", "Alpha template", SourceInfo.Scope.USER),
            template("beta", "Beta template", SourceInfo.Scope.PROJECT),
            template("gamma", "Gamma template", SourceInfo.Scope.TEMPORARY)));

        // pi :787-792 无门（对照 skill 臂的 enableSkillCommands）。
        assertThat(entries).containsExactly(
            new PromptTemplateItems.Entry("alpha", "[u] Alpha template", null),
            new PromptTemplateItems.Entry("beta", "[p] Beta template", null),
            new PromptTemplateItems.Entry("gamma", "[t] Gamma template", null));
    }

    @Test
    void emptyDescriptionFallsBackToBareTag() {
        var entries = PromptTemplateItems.list(List.of(
            template("nodesc", "", SourceInfo.Scope.PROJECT)));

        // pi prefixAutocompleteDescription（:706-707）：描述空 ⇒ 仅 [tag]。
        assertThat(entries)
            .containsExactly(new PromptTemplateItems.Entry("nodesc", "[p]", null));
    }

    @Test
    void argumentHintPassesThrough() {
        var template = new PromptTemplate("review", "Review code", "body $1",
            "<file> [focus]", "/tmp/review.md",
            info(SourceInfo.Scope.USER));

        var entries = PromptTemplateItems.list(List.of(template));

        // pi :790：argumentHint 随行（skill 条目没有，模板条目有）。
        assertThat(entries.get(0).argumentHint()).isEqualTo("<file> [focus]");
    }

    @Test
    void absentSourceInfoLeavesDescriptionUnprefixed() {
        // 兼容构造/手造实例无 sourceInfo ⇒ pi :703-705 缺席短路，描述原文。
        var entries = PromptTemplateItems.list(List.of(
            new PromptTemplate("plain", "Plain description", "body")));

        assertThat(entries).containsExactly(
            new PromptTemplateItems.Entry("plain", "Plain description", null));
    }

    private static PromptTemplate template(String name, String description,
            SourceInfo.Scope scope) {
        return new PromptTemplate(name, description, "body", null,
            "/tmp/" + name + ".md", info(scope));
    }

    private static SourceInfo info(SourceInfo.Scope scope) {
        return new SourceInfo("/tmp/x.md", "local", scope,
            SourceInfo.Origin.TOP_LEVEL, "/tmp");
    }
}
