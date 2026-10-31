package com.pijava.evals.docker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 批 32（docs/44 §7）：Docker 编排上下文 —— 仓库根、镜像前缀与 auth 文件。
 *
 * <p>auth 文件必须存在才会执行容器；缺文件直接报错（pi {@code requireEvalAuthFile}
 * 对位），不静默匿名。</p>
 *
 * @param repositoryRoot repository root (build context)
 * @param authFile       eval auth file mounted read-only into the container
 * @param imagePrefix    image prefix (tag without target)
 */
public record DockerContext(Path repositoryRoot, Path authFile, String imagePrefix) {

    /** Environment variable overriding the auth file path. */
    public static final String AUTH_FILE_ENV = "PI_EVAL_AUTH_FILE";

    /** Normalizes paths and requires the context fields. */
    public DockerContext {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(authFile, "authFile");
        Objects.requireNonNull(imagePrefix, "imagePrefix");
    }

    /**
     * Build a context, computing the image prefix from the repository digest.
     *
     * @param repositoryRoot repository root
     * @param authFile       auth file
     * @return context
     * @throws IOException if the repository content cannot be hashed
     */
    public static DockerContext of(Path repositoryRoot, Path authFile) throws IOException {
        return new DockerContext(repositoryRoot, authFile,
            DockerImageSpec.imagePrefix(DockerImageSpec.repositoryDigest(repositoryRoot)));
    }

    /**
     * Build a context with the default auth file ({@value #AUTH_FILE_ENV} or
     * {@code .eval/auth.json}).
     *
     * @param repositoryRoot repository root
     * @return context
     * @throws IOException if the repository content cannot be hashed
     */
    public static DockerContext of(Path repositoryRoot) throws IOException {
        return of(repositoryRoot, defaultAuthFile(repositoryRoot));
    }

    /**
     * Default auth file for a repository.
     *
     * @param repositoryRoot repository root
     * @return env override or {@code .eval/auth.json}
     */
    public static Path defaultAuthFile(Path repositoryRoot) {
        var env = System.getenv(AUTH_FILE_ENV);
        return env == null || env.isBlank()
            ? repositoryRoot.resolve(".eval").resolve("auth.json") : Path.of(env);
    }

    /** @return the dockerfile path used by builds */
    public Path dockerfile() {
        return repositoryRoot.resolve("pi-java-evals").resolve("docker").resolve("Dockerfile");
    }

    /**
     * Require the auth file before running a container.
     *
     * @throws IllegalStateException when the auth file is absent
     */
    public void requireAuthFile() {
        if (!Files.isRegularFile(authFile)) {
            throw new IllegalStateException("Eval auth file not found: " + authFile);
        }
    }
}
