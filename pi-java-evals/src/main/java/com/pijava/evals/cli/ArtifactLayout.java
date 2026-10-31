package com.pijava.evals.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

import com.pijava.evals.plan.DocumentationVariant;

/**
 * 批 32（docs/44 §5.3/§6）：一次 eval 运行的 artifact 目录布局。
 *
 * <pre>
 * .eval/&lt;ISO-8601&gt;_&lt;uuid&gt;/
 * ├─ protocol.json
 * ├─ expected-runs.json
 * ├─ observations.jsonl
 * ├─ report.json / report.txt
 * ├─ tasks/&lt;arm-id&gt;/junit.xml
 * └─ &lt;variant&gt;/sessions/&lt;sha&gt;/session.jsonl
 * </pre>
 */
public final class ArtifactLayout {

    private static final DateTimeFormatter DIR_TIMESTAMP = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    private final Path root;

    private ArtifactLayout(Path root) {
        this.root = root;
    }

    /**
     * Create a fresh run directory under {@code baseDir/.eval}.
     *
     * @param baseDir repository (or artifact) base directory
     * @return layout pointing at the new directory
     * @throws IOException if directories cannot be created
     */
    public static ArtifactLayout create(Path baseDir) throws IOException {
        return create(baseDir, Instant.now(), UUID.randomUUID());
    }

    /**
     * Create a run directory with an explicit timestamp and id (tests).
     *
     * @param baseDir   repository (or artifact) base directory
     * @param timestamp run start
     * @param runId     unique run id
     * @return layout pointing at the new directory
     * @throws IOException if directories cannot be created
     */
    public static ArtifactLayout create(Path baseDir, Instant timestamp, UUID runId)
            throws IOException {
        Objects.requireNonNull(baseDir, "baseDir");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(runId, "runId");
        var name = DIR_TIMESTAMP.format(timestamp) + "_" + runId;
        var root = baseDir.resolve(".eval").resolve(name);
        Files.createDirectories(root);
        return new ArtifactLayout(root);
    }

    /**
     * Wrap an existing artifact directory.
     *
     * @param root artifact root
     * @return layout
     */
    public static ArtifactLayout of(Path root) {
        Objects.requireNonNull(root, "root");
        return new ArtifactLayout(root);
    }

    /** @return artifact root */
    public Path root() {
        return root;
    }

    /** @return {@code protocol.json} */
    public Path protocol() {
        return root.resolve("protocol.json");
    }

    /** @return {@code expected-runs.json} */
    public Path expectedRuns() {
        return root.resolve("expected-runs.json");
    }

    /** @return {@code observations.jsonl} */
    public Path observations() {
        return root.resolve("observations.jsonl");
    }

    /** @return {@code report.json} */
    public Path reportJson() {
        return root.resolve("report.json");
    }

    /** @return {@code report.txt} */
    public Path reportText() {
        return root.resolve("report.txt");
    }

    /**
     * Per-arm Docker task directory.
     *
     * @param armId arm identifier
     * @return {@code tasks/<arm-id>}
     */
    public Path taskDir(String armId) {
        return root.resolve("tasks").resolve(armId);
    }

    /**
     * Session artifact directory.
     *
     * @param variant docs variant
     * @param sha256  session content digest
     * @return {@code <variant>/sessions/<sha>}
     */
    public Path sessionDir(DocumentationVariant variant, String sha256) {
        return root.resolve(variant.wireName()).resolve("sessions").resolve(sha256);
    }
}
