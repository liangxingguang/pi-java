package com.pijava.evals.harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 32（docs/44 §10）：真 harness 的假 provider 验收 —— 单轮回复、transcript 映射、
 * usage、session 路径与 artifact 落盘。
 */
class PiCodingAgentHarnessTest {

    private static final String PROVIDER = "eval-faux";
    private static final String MODEL = "hello";

    private static List<StreamEvent> textSeq(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private static ProviderRegistry registry(List<List<StreamEvent>> responses) {
        var registry = ProviderRegistry.create();
        registry.register(FauxProvider.sequence(PROVIDER, responses));
        return registry;
    }

    private static HarnessOptions.Builder options(ProviderRegistry registry) {
        return HarnessOptions.builder()
            .name("with_docs")
            .provider(PROVIDER)
            .model(MODEL)
            .thinking("off")
            .noExtensions(true)
            .providers(registry);
    }

    @Test
    void runsSingleTurnAgainstFauxProvider(@TempDir Path tmp) {
        var registry = registry(List.of(textSeq("hello from faux")));
        var harness = new PiCodingAgentHarness(options(registry)
            .noTools(true).noSession(true).build());

        var run = harness.run("say hello");

        assertThat(run.response()).isEqualTo("hello from faux");
        assertThat(run.prompt()).isEqualTo("say hello");
        assertThat(run.steps()).hasSize(1);
        assertThat(run.events()).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("message");
            assertThat(event.role()).isEqualTo("user");
            assertThat(event.content()).isEqualTo("say hello");
        });
        assertThat(run.events()).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("message");
            assertThat(event.role()).isEqualTo("assistant");
            assertThat(event.content()).isEqualTo("hello from faux");
        });
        assertThat(run.toolCalls()).isEmpty();
        assertThat(run.usage().provider()).isEqualTo(PROVIDER);
        assertThat(run.usage().model()).isEqualTo(MODEL);
        assertThat(run.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(run.systemPromptSha256()).hasSize(64);
        assertThat(run.sessionFile()).isNull();
        assertThat(run.artifacts()).containsKey("runId");
    }

    @Test
    void writesSessionSnapshotArtifact(@TempDir Path tmp) throws Exception {
        var artifactDir = tmp.resolve("artifact");
        var registry = registry(List.of(textSeq("ok")));
        var harness = new PiCodingAgentHarness(options(registry)
            .noTools(true).sessionArtifactDir(artifactDir).build());

        var run = harness.run("hi");

        assertThat(run.sessionFile()).isNotNull();
        assertThat(run.sessionJsonl()).isNotBlank();
        assertThat(run.artifacts())
            .containsKey(PiCodingAgentHarness.PI_SESSION_SNAPSHOT_ARTIFACT);
        var artifact = Path.of(run.artifacts()
            .get(PiCodingAgentHarness.PI_SESSION_SNAPSHOT_ARTIFACT));
        assertThat(Files.isRegularFile(artifact)).isTrue();
        assertThat(artifact).hasContent(run.sessionJsonl());
    }

    @Test
    void requiresAtLeastOnePromptStep(@TempDir Path tmp) {
        var registry = registry(List.of(textSeq("ok")));
        var harness = new PiCodingAgentHarness(options(registry)
            .noTools(true).noSession(true).build());
        assertThatThrownBy(() -> harness.run(List.of(HarnessStep.reload())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one prompt step");
        assertThatThrownBy(() -> harness.run(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
