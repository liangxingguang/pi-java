package com.pijava.evals.contents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.evals.EvalTestRuns;
import com.pijava.evals.plan.DocumentationVariant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 32（docs/44 §10）：TUI 文档问答的用例、变体种入差异与 Levenshtein 判分。
 */
class TuiDocsEvalTest {

    @TempDir
    Path repo;

    @Test
    void seedsDocsOnlyForWithDocsVariant() throws IOException {
        write("docs/map/05-tui-web.md", "ChatScreen TuiExtensionUI MAX_WIDGET_LINES=10");
        write("docs/03-detailed-design.md", "extension slots");
        var suite = new TuiDocsEval(repo);
        var with = suite.harnessOptions(DocumentationVariant.WITH_DOCS, null);
        var without = suite.harnessOptions(DocumentationVariant.WITHOUT_DOCS, null);
        assertThat(with.workspaceFiles()).containsOnlyKeys(TuiDocsEval.DOC_PAGES.toArray(String[]::new));
        assertThat(without.workspaceFiles()).isEmpty();
        assertThat(suite.harnessCases()).extracting(evalCase -> evalCase.descriptor().caseName())
            .containsExactlyElementsOf(TuiDocsEval.QUESTIONS.stream()
                .map(TuiDocsEval.Question::caseName).toList());
    }

    @Test
    void levenshteinJudgeScoresExpectedAnswers() {
        var suite = new TuiDocsEval(repo);
        var question = TuiDocsEval.QUESTIONS.get(0);
        var exact = EvalTestRuns.response(question.answer());
        var wrong = EvalTestRuns.response("Something else entirely");
        var judge = suite.judges().get(0);
        assertThat(judge.evaluate(exact, question.answer()).score()).isEqualTo(1.0);
        assertThat(judge.evaluate(wrong, question.answer()).score())
            .isLessThan(TuiDocsEval.THRESHOLD);
    }

    @Test
    void promptNamesTheQuestion() {
        var question = TuiDocsEval.QUESTIONS.get(2);
        var prompt = TuiDocsEval.promptFor(question);
        assertThat(prompt).contains(question.question());
    }

    private void write(String relative, String content) throws IOException {
        var path = repo.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
