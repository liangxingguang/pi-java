package com.pijava.evals.docker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.EvalCaseDescriptor;

/**
 * 批 32（docs/44 §7.2）：{@code docker build}/{@code docker run} 的逐项参数 ——
 * pi {@code docker.ts:dockerArgs} 对位。
 *
 * <p>run 侧强制 auth 文件存在（{@link DockerContext#requireAuthFile()}）；容器内
 * 挂载点为 {@value #AUTH_MOUNT}。</p>
 */
public final class DockerCommandBuilder {

    /** Container-side auth mount path. */
    public static final String AUTH_MOUNT = "/run/pi-eval/auth.json";

    /** Artifact mount path. */
    public static final String ARTIFACT_MOUNT = "/artifacts";

    /** Temp mount size. */
    public static final String TMPFS = "/tmp:rw,size=256m";

    /** Docs-eval package dir inside the without-docs image. */
    public static final String WITHOUT_DOCS_PACKAGE_DIR = "/opt/pi-eval/no-docs";

    /** Docs-eval package dir inside the with-docs image. */
    public static final String WITH_DOCS_PACKAGE_DIR = "/src";

    /**
     * Build {@code docker build} arguments.
     *
     * @param context docker context
     * @param image   image spec
     * @return command argv
     */
    public List<String> buildCommand(DockerContext context, DockerImageSpec image) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(image, "image");
        var command = new ArrayList<String>();
        command.add("docker");
        command.add("build");
        command.add("--target");
        command.add(image.target());
        for (var arg : image.buildArgs()) {
            command.add("--build-arg");
            command.add(arg);
        }
        command.add("-f");
        command.add(context.dockerfile().toString());
        command.add("-t");
        command.add(image.image());
        command.add(context.repositoryRoot().toString());
        return command;
    }

    /**
     * Build the {@code --list-cases} discovery command (no auth needed).
     *
     * @param image image spec
     * @return command argv
     */
    public List<String> discoverCommand(DockerImageSpec image) {
        Objects.requireNonNull(image, "image");
        var command = new ArrayList<String>();
        command.add("docker");
        command.add("run");
        commonRunFlags(command);
        command.add(image.image());
        command.add("--list-cases");
        return command;
    }

    /**
     * Build a single-arm {@code docker run} command.
     *
     * @param context     docker context (auth file must exist)
     * @param image       image spec
     * @param artifactDir host artifact directory
     * @param provider    provider name
     * @param model       model id
     * @param evalCase    eval case
     * @param run         1-based run number
     * @return command argv
     * @throws IllegalStateException when the auth file is missing
     */
    public List<String> runCommand(DockerContext context, DockerImageSpec image,
                                   Path artifactDir, String provider, String model,
                                   EvalCaseDescriptor evalCase, int run) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(artifactDir, "artifactDir");
        Objects.requireNonNull(evalCase, "evalCase");
        context.requireAuthFile();
        var command = new ArrayList<String>();
        command.add("docker");
        command.add("run");
        commonRunFlags(command);
        command.add("-v");
        command.add(artifactDir.toAbsolutePath() + ":" + ARTIFACT_MOUNT + ":rw");
        command.add("-v");
        command.add(context.authFile().toAbsolutePath() + ":" + AUTH_MOUNT + ":ro");
        addEnv(command, "PI_EVAL_CONTAINER", "1");
        addEnv(command, "PI_EVAL_VARIANT", variantOf(image).wireName());
        addEnv(command, "PI_PROVIDER", provider);
        addEnv(command, "PI_MODEL", model);
        addEnv(command, "PI_EVAL_RUN", Integer.toString(run));
        addEnv(command, "PI_JAVA_PACKAGE_DIR", packageDirOf(image));
        command.add(image.image());
        command.add("--suite");
        command.add(evalCase.evalSet());
        command.add("--case");
        command.add(evalCase.caseName());
        command.add("--testNamePattern");
        command.add("^" + evalCase.caseName() + "$");
        return command;
    }

    private static void commonRunFlags(List<String> command) {
        command.add("--rm");
        command.add("--read-only");
        command.add("--network=bridge");
        command.add("--user");
        command.add("65532:65532");
        command.add("--tmpfs");
        command.add(TMPFS);
    }

    private static void addEnv(List<String> command, String name, String value) {
        command.add("-e");
        command.add(name + "=" + value);
    }

    private static DocumentationVariant variantOf(DockerImageSpec image) {
        return DockerImageSpec.WITHOUT_DOCS_TARGET.equals(image.target())
            ? DocumentationVariant.WITHOUT_DOCS : DocumentationVariant.WITH_DOCS;
    }

    private static String packageDirOf(DockerImageSpec image) {
        return DockerImageSpec.WITHOUT_DOCS_TARGET.equals(image.target())
            ? WITHOUT_DOCS_PACKAGE_DIR : WITH_DOCS_PACKAGE_DIR;
    }
}
