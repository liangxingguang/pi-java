package com.pijava.ai.api;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolDefinition render fields（pi {@code ToolDefinition} label/promptSnippet/guidelines/renderShell）。
 */
class ToolDefinitionTest {

    @Test
    void defaultsLabelToNameAndRenderShellToDefault() {
        var def = new ToolDefinition("bash", "Run a command", Map.of("type", "object"));
        assertThat(def.label()).isEqualTo("bash");
        assertThat(def.renderShell()).isEqualTo("default");
        assertThat(def.promptSnippet()).isNull();
        assertThat(def.promptGuidelines()).isEmpty();
    }

    @Test
    void renderFieldsAreModeled() {
        var def = new ToolDefinition(
            "bash", "Run a command", Map.of("type", "object"),
            "Bash", "Run shell commands", List.of("One shot", "CWD-scoped"), "default");
        assertThat(def.label()).isEqualTo("Bash");
        assertThat(def.promptSnippet()).isEqualTo("Run shell commands");
        assertThat(def.promptGuidelines()).containsExactly("One shot", "CWD-scoped");
        assertThat(def.renderShell()).isEqualTo("default");
    }

    @Test
    void collectionsAreDefensivelyCopied() {
        var guidelines = new java.util.ArrayList<String>();
        guidelines.add("g");
        var def = new ToolDefinition("bash", "desc", Map.of(), "Bash", null, guidelines, null);
        guidelines.add("mutated");
        assertThat(def.promptGuidelines()).containsExactly("g");
    }
}
