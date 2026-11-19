package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.pijava.examples.sdk.Sdk01Minimal;
import com.pijava.examples.sdk.Sdk02CustomModel;
import com.pijava.examples.sdk.Sdk03CustomPrompt;
import com.pijava.examples.sdk.Sdk06Extensions;
import com.pijava.examples.sdk.Sdk12FullControl;
import com.pijava.examples.sdk.Sdk13SessionRuntime;
import com.pijava.examples.sdk.SdkRunSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** docs/56 §2.2：SDK 运行型 6 件——配置投影纯函数 + 真实 AgentSession faux 闭环烟测。 */
class SdkRuntimeTypesTest {

    @TempDir Path tmp;

    @Test
    void minimal01RealSessionRun() {
        var outcome = Sdk01Minimal.run(() ->
            ExampleFixtures.newSession(tmp, ExampleFixtures.textProvider("sdk-min", "Hello from faux")));
        assertThat(Sdk01Minimal.PROMPT).isEqualTo("What files are in the current directory?");
        assertThat(outcome.result().status().exitCode()).isZero();
        assertThat(outcome.finalText()).isEqualTo("Hello from faux");
        assertThat(outcome.messageCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void customModel02Lookups() {
        var lookups = Sdk02CustomModel.lookups();
        assertThat(Sdk02CustomModel.THINKING_LEVEL).isEqualTo("medium");
        assertThat(Sdk02CustomModel.PROMPT).isEqualTo("Say hello in one sentence.");
        assertThat(lookups.get(0).message()).isEqualTo("Found model: anthropic/claude-opus-4-5");
        assertThat(lookups.get(1).message()).isEqualTo("Found custom model: my-provider/my-model");
    }

    @Test
    void customPrompt03Texts() {
        assertThat(Sdk03CustomPrompt.REPLACE_PROMPT).isEqualTo(String.join("\n",
            "You are a helpful assistant that speaks like a pirate.",
            "Always end responses with \"Arrr!\""));
        assertThat(Sdk03CustomPrompt.APPEND).isEqualTo(
            "## Additional Instructions\n- Always be concise\n- Use bullet points when listing things");
        assertThat(Sdk03CustomPrompt.appendSystemPrompt("BASE")).isEqualTo("BASE\n" + Sdk03CustomPrompt.APPEND);
        assertThat(Sdk03CustomPrompt.PROMPT_1).isEqualTo("What is 2 + 2?");
        assertThat(Sdk03CustomPrompt.HEADER_REPLACE).isEqualTo("=== Replace prompt ===");
    }

    @Test
    void extensions06Projection() {
        assertThat(Sdk06Extensions.additionalExtensionPaths())
            .containsExactly("./my-logging-extension.ts", "./my-safety-extension.ts");
        assertThat(Sdk06Extensions.INLINE_EVENT_TEXT).isEqualTo("[Inline Extension] Agent starting");
        assertThat(Sdk06Extensions.commentedExampleTexts()).contains("Processed: input");
    }

    @Test
    void fullControl12Config() {
        var config = Sdk12FullControl.config("/work");
        assertThat(config).containsEntry("cwd", "/work")
            .containsEntry("agentDir", "/tmp/my-agent")
            .containsEntry("model", "anthropic/claude-sonnet-4-5")
            .containsEntry("thinkingLevel", "off")
            .containsEntry("tools", List.of("read", "bash"));
        assertThat(config.get("systemPrompt")).isEqualTo(
            "You are a minimal assistant.\nAvailable: read, bash. Be concise.");
        assertThat(Sdk12FullControl.emptyResourceLoader())
            .allSatisfy((key, value) -> assertThat((List<?>) value).isEmpty());
    }

    @Test
    void sessionRuntime13Binding() {
        assertThat(Sdk13SessionRuntime.queuedLine(2, 3)).isEqualTo("Queued: 5");
        assertThat(Sdk13SessionRuntime.bindSessionSteps())
            .containsExactly("unsubscribe", "runtime.session", "bindExtensions", "subscribe");
        assertThat(Sdk13SessionRuntime.sessionFiles("/a", "/b", "/a"))
            .containsExactly("/a", "/b", "/a");
        assertThat(Sdk13SessionRuntime.sessionFiles(null, "/b", null))
            .containsExactly(null, "/b");
        assertThat(Sdk13SessionRuntime.afterNewLine("/new")).isEqualTo("After newSession(): /new");
    }
}
