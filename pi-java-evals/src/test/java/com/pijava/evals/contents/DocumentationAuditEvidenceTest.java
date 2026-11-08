package com.pijava.evals.contents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.evals.EvalTestRuns;
import com.pijava.evals.harness.ToolCallRecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L6 W3（docs/26 §4）：审计取证断言 —— pi
 * {@code documentation-audit.eval.ts:60-71} 的「必须读过页面 ＋ 必须读过实现源」。
 */
class DocumentationAuditEvidenceTest {

    @TempDir
    Path repo;

    @Test
    void requiresReadingTheAuditedPage() throws IOException {
        seedPage("docs/a.md", "see CodingAgent for details");
        seedSource("pi-java-coding-agent/src/main/java/com/pijava/coding/agent/Main.java");
        var judge = judge();

        var result = judge.evaluate(run(List.of(
            okRead("pi-java-coding-agent/src/main/java/com/pijava/coding/agent/Main.java"),
            submit())), "docs/a.md");

        assertThat(result.score()).isZero();
        assertThat(result.rationale()).contains("never read the audited page");
        assertThat(result.metadata()).containsEntry("reason", "page-not-read");
    }

    @Test
    void requiresReadingSomeImplementationSource() throws IOException {
        seedPage("docs/a.md", "see CodingAgent for details");
        seedSource("pi-java-coding-agent/src/main/java/com/pijava/coding/agent/Main.java");
        var judge = judge();

        var result = judge.evaluate(run(List.of(okRead("docs/a.md"), submit())), "docs/a.md");

        assertThat(result.score()).isZero();
        assertThat(result.rationale()).contains("read no implementation source");
        assertThat(result.metadata()).containsEntry("reason", "implementation-not-read");
    }

    @Test
    void passesWhenPageAndImplementationAreBothRead() throws IOException {
        seedPage("docs/a.md", "see CodingAgent for details");
        seedSource("pi-java-coding-agent/src/main/java/com/pijava/coding/agent/Main.java");
        var judge = judge();

        var result = judge.evaluate(run(List.of(
            okRead("docs/a.md"),
            okRead("pi-java-coding-agent/src/main/java/com/pijava/coding/agent/Main.java"),
            submit())), "docs/a.md");

        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void failedReadDoesNotCountAsEvidence() throws IOException {
        seedPage("docs/a.md", "see CodingAgent for details");
        seedSource("pi-java-coding-agent/src/main/java/com/pijava/coding/agent/Main.java");
        var judge = judge();

        var result = judge.evaluate(run(List.of(
            new ToolCallRecord("c1", "read", Map.of("path", "docs/a.md"), "error"),
            okRead("pi-java-coding-agent/src/main/java/com/pijava/coding/agent/Main.java"),
            submit())), "docs/a.md");

        assertThat(result.score()).isZero();
        assertThat(result.metadata()).containsEntry("reason", "page-not-read");
    }

    @Test
    void submitChecksStillApplyAfterEvidence() throws IOException {
        seedPage("docs/a.md", "see CodingAgent for details");
        seedSource("pi-java-coding-agent/src/main/java/com/pijava/coding/agent/Main.java");
        var judge = judge();
        var evidence = List.of(okRead("docs/a.md"),
            okRead("pi-java-coding-agent/src/main/java/com/pijava/coding/agent/Main.java"));

        var noSubmit = judge.evaluate(run(evidence), "docs/a.md");
        assertThat(noSubmit.metadata()).containsEntry("reason", "submit-count");

        var mismatch = judge.evaluate(run(List.of(evidence.get(0), evidence.get(1),
            EvalTestRuns.ok(DocumentationAuditEval.SUBMIT_TOOL,
                Map.of("verdict", "mismatch", "explanation", "stale")))), "docs/a.md");
        assertThat(mismatch.score()).isZero();
        assertThat(mismatch.metadata()).containsEntry("reason", "verdict");
    }

    @Test
    void workspaceSeedsPagesAndSelectedSources() throws IOException {
        seedPage("docs/a.md", "uses ArgsParser directly");
        seedSource("pi-java-coding-agent/src/main/java/com/pijava/coding/agent/Main.java");
        seedSource("pi-java-coding-agent/src/main/java/com/pijava/coding/agent/cli/ArgsParser.java");
        var suite = new DocumentationAuditEval(repo);

        var files = suite.workspaceFiles();
        assertThat(files).containsKey("docs/a.md")
            .containsKey("pi-java-coding-agent/src/main/java/com/pijava/coding/agent/Main.java")
            // 页面提到 ArgsParser 类名 ⇒ 该源被选中
            .containsKey("pi-java-coding-agent/src/main/java/com/pijava/coding/agent/cli/ArgsParser.java");
        assertThat(suite.implementationPaths())
            .contains("pi-java-coding-agent/src/main/java/com/pijava/coding/agent/cli/ArgsParser.java");
    }

    private com.pijava.evals.judge.Judge judge() {
        return new DocumentationAuditEval(repo).judges().get(0);
    }

    private static com.pijava.evals.harness.HarnessRun run(List<ToolCallRecord> calls) {
        return EvalTestRuns.response("", calls);
    }

    private static ToolCallRecord okRead(String path) {
        return new ToolCallRecord("c-" + path, "read", Map.of("path", path), "ok");
    }

    private static ToolCallRecord submit() {
        return EvalTestRuns.ok(DocumentationAuditEval.SUBMIT_TOOL,
            Map.of("verdict", "match", "explanation", "Accurate."));
    }

    private void seedPage(String path, String content) throws IOException {
        write(path, content);
    }

    private void seedSource(String path) throws IOException {
        write(path, "package com.pijava;\nclass Fixture {}\n");
    }

    private void write(String path, String content) throws IOException {
        var target = repo.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
