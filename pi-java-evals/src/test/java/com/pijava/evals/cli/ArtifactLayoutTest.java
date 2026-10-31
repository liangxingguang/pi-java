package com.pijava.evals.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import com.pijava.evals.plan.DocumentationVariant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** 批 32（docs/44 §5.3）：artifact 目录命名与标准文件路径。 */
class ArtifactLayoutTest {

    @TempDir
    Path baseDir;

    @Test
    void namesDirectoryWithIsoTimestampAndUuid() throws Exception {
        var runId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        var layout = ArtifactLayout.create(baseDir,
            Instant.parse("2026-10-31T09:57:39Z"), runId);
        assertThat(layout.root()).exists();
        assertThat(layout.root().getFileName().toString())
            .isEqualTo("2026-10-31T09-57-39Z_" + runId);
        assertThat(layout.root().getParent()).isEqualTo(baseDir.resolve(".eval"));
        assertThat(layout.root().getFileName().toString()).doesNotContain(":");
    }

    @Test
    void exposesStandardArtifactPaths() throws Exception {
        var layout = ArtifactLayout.create(baseDir);
        assertThat(layout.observations().getFileName().toString())
            .isEqualTo("observations.jsonl");
        assertThat(layout.reportJson().getFileName().toString()).isEqualTo("report.json");
        assertThat(layout.reportText().getFileName().toString()).isEqualTo("report.txt");
        assertThat(layout.protocol().getFileName().toString()).isEqualTo("protocol.json");
        assertThat(layout.expectedRuns().getFileName().toString())
            .isEqualTo("expected-runs.json");
        assertThat(layout.taskDir("set/case#with_docs#1"))
            .isEqualTo(layout.root().resolve("tasks").resolve("set/case#with_docs#1"));
        assertThat(layout.sessionDir(DocumentationVariant.WITHOUT_DOCS, "abc"))
            .isEqualTo(layout.root().resolve("without_docs").resolve("sessions").resolve("abc"));
    }

    @Test
    void repeatedCreationProducesDistinctDirectories() throws Exception {
        var first = ArtifactLayout.create(baseDir);
        var second = ArtifactLayout.create(baseDir);
        assertThat(first.root()).isNotEqualTo(second.root());
        assertThat(Files.isDirectory(first.root())).isTrue();
        assertThat(Files.isDirectory(second.root())).isTrue();
    }
}
