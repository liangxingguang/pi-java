package com.pijava.evals.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.evals.api.EvalReporter;
import com.pijava.evals.harness.HarnessUsage;
import com.pijava.evals.plan.DocumentationVariant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** 批 32（docs/44 §5.3）：observation 的 JSON 往返与落盘职责。 */
class ObservationJsonTest {

    @TempDir
    Path artifactDir;

    @Test
    void roundTripsUsageScoreAndMetrics() {
        var usage = new HarnessUsage("faux", "faux", 1, 2, 3, 4, 5, 6L, null);
        var observation = new EvalObservation("set", "case", DocumentationVariant.WITH_DOCS,
            2, CaseStatus.PASSED, 1.0, "ok", usage, 25L, null,
            Map.of("calls", 1, "verdict", "match"));
        var parsed = ObservationJson.fromJson(ObservationJson.toJson(observation));
        assertThat(parsed).isEqualTo(observation);
    }

    @Test
    void roundTripsUnavailableUsageAndMissingScore() {
        var observation = new EvalObservation("set", "case",
            DocumentationVariant.WITHOUT_DOCS, 1, CaseStatus.ERRORED, null, "boom",
            null, 5L, null, Map.of());
        var parsed = ObservationJson.fromJson(ObservationJson.toJson(observation));
        assertThat(parsed.score()).isNull();
        assertThat(parsed.usage()).isNull();
        assertThat(ObservationJson.toJson(parsed)).contains("\"usage\":\"unavailable\"");
    }

    @Test
    void persistCopiesSessionArtifactAndAppendsObservation() throws IOException {
        var session = artifactDir.resolve("source-session.jsonl");
        Files.writeString(session, "{\"type\":\"session\"}\n", StandardCharsets.UTF_8);
        var observation = new EvalObservation("set", "case",
            DocumentationVariant.WITH_DOCS, 1, CaseStatus.PASSED, 1.0, "",
            null, 10L, null, Map.of());
        var stored = EvalReporter.write(observation, session.toString(), artifactDir);
        assertThat(stored.sessionArtifact()).isNotNull();
        var artifact = Path.of(stored.sessionArtifact());
        assertThat(artifact).exists();
        assertThat(artifact.getParent().getParent()).isEqualTo(
            artifactDir.resolve("with_docs").resolve("sessions"));
        var lines = Files.readAllLines(
            artifactDir.resolve("observations.jsonl"), StandardCharsets.UTF_8);
        assertThat(lines).hasSize(1);
        assertThat(ObservationJson.fromJson(lines.get(0)).sessionArtifact())
            .isEqualTo(stored.sessionArtifact());
    }

    @Test
    void persistWithoutSessionWritesNoArtifact() throws IOException {
        var observation = new EvalObservation("set", "case",
            DocumentationVariant.WITHOUT_DOCS, 1, CaseStatus.SKIPPED, null, "skipped",
            null, 1L, null, Map.of());
        var stored = EvalReporter.write(observation, null, artifactDir);
        assertThat(stored.sessionArtifact()).isNull();
        assertThat(artifactDir.resolve("without_docs")).doesNotExist();
        var observations = ObservationJson.read(artifactDir.resolve("observations.jsonl"));
        assertThat(observations).containsExactly(stored);
    }
}
