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
    void substituteArgsDefaultBranchesFollowJsTruthySemantics() {
        // pi :71-109 单正则五支、单 pass。default 判空是 JS 真值：空串也落 default；
        // $N 越界 ≙ undefined ⇒ default。
        var args = List.of("a", "b");
        assertThat(PromptTemplates.substituteArgs("${1:-dflt}", args)).isEqualTo("a");
        assertThat(PromptTemplates.substituteArgs("${3:-dflt}", args)).isEqualTo("dflt");
        assertThat(PromptTemplates.substituteArgs("${@:-none}", args)).isEqualTo("a b");
        assertThat(PromptTemplates.substituteArgs("${@:-none}", List.of())).isEqualTo("none");
        assertThat(PromptTemplates.substituteArgs("${ARGUMENTS:-none}", List.of())).isEqualTo("none");
        assertThat(PromptTemplates.substituteArgs("${1:-d}", List.of(""))).isEqualTo("d");
        // 切片 0 按 1 计（JS Math.max(0, N-1)）
        assertThat(PromptTemplates.substituteArgs("${@:0:1}", args)).isEqualTo("a");
        // 单 pass：替换结果里的 $ 形不再二次展开
        assertThat(PromptTemplates.substituteArgs("$1", List.of("$2", "x"))).isEqualTo("$2");
        // ${...} 形不会被简单支误吃
        assertThat(PromptTemplates.substituteArgs("${@:1:1} tail", args)).isEqualTo("a tail");
    }

    @Test
    void loadReadsArgumentHintFrontmatter(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("review.md"),
            "---\ndescription: Review\nargument-hint: <file> [focus]\n---\nReview $1");
        var result = PromptTemplates.load(new DefaultFileSystem(), List.of(tmp.toString()));
        var template = result.templates().get(0);
        // pi :19/:144：argument-hint ⇒ argumentHint；filePath 随行。
        assertThat(template.argumentHint()).isEqualTo("<file> [focus]");
        assertThat(template.filePath()).endsWith("review.md");
    }

    @Test
    void missingExplicitPathIsSilentlySkipped(@TempDir Path tmp) throws Exception {
        // pi :271-273：显式路径不存在 ⇒ 静默（无诊断）。
        var result = PromptTemplates.load(new DefaultFileSystem(),
            List.of(tmp.resolve("does-not-exist").toString()));
        assertThat(result.templates()).isEmpty();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void loadDefaultsScansAgentAndProjectPromptDirs(@TempDir Path tmp) throws Exception {
        // pi :262-265 includeDefaults：agentDir/prompts + cwd/.pi-java/prompts（品牌分叉）。
        Path agentDir = tmp.resolve("agent");
        Path cwd = tmp.resolve("proj");
        Files.createDirectories(agentDir.resolve("prompts"));
        Files.createDirectories(cwd.resolve(".pi-java/prompts"));
        Files.writeString(agentDir.resolve("prompts/u.md"), "---\ndescription: U\n---\nuser tpl");
        Files.writeString(cwd.resolve(".pi-java/prompts/p.md"), "---\ndescription: P\n---\nproj tpl");

        var result = PromptTemplates.loadDefaults(
            new DefaultFileSystem(), agentDir.toString(), cwd.toString());

        assertThat(result.templates()).extracting(PromptTemplates.PromptTemplate::name)
            .containsExactlyInAnyOrder("u", "p");
        // 缺失目录静默（proj2 无任何目录）
        var empty = PromptTemplates.loadDefaults(
            new DefaultFileSystem(), tmp.resolve("nope1").toString(), tmp.resolve("nope2").toString());
        assertThat(empty.templates()).isEmpty();
        assertThat(empty.diagnostics()).isEmpty();
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
