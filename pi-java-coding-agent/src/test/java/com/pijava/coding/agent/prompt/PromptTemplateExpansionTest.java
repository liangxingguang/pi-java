package com.pijava.coding.agent.prompt;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * map #64：pi {@code expandPromptTemplate}（prompt-templates.ts:304-320）对位夹具。
 */
class PromptTemplateExpansionTest {

    private static PromptTemplateRegistry registry() {
        var registry = new PromptTemplateRegistry();
        registry.register(new PromptTemplates.PromptTemplate(
            "review", "review code", "Review $1 for issues. All: $ARGUMENTS"));
        return registry;
    }

    @Test
    void nonCommandPassesThrough() {
        assertThat(PromptTemplateExpansion.expand("hello", registry())).isEqualTo("hello");
        assertThat(PromptTemplateExpansion.expand("", registry())).isEqualTo("");
    }

    @Test
    void unknownTemplatePassesThrough() {
        assertThat(PromptTemplateExpansion.expand("/nope a b", registry())).isEqualTo("/nope a b");
    }

    @Test
    void expandsTemplateWithArgs() {
        assertThat(PromptTemplateExpansion.expand("/review Main.java extra", registry()))
            .isEqualTo("Review Main.java for issues. All: Main.java extra");
    }

    @Test
    void expandsTemplateWithoutArgs() {
        assertThat(PromptTemplateExpansion.expand("/review", registry()))
            .isEqualTo("Review  for issues. All: ");
    }
}
