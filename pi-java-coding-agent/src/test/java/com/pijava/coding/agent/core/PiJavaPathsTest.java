package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 批 30（T6，B95）：{@link PiJavaPaths} 三臂——env 覆盖／向上解析／找不到；
 * 以及 examples 目录的条件臂（pi config.ts:444-457 三 getter 对位）。
 */
class PiJavaPathsTest {

    @TempDir
    Path temp;

    /** 造一个假发行根（README.md＋docs/ 组合标记只有仓根满足）。 */
    private Path fakeRoot(String name, boolean withExamples) throws Exception {
        var base = Files.createDirectories(temp.resolve(name));
        Files.createDirectories(base.resolve("sub/module"));
        Files.writeString(base.resolve("README.md"), "# x");
        Files.createDirectories(base.resolve("docs"));
        if (withExamples) {
            Files.createDirectories(base.resolve("pi-java-examples"));
        }
        return base;
    }

    @Test
    void envOverrideWins() throws Exception {
        var root = fakeRoot("env", false);
        assertThat(PiJavaPaths.packageDir(root.toString(), null))
            .isEqualTo(root.toAbsolutePath().normalize());
        // env 在场时 start 不参与（pi getPackageDir 的早退臂）。
        assertThat(PiJavaPaths.packageDir(root.toString(), temp)).isEqualTo(root);
    }

    @Test
    void walksUpToTheMarkedRoot() throws Exception {
        var root = fakeRoot("walk", false);
        var deep = root.resolve("sub/module/target/classes");
        Files.createDirectories(deep);
        assertThat(PiJavaPaths.packageDir(null, deep)).isEqualTo(root);
    }

    @Test
    void unresolvableRootYieldsNull() {
        // 无标记的树上访到文件系统根 ⇒ null（⇒ docs 段不产出）。
        assertThat(PiJavaPaths.packageDir(null, temp.resolve("nowhere"))).isNull();
        assertThat(PiJavaPaths.packageDir(null, null)).isNull();
    }

    @Test
    void examplesOnlyWhenDirectoryExists() throws Exception {
        var withExamples = PiJavaPaths.documentation(fakeRoot("with", true));
        assertThat(withExamples.examples()).isNotNull();

        var without = PiJavaPaths.documentation(fakeRoot("without", false));
        assertThat(without.examples()).isNull();

        // 路径进系统提示 ⇒ 正斜杠归一（pi normalizePath 同形）。
        assertThat(withExamples.readme()).doesNotContain("\\").endsWith("/README.md");
        assertThat(withExamples.docs()).endsWith("/docs");
        assertThat(withExamples.examples()).endsWith("/pi-java-examples");
    }

    /**
     * 仓内实跑（SessionWiring 供值路径的真实解析）：本测试 JVM 的代码源在
     * {@code pi-java-coding-agent/target/test-classes} ⇒ 向上解析必中仓根；
     * 仓根的 {@code pi-java-examples/} 就位 ⇒ examples 行首次产出（批 28 前提）。
     */
    @Test
    void inRepoRunResolvesTheRealRootWithExamples() {
        var documentation = PiJavaPaths.documentation();
        assertThat(documentation).isNotNull();
        assertThat(documentation.readme()).endsWith("/pi-java/README.md");
        assertThat(documentation.docs()).endsWith("/pi-java/docs");
        assertThat(documentation.examples()).endsWith("/pi-java/pi-java-examples");
    }
}
