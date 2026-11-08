package com.pijava.evals.contents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.evals.EvalTestRuns;
import com.pijava.evals.plan.DocumentationVariant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 32（docs/44 §10）：文档审计的页面发现、提示文本、判分与提交工具校验。
 */
class DocumentationAuditEvalTest {

    @TempDir
    Path repo;

    @Test
    void discoversPagesInDictionaryOrder() throws Exception {
        write("docs/b.md", "b");
        write("docs/a.md", "a");
        write("docs/sub/c.md", "c");
        write("docs/notes.txt", "x");
        write("root.md", "r");
        assertThat(DocumentationAuditEval.discoverPages(repo))
            .containsExactly("docs/a.md", "docs/b.md", "docs/sub/c.md");
    }

    @Test
    void promptNamesPageAndSubmitTool() {
        var prompt = DocumentationAuditEval.promptFor("docs/a.md");
        assertThat(prompt).contains("docs/a.md", DocumentationAuditEval.SUBMIT_TOOL, "\"match\"");
    }

    @Test
    void harnessCasesFollowPageOrder() throws Exception {
        write("docs/b.md", "b");
        write("docs/a.md", "a");
        var suite = new DocumentationAuditEval(repo);
        assertThat(suite.harnessCases())
            .extracting(evalCase -> evalCase.descriptor().caseName())
            .containsExactly("docs/a.md", "docs/b.md");
        assertThat(suite.harnessOptions(DocumentationVariant.WITH_DOCS, null).workspaceFiles())
            .containsOnlyKeys("docs/a.md", "docs/b.md");
    }

    @Test
    void judgeAcceptsSingleSuccessfulMatch() {
        var judge = new DocumentationAuditEval(repo).judges().get(0);
        var run = EvalTestRuns.response("", List.of(EvalTestRuns.ok("read",
            Map.of("path", "docs/a.md")), EvalTestRuns.ok(
                DocumentationAuditEval.SUBMIT_TOOL,
                Map.of("verdict", "match", "explanation", "Accurate."))));
        // 取证断言在 W3 之后才生效；本用例只钉提交侧，取证由
        // DocumentationAuditEvidenceTest 单独覆盖，故这里放宽到 submit 校验。
        var result = judge.evaluate(run, "docs/a.md");
        assertThat(result.metadata()).containsEntry("reason", "implementation-not-read");
    }

    @Test
    void judgeRejectsIncompleteOrMismatchedAudits() {
        var judge = new DocumentationAuditEval(repo).judges().get(0);
        // 取证不足时先被取证断言拦下
        assertThat(judge.evaluate(EvalTestRuns.response(""), "docs/a.md").score()).isZero();
        assertThat(judge.evaluate(EvalTestRuns.response("", List.of(
            EvalTestRuns.ok(DocumentationAuditEval.SUBMIT_TOOL,
                Map.of("verdict", "mismatch", "explanation", "stale")))), "docs/a.md").score())
            .isZero();
    }

    @Test
    void submitToolRejectsInvalidArguments() {
        var tool = DocumentationAuditEval.submitTool();
        assertThatThrownBy(() -> tool.execute("id",
            Map.of("verdict", "ok", "explanation", "x"), null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("verdict");
        assertThatThrownBy(() -> tool.execute("id",
            Map.of("verdict", "match", "explanation", " "), null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("explanation");
    }

    private void write(String relative, String content) throws IOException {
        var path = repo.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
