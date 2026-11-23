package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.core.InMemorySettingsStorage;
import com.pijava.coding.agent.core.PackageSource;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W8（pi package-manager.ts:1307-1330）：update 逐包发 {@code result} 事件——
 * 文案 {@code source from -> to}，npm 版本装后现读，配置序。
 */
class PackageUpdateResultTest {

    @TempDir
    Path cwd;

    @TempDir
    Path agentDir;

    @TempDir
    Path homeDir;

    private InMemorySettingsStorage storage;
    private SettingsManager settings;
    private InstallLayout layout;
    private StubNpm npm;
    private final List<ProgressEvent> events = new ArrayList<>();

    /** 装 2.0.0 的 npm stub（registry latest 恒 2.0.0）。 */
    private static final class StubNpm implements NpmResolver {
        @Override
        public List<String> versions(String name) {
            return List.of("1.0.0", "2.0.0");
        }

        @Override
        public String latestSatisfying(String name, String range) {
            return "2.0.0";
        }

        @Override
        public Path install(NpmSource source, Path installRoot) throws IOException {
            var dir = installRoot.resolve("node_modules").resolve(source.name());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("package.json"),
                "{\"name\":\"" + source.name() + "\",\"version\":\"2.0.0\"}");
            return dir;
        }

        @Override
        public void uninstall(String name, Path installRoot) {
            // no-op
        }
    }

    @BeforeEach
    void setUp() {
        storage = new InMemorySettingsStorage();
        settings = SettingsManager.withStorage(storage);
        npm = new StubNpm();
        layout = new InstallLayout(new SourceResolver(cwd, agentDir, settings::isProjectTrusted));
    }

    private void seedUser(String... sources) {
        var s = new Settings();
        s.packages = java.util.Arrays.stream(sources).map(PackageSource::of).toList();
        storage.writeGlobal(s);
        settings.reload();
    }

    private void presetInstalled(String name, String version) throws IOException {
        var dir = agentDir.resolve("npm").resolve("node_modules").resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"),
            "{\"name\":\"" + name + "\",\"version\":\"" + version + "\"}");
    }

    @Test
    void npmUpdatesEmitResultEventsInConfiguredOrder() throws Exception {
        seedUser("npm:foo", "npm:bar");
        presetInstalled("foo", "1.0.0");
        presetInstalled("bar", "1.0.0");

        var updater = new PackageUpdater(
            new SourceResolver(cwd, agentDir, settings::isProjectTrusted), settings, layout, npm,
            new GitPackageStore(new GitRunner(), layout, null),
            new PackageUpdater.ProgressRunner() {
                @Override
                public void run(ProgressEvent.Action action, String source, String message,
                        PackageManager.IoRunnable op) throws IOException {
                    op.run();
                }

                @Override
                public void emit(ProgressEvent event) {
                    events.add(event);
                }
            });
        updater.update(null);

        var results = events.stream()
            .filter(e -> e.type() == ProgressEvent.Type.RESULT)
            .map(ProgressEvent::message)
            .toList();
        assertThat(results).containsExactly(
            "npm:foo 1.0.0 -> 2.0.0",
            "npm:bar 1.0.0 -> 2.0.0");
    }

    @Test
    void unchangedNpmPackageEmitsNoResult() throws Exception {
        seedUser("npm:foo");
        presetInstalled("foo", "2.0.0");

        var updater = new PackageUpdater(
            new SourceResolver(cwd, agentDir, settings::isProjectTrusted), settings, layout,
            new StubNpmKeeping("2.0.0"),
            new GitPackageStore(new GitRunner(), layout, null),
            new PackageUpdater.ProgressRunner() {
                @Override
                public void run(ProgressEvent.Action action, String source, String message,
                        PackageManager.IoRunnable op) throws IOException {
                    op.run();
                }

                @Override
                public void emit(ProgressEvent event) {
                    events.add(event);
                }
            });
        updater.update(null);

        assertThat(events.stream().filter(e -> e.type() == ProgressEvent.Type.RESULT)).isEmpty();
    }

    /** 已装即最新 ⇒ shouldUpdate=false（不装、不发 result）。 */
    private static final class StubNpmKeeping implements NpmResolver {
        private final String latest;

        StubNpmKeeping(String latest) {
            this.latest = latest;
        }

        @Override public List<String> versions(String name) {
            return List.of(latest);
        }

        @Override public String latestSatisfying(String name, String range) {
            return latest;
        }

        @Override public Path install(NpmSource source, Path installRoot) {
            throw new UnsupportedOperationException();
        }

        @Override public void uninstall(String name, Path installRoot) {
            // no-op
        }
    }
}
