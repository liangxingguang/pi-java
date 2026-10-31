package com.pijava.evals.docker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.EvalCaseDescriptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 32（docs/44 §7.2）：{@link DockerCommandBuilder} 的 argv 逐项、auth 缺失与
 * 两 target 的参数差异。
 */
class DockerCommandBuilderTest {

    private static final String PREFIX = "pi-java-evals:abc123def456";

    @TempDir
    Path temp;

    @Test
    void buildsBothTargetsWithBuildArgsAndDockerfile() throws IOException {
        var context = context();
        var builder = new DockerCommandBuilder();
        var expected = List.of("docker", "build", "--target", "without-docs",
            "--build-arg", "PI_EVAL_TARGET=without-docs", "-f",
            context.dockerfile().toString(), "-t", PREFIX + "-without-docs",
            context.repositoryRoot().toString());
        assertThat(builder.buildCommand(context,
            new DockerImageSpec(PREFIX, DockerImageSpec.WITHOUT_DOCS_TARGET)))
            .containsExactlyElementsOf(expected);
        assertThat(DockerImageSpec.both(PREFIX)).extracting(DockerImageSpec::target)
            .containsExactly(DockerImageSpec.WITHOUT_DOCS_TARGET,
                DockerImageSpec.WITH_DOCS_TARGET);
        assertThat(DockerImageSpec.both(PREFIX)).extracting(DockerImageSpec::image)
            .containsExactly(PREFIX + "-without-docs", PREFIX + "-with-docs");
    }

    @Test
    void runCommandCarriesIsolationMountsAndEnv() throws IOException {
        var context = context();
        Files.writeString(context.authFile(), "{}");
        var artifacts = temp.resolve("artifacts");
        var builder = new DockerCommandBuilder();
        var command = builder.runCommand(context,
            new DockerImageSpec(PREFIX, DockerImageSpec.WITH_DOCS_TARGET), artifacts,
            "acme", "fixture-chat",
            new EvalCaseDescriptor("contents", "smoke"), 2);
        assertThat(command).startsWith("docker", "run", "--rm", "--read-only",
            "--network=bridge", "--user", "65532:65532", "--tmpfs", "/tmp:rw,size=256m")
            .contains("-v", artifacts.toAbsolutePath() + ":/artifacts:rw")
            .contains("-v", context.authFile().toAbsolutePath() + ":/run/pi-eval/auth.json:ro")
            .contains("-e", "PI_EVAL_CONTAINER=1", "PI_EVAL_VARIANT=with_docs",
                "PI_PROVIDER=acme", "PI_MODEL=fixture-chat", "PI_EVAL_RUN=2",
                "PI_JAVA_PACKAGE_DIR=/src")
            .endsWith(PREFIX + "-with-docs", "--suite", "contents", "--case", "smoke",
                "--testNamePattern", "^smoke$");
    }

    @Test
    void withoutDocsImageUsesStubPackageDirAndVariant() throws IOException {
        var context = context();
        Files.writeString(context.authFile(), "{}");
        var command = new DockerCommandBuilder().runCommand(context,
            DockerImageSpec.forVariant(PREFIX, DocumentationVariant.WITHOUT_DOCS),
            temp.resolve("artifacts"), "acme", "fixture-chat",
            new EvalCaseDescriptor("contents", "smoke"), 1);
        assertThat(command).contains("PI_EVAL_VARIANT=without_docs",
            "PI_JAVA_PACKAGE_DIR=" + DockerCommandBuilder.WITHOUT_DOCS_PACKAGE_DIR);
    }

    @Test
    void runCommandRequiresAuthFile() throws IOException {
        var context = context();
        assertThatThrownBy(() -> new DockerCommandBuilder().runCommand(context,
                new DockerImageSpec(PREFIX, DockerImageSpec.WITH_DOCS_TARGET),
                temp.resolve("artifacts"), "acme", "fixture-chat",
                new EvalCaseDescriptor("contents", "smoke"), 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Eval auth file not found");
    }

    @Test
    void discoveryDoesNotRequireAuthFile() throws IOException {
        var context = context();
        var command = new DockerCommandBuilder().discoverCommand(
            new DockerImageSpec(PREFIX, DockerImageSpec.WITHOUT_DOCS_TARGET));
        assertThat(command).containsExactly("docker", "run", "--rm", "--read-only",
            "--network=bridge", "--user", "65532:65532", "--tmpfs", "/tmp:rw,size=256m",
            PREFIX + "-without-docs", "--list-cases");
        assertThat(command).doesNotContain("/run/pi-eval/auth.json");
    }

    @Test
    void rejectsUnknownTarget() {
        assertThatThrownBy(() -> new DockerImageSpec(PREFIX, "docs"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown docker target");
    }

    private DockerContext context() throws IOException {
        var repo = Files.createDirectories(temp.resolve("repo"));
        Files.createDirectories(repo.resolve("pi-java-evals").resolve("docker"));
        return new DockerContext(repo, temp.resolve("auth.json"), PREFIX);
    }
}
