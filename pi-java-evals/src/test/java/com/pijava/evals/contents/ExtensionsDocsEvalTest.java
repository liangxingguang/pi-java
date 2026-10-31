package com.pijava.evals.contents;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.evals.harness.HarnessRun;
import com.pijava.evals.harness.HarnessStep;
import com.pijava.evals.harness.HarnessUsage;
import com.pijava.evals.harness.ToolCallRecord;
import com.pijava.evals.harness.TranscriptEvent;
import com.pijava.evals.plan.DocumentationVariant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 32（docs/44 §10）：扩展作者 eval 的步骤、工具集与判定（不跑模型）。
 */
class ExtensionsDocsEvalTest {

    @TempDir
    Path tmp;

    @Test
    void exposesPromptReloadPromptSteps() {
        var suite = new ExtensionsDocsEval();
        assertThat(ExtensionsDocsEval.steps()).extracting(HarnessStep::kind)
            .containsExactly("prompt", "reload", "prompt");
        var options = suite.harnessOptions(DocumentationVariant.WITH_DOCS, null);
        assertThat(options.tools()).contains("bash", ExtensionsDocsEval.HELLO_TOOL);
        assertThat(options.customTools()).hasSize(1);
        assertThat(options.customTools().get(0).name())
            .isEqualTo(ExtensionsDocsEval.INSTALL_TOOL);
        assertThat(options.sessionCustomizer()).isNotNull();
    }

    @Test
    void judgeAcceptsGreetingFlow() {
        var judge = new ExtensionsDocsEval().judges().get(0);
        var run = run("Hello, Bob!", false);
        assertThat(judge.evaluate(run, Map.of()).score()).isEqualTo(1.0);
    }

    @Test
    void judgeRejectsMissingHelloOrErrors() {
        var judge = new ExtensionsDocsEval().judges().get(0);
        assertThat(judge.evaluate(run("Hello, Bob!", true), Map.of()).score()).isZero();
        assertThat(judge.evaluate(run("Hello, Alice!", false), Map.of()).score()).isZero();
        assertThat(judge.evaluate(missingHello(), Map.of()).score()).isZero();
    }

    @Test
    void installToolRejectsMissingJar() {
        var tool = ExtensionsDocsEval.installTool(new AtomicReference<>());
        assertThatThrownBy(() -> tool.execute("id", Map.of("jar", tmp.resolve("missing.jar")
            .toString()), null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found");
    }

    private static HarnessRun run(String response, boolean errorResult) {
        var events = List.of(
            TranscriptEvent.toolCall("install-id", ExtensionsDocsEval.INSTALL_TOOL,
                Map.of("jar", "/tmp/hello.jar")),
            TranscriptEvent.toolResult("install-id", ExtensionsDocsEval.INSTALL_TOOL,
                "loaded extensions: hello", errorResult),
            TranscriptEvent.toolCall("hello-id", ExtensionsDocsEval.HELLO_TOOL,
                Map.of("name", "Bob")),
            TranscriptEvent.toolResult("hello-id", ExtensionsDocsEval.HELLO_TOOL,
                "Hello, Bob!", false));
        var calls = List.of(
            new ToolCallRecord("install-id", ExtensionsDocsEval.INSTALL_TOOL,
                Map.of("jar", "/tmp/hello.jar"), errorResult ? "error" : "ok"),
            new ToolCallRecord("hello-id", ExtensionsDocsEval.HELLO_TOOL,
                Map.of("name", "Bob"), "ok"));
        return new HarnessRun(List.of(HarnessStep.prompt("prompt")), "prompt", response,
            events, calls, new HarnessUsage("faux", "faux", 0, 0, 0, 0, 0, calls.size(), null),
            1L, "system", "sha", null, null, "run", Map.of());
    }

    private static HarnessRun missingHello() {
        var calls = List.of(new ToolCallRecord("install-id",
            ExtensionsDocsEval.INSTALL_TOOL, Map.of("jar", "/tmp/hello.jar"), "ok"));
        return new HarnessRun(List.of(HarnessStep.prompt("prompt")), "prompt", "Hello, Bob!",
            List.of(), calls,
            new HarnessUsage("faux", "faux", 0, 0, 0, 0, 0, 1, null),
            1L, "system", "sha", null, null, "run", Map.of());
    }
}
