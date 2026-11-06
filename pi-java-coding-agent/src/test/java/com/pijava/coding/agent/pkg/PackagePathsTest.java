package com.pijava.coding.agent.pkg;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W4：包 CHANGELOG 路径解析——pi {@code resolvePackageChangelogPath}
 * （package-manager.ts:697-713）与 {@code hasManifestResourceFields}（:686-688）。
 */
class PackagePathsTest {

    @TempDir
    Path tempRoot;

    @Test
    void defaultsToChangelogMdWhenManifestHasNoOverride() throws Exception {
        var root = Files.createDirectories(tempRoot.resolve("pkg"));
        Files.writeString(root.resolve("CHANGELOG.md"), "## [1.0.0]\n");

        var resolved = PackagePaths.resolvePackageChangelogPath(root.toString(), null);

        assertThat(resolved).isEqualTo(root.resolve("CHANGELOG.md").toString());
    }

    @Test
    void manifestOverrideWins() throws Exception {
        var root = Files.createDirectories(tempRoot.resolve("pkg2"));
        Files.writeString(root.resolve("CHANGES.md"), "## [1.0.0]\n");
        var manifest = new PiManifest(null, null, null, null, "CHANGES.md");

        assertThat(PackagePaths.resolvePackageChangelogPath(root.toString(), manifest))
            .isEqualTo(root.resolve("CHANGES.md").toString());
    }

    @Test
    void missingFileIsNull() throws Exception {
        var root = Files.createDirectories(tempRoot.resolve("pkg3"));

        assertThat(PackagePaths.resolvePackageChangelogPath(root.toString(), null)).isNull();
    }

    @Test
    void directoryIsNotAccepted() throws Exception {
        var root = Files.createDirectories(tempRoot.resolve("pkg4"));
        Files.createDirectories(root.resolve("CHANGELOG.md"));

        assertThat(PackagePaths.resolvePackageChangelogPath(root.toString(), null)).isNull();
    }

    @Test
    void escapingPathIsRejected() throws Exception {
        var root = Files.createDirectories(tempRoot.resolve("pkg5").resolve("inner"));
        var outside = tempRoot.resolve("outside.md");
        Files.writeString(outside, "## [1.0.0]\n");
        var manifest = new PiManifest(null, null, null, null, "../outside.md");

        assertThat(PackagePaths.resolvePackageChangelogPath(root.toString(), manifest)).isNull();
    }

    @Test
    void emptyManifestOverrideIsNull() throws Exception {
        var root = Files.createDirectories(tempRoot.resolve("pkg6"));
        Files.writeString(root.resolve("CHANGELOG.md"), "## [1.0.0]\n");
        var manifest = new PiManifest(null, null, null, null, "");

        assertThat(PackagePaths.resolvePackageChangelogPath(root.toString(), manifest)).isNull();
    }

    @Test
    void manifestReadsChangelogPathKey() throws Exception {
        var root = Files.createDirectories(tempRoot.resolve("pkg7"));
        Files.writeString(root.resolve("package.json"),
            "{\"pi\":{\"changelogPath\":\"docs/CHANGELOG.md\"}}");

        var manifest = PiManifest.read(root.resolve("package.json"));

        assertThat(manifest).isNotNull();
        assertThat(manifest.changelogPath()).isEqualTo("docs/CHANGELOG.md");
        // 只有 changelogPath ⇒ 无资源字段（pi :686-688）⇒ 仍走目录自动发现。
        assertThat(PackageResourceCollector.hasManifestResourceFields(manifest)).isFalse();
    }

    @Test
    void manifestWithResourceFieldsIsRecognised() throws Exception {
        var root = Files.createDirectories(tempRoot.resolve("pkg8"));
        Files.writeString(root.resolve("package.json"),
            "{\"pi\":{\"skills\":[\"skills\"],\"changelogPath\":\"CHANGELOG.md\"}}");

        var manifest = PiManifest.read(root.resolve("package.json"));

        assertThat(PackageResourceCollector.hasManifestResourceFields(manifest)).isTrue();
    }
}
