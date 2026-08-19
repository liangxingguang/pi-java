package com.pijava.coding.agent.extension;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-16: ExtensionPackageManager — install/remove/list/update 与校验。
 */
class ExtensionPackageManagerTest {

    @TempDir
    Path tmp;

    @Test
    void installCopiesJarAndListsIt() throws Exception {
        var jar = TestExtensionJar.build(tmp.resolve("src/test-ext.jar"));
        var manager = new ExtensionPackageManager(tmp.resolve("ext"));

        var installed = manager.install(jar);

        assertThat(installed.name()).isEqualTo("test-ext");
        assertThat(installed.version()).isEqualTo("1.0.0");
        assertThat(installed.commands()).contains("sample");
        assertThat(Files.isRegularFile(installed.path())).isTrue();
        assertThat(manager.list()).hasSize(1);
    }

    @Test
    void installRejectsPlainJar() throws Exception {
        var jar = TestExtensionJar.buildPlain(tmp.resolve("plain.jar"));
        var manager = new ExtensionPackageManager(tmp.resolve("ext"));

        assertThatThrownBy(() -> manager.install(jar))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not an extension JAR");
    }

    @Test
    void installFromFileUri() throws Exception {
        var jar = TestExtensionJar.build(tmp.resolve("src/test-ext.jar"));
        var manager = new ExtensionPackageManager(tmp.resolve("ext"));

        var installed = manager.install(jar.toUri());

        assertThat(installed.name()).isEqualTo("test-ext");
        assertThat(manager.list()).hasSize(1);
    }

    @Test
    void removeByManifestNameAndByFileName() throws Exception {
        var jar = TestExtensionJar.build(tmp.resolve("src/test-ext.jar"));
        var manager = new ExtensionPackageManager(tmp.resolve("ext"));
        manager.install(jar);

        assertThat(manager.remove("test-ext")).isTrue();
        assertThat(manager.list()).isEmpty();

        manager.install(jar);
        assertThat(manager.remove("test-ext.jar")).isTrue();
        assertThat(manager.remove("test-ext")).isFalse();
    }

    @Test
    void installedJarsEmptyWhenDirMissing() {
        var manager = new ExtensionPackageManager(tmp.resolve("ext"));
        assertThat(manager.installedJars()).isEmpty();
        assertThat(manager.list()).isEmpty();
        assertThat(manager.remove("anything")).isFalse();
    }

    @Test
    void updateReplacesExistingJar() throws Exception {
        var source = tmp.resolve("src/test-ext.jar");
        var manager = new ExtensionPackageManager(tmp.resolve("ext"));
        manager.install(TestExtensionJar.build(source));
        assertThat(manager.list()).hasSize(1);

        // 同一来源文件名重新安装 → 覆盖而非新增。
        var updated = manager.update(TestExtensionJar.build(source).toString());

        assertThat(updated).isPresent();
        assertThat(manager.list()).hasSize(1);
        assertThat(updated.get().path()).isEqualTo(manager.dir().resolve("test-ext.jar"));
    }

    @Test
    void updateWithUnknownSourceReturnsEmpty() {
        var manager = new ExtensionPackageManager(tmp.resolve("ext"));
        assertThat(manager.update(tmp.resolve("missing.jar").toString())).isEmpty();
    }
}
