package com.pijava.coding.agent.subcommand;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.pijava.coding.agent.extension.ExtensionPackageManager;
import com.pijava.coding.agent.extension.TestExtensionJar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-16: PackageCommand — install/list/remove/update 分发的退出码与输出。
 */
class PackageCommandTest {

    @TempDir
    Path tmp;

    @Test
    void listPrintsInstalledExtensions() throws Exception {
        var jar = TestExtensionJar.build(tmp.resolve("src/test-ext.jar"));
        var manager = new ExtensionPackageManager(tmp.resolve("ext"));
        manager.install(jar);

        var out = capture(() -> PackageCommand.run("list", new String[] {}, manager));

        assertThat(out).contains("Installed extensions")
            .contains("test-ext 1.0.0")
            .contains("test extension");
    }

    @Test
    void listPrintsEmptyHint() {
        var manager = new ExtensionPackageManager(tmp.resolve("ext"));
        var out = capture(() -> PackageCommand.run("list", new String[] {}, manager));
        assertThat(out).contains("(none");
    }

    @Test
    void installPrintsInfoAndCopiesJar() throws Exception {
        var jar = TestExtensionJar.build(tmp.resolve("src/test-ext.jar"));
        var manager = new ExtensionPackageManager(tmp.resolve("ext"));

        var out = capture(() -> PackageCommand.run(
            "install", new String[] {jar.toString()}, manager));

        assertThat(out).contains("Installed test-ext 1.0.0");
        assertThat(manager.list()).hasSize(1);
    }

    @Test
    void installMissingSourceReturnsOne() {
        var manager = new ExtensionPackageManager(tmp.resolve("ext"));
        var exit = PackageCommand.run("install",
            new String[] {tmp.resolve("missing.jar").toString()}, manager);
        assertThat(exit).isOne();
    }

    @Test
    void removeUnknownReturnsOne() {
        var manager = new ExtensionPackageManager(tmp.resolve("ext"));
        var exit = PackageCommand.run("remove", new String[] {"unknown"}, manager);
        assertThat(exit).isOne();
    }

    @Test
    void removeInstalledReturnsZero() throws Exception {
        var jar = TestExtensionJar.build(tmp.resolve("src/test-ext.jar"));
        var manager = new ExtensionPackageManager(tmp.resolve("ext"));
        manager.install(jar);

        var exit = PackageCommand.run("remove", new String[] {"test-ext"}, manager);

        assertThat(exit).isZero();
        assertThat(manager.list()).isEmpty();
    }

    @Test
    void updateSelfPrintsReleaseNote() {
        var manager = new ExtensionPackageManager(tmp.resolve("ext"));
        var out = capture(() -> PackageCommand.run("update", new String[] {"self"}, manager));
        assertThat(out).contains("release pipeline");
    }

    private static String capture(Runnable action) {
        var buffer = new ByteArrayOutputStream();
        var original = System.out;
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
