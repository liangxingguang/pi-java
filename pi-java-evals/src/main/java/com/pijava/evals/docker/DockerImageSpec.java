package com.pijava.evals.docker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import com.pijava.evals.plan.DocumentationVariant;

/**
 * 批 32（docs/44 §7.1）：Docker 镜像名与 target —— pi {@code docker.ts} 的两 target
 * 对位。
 *
 * <p>镜像前缀是 {@code pi-java-evals:<repo digest[0..12]>}，target 为
 * {@code without-docs}／{@code with-docs}；完整镜像名＝前缀＋{@code -<target>}。</p>
 */
public record DockerImageSpec(String imagePrefix, String target) {

    /** Target that removes the Pi documentation. */
    public static final String WITHOUT_DOCS_TARGET = "without-docs";

    /** Target that keeps the Pi documentation. */
    public static final String WITH_DOCS_TARGET = "with-docs";

    /** Normalizes the prefix. */
    public DockerImageSpec {
        Objects.requireNonNull(imagePrefix, "imagePrefix");
        Objects.requireNonNull(target, "target");
        if (!WITHOUT_DOCS_TARGET.equals(target) && !WITH_DOCS_TARGET.equals(target)) {
            throw new IllegalArgumentException("Unknown docker target: " + target);
        }
    }

    /**
     * Both targets in build order.
     *
     * @param imagePrefix image prefix
     * @return without-docs then with-docs
     */
    public static List<DockerImageSpec> both(String imagePrefix) {
        return List.of(forVariant(imagePrefix, DocumentationVariant.WITHOUT_DOCS),
            forVariant(imagePrefix, DocumentationVariant.WITH_DOCS));
    }

    /**
     * Target matching a documentation variant.
     *
     * @param imagePrefix image prefix
     * @param variant     docs variant
     * @return image spec
     */
    public static DockerImageSpec forVariant(String imagePrefix, DocumentationVariant variant) {
        return new DockerImageSpec(imagePrefix,
            variant == DocumentationVariant.WITHOUT_DOCS
                ? WITHOUT_DOCS_TARGET : WITH_DOCS_TARGET);
    }

    /** @return full image tag */
    public String image() {
        return imagePrefix + "-" + target;
    }

    /** @return {@code docker build --build-arg} values */
    public List<String> buildArgs() {
        return List.of("PI_EVAL_TARGET=" + target);
    }

    /**
     * Compute the repository content digest prefix (12 hex chars).
     *
     * @param repositoryRoot repository root
     * @return digest prefix
     * @throws IOException if the tree cannot be read
     */
    public static String repositoryDigest(Path repositoryRoot) throws IOException {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        var digest = newDigest();
        try (var paths = Files.walk(repositoryRoot)) {
            var files = paths.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(path ->
                    repositoryRoot.relativize(path).toString()))
                .toList();
            for (var file : files) {
                var relative = repositoryRoot.relativize(file).toString();
                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
    }

    /**
     * @param digestPrefix digest prefix from {@link #repositoryDigest(Path)}
     * @return image prefix
     */
    public static String imagePrefix(String digestPrefix) {
        return "pi-java-evals:" + digestPrefix;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
