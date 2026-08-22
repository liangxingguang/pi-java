package com.pijava.coding.agent.prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.tool.DefaultFileSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PromptTemplates — 模板加载/frontmatter/参数替换（pi {@code prompt-templates.ts}）。
 */
class PromptTemplatesTest {

    @Test
    void parseCommandArgsHandlesQuotesAndWhitespace() {
        assertThat(PromptTemplates.parseCommandArgs("a b 'c d' e")).containsExactly("a", "b", "c d", "e");
        assertThat(PromptTemplates.parseCommandArgs("  spaced  ")).containsExactly("spaced");
        assertThat(PromptTemplates.parseCommandArgs("\"double quoted\" x")).containsExactly("double quoted", "x");
        assertThat(PromptTemplates.parseCommandArgs("")).isEmpty();
    }

    @Test
    void substituteArgsHandlesAllPlaceholders() {
        var args = List.of("a", "b", "c");
        assertThat(PromptTemplates.substituteArgs("$1 and $2", args)).isEqualTo("a and b");
        assertThat(PromptTemplates.substituteArgs("all: $@", args)).isEqualTo("all: a b c");
        assertThat(PromptTemplates.substituteArgs("all: $ARGUMENTS", args)).isEqualTo("all: a b c");
        assertThat(PromptTemplates.substituteArgs("tail: ${@:2}", args)).isEqualTo("tail: b c");
        assertThat(PromptTemplates.substituteArgs("slice: ${@:1:2}", args)).isEqualTo("slice: a b");
        assertThat(PromptTemplates.substituteArgs("missing: $5", List.of("a"))).isEqualTo("missing: ");
    }

    @Test
    void parseFrontmatterExtractsDescriptionAndBody() {
        var parsed = PromptTemplates.parseFrontmatter("---\ndescription: Greet someone\n---\nHello $1");
        assertThat(parsed.frontmatter().get("description")).isEqualTo("Greet someone");
        assertThat(parsed.body()).isEqualTo("Hello $1");
    }

    @Test
    void noFrontmatterTreatsWholeContentAsBody() {
        var parsed = PromptTemplates.parseFrontmatter("plain body\nsecond line");
        assertThat(parsed.frontmatter()).isEmpty();
        assertThat(parsed.body()).contains("plain body");
    }

    @Test
    void descriptionFallsBackToFirstLine() {
        var parsed = PromptTemplates.parseFrontmatter("---\n---\nFirst line here\nmore");
        assertThat(parsed.frontmatter()).isEmpty();
        assertThat(parsed.body()).startsWith("First line here");
    }

    @Test
    void loadReadsMdFromDirectoryAndSkipsOthers(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("greet.md"), "---\ndescription: Greeting\n---\nHello $1");
        Files.writeString(tmp.resolve("notes.txt"), "not a template");
        Files.createDirectories(tmp.resolve("sub"));
        Files.writeString(tmp.resolve("sub/sub.md"), "nested ignored");

        var result = PromptTemplates.load(new DefaultFileSystem(), List.of(tmp.toString()));

        assertThat(result.templates()).hasSize(1);
        var template = result.templates().get(0);
        assertThat(template.name()).isEqualTo("greet");
        assertThat(template.description()).isEqualTo("Greeting");
        assertThat(template.content()).isEqualTo("Hello $1");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void formatPromptTemplateInvocationSubstitutes() {
        var template = new PromptTemplates.PromptTemplate("greet", "Greeting", "Hi $1");
        assertThat(PromptTemplates.formatPromptTemplateInvocation(template, List.of("world")))
            .isEqualTo("Hi world");
    }
}
