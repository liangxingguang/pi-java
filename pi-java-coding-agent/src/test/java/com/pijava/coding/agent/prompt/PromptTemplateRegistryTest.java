package com.pijava.coding.agent.prompt;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PromptTemplateRegistry — 会话级提示模板注册表。
 */
class PromptTemplateRegistryTest {

    private static PromptTemplates.PromptTemplate t(String name) {
        return new PromptTemplates.PromptTemplate(name, "desc " + name, "body " + name);
    }

    @Test
    void registerAndGet() {
        var registry = new PromptTemplateRegistry();
        registry.register(t("greet"));
        assertThat(registry.get("greet").description()).isEqualTo("desc greet");
        assertThat(registry.all()).hasSize(1);
    }

    @Test
    void registerAllAndList() {
        var registry = new PromptTemplateRegistry();
        registry.registerAll(List.of(t("a"), t("b")));
        assertThat(registry.all()).extracting(
            PromptTemplates.PromptTemplate::name).containsExactly("a", "b");
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void laterRegistrationWins() {
        var registry = new PromptTemplateRegistry();
        registry.register(t("a"));
        registry.register(new PromptTemplates.PromptTemplate(
            "a", "override", "body"));
        assertThat(registry.get("a").description()).isEqualTo("override");
    }

    @Test
    void unknownNameThrows() {
        var registry = new PromptTemplateRegistry();
        assertThatThrownBy(() -> registry.get("missing"))
            .isInstanceOf(PromptTemplateRegistry.UnknownPromptTemplateException.class)
            .hasMessageContaining("missing");
    }
}
