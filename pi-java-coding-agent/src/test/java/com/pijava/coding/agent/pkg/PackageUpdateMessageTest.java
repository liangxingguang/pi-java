package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.core.InMemorySettingsStorage;
import com.pijava.coding.agent.core.PackageSource;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;

/**
 * L5 W5 §6.2（pi package-manager.ts:1211-1233）：{@code pi update} 宣告了更新但
 * 安装后版本没变 ⇒ 发 progress 型消息（文案逐字）；版本确实变了 ⇒ 不发。
 */
class PackageUpdateMessageTest {

    @TempDir
    Path cwd;

    @TempDir
    Path agentDir;

    @TempDir
    Path homeDir;

    private InMemorySettingsStorage storage;
    private SettingsManager settings;
    private StaleNpm npm;
    private PackageManager manager;
    private List<ProgressEvent> events;

    /** announced 目标 2.0.0，但 install 后磁盘仍是 1.0.0（registry 元数据过期的情形）。 */
    private static final class StaleNpm implements NpmResolver {
        volatile boolean stale = true;
        volatile String latest = "2.0.0";

        @Override
        public List<String> versions(String name) {
            return List.of("1.0.0", "2.0.0");
        }

        @Override
        public String latestSatisfying(String name, String range) {
            return latest;
        }

        @Override
        public Path install(NpmSource source, Path installRoot) throws IOException {
            var version = stale ? "1.0.0" : latest;
            var target = installRoot.resolve("node_modules").resolve(source.name());
            Files.createDirectories(target);
            Files.writeString(target.resolve("package.json"),
                "{\"name\":\"" + source.name() + "\",\"version\":\"" + version + "\"}");
            return target;
        }

        @Override
        public void uninstall(String name, Path installRoot) {
            // 本套件不消费。
        }
    }

    @BeforeEach
    void setUp() {
        storage = new InMemorySettingsStorage();
        settings = SettingsManager.withStorage(storage);
        npm = new StaleNpm();
        events = new ArrayList<>();
        var resolver = new SourceResolver(cwd, agentDir, settings::isProjectTrusted);
        var layout = new InstallLayout(resolver);
        manager = new PackageManager(resolver, settings, List.of(), npm, layout,
            new GitPackageStore(new GitRunner(), layout, null), homeDir);
        manager.setProgressCallback(events::add);
    }

    private void seedUser(String... sources) {
        var s = new Settings();
        s.packages = java.util.Arrays.stream(sources).map(PackageSource::of).toList();
        storage.writeGlobal(s);
        settings.reload();
    }

    private void markInstalled(String name, String version) throws IOException {
        var dir = agentDir.resolve("npm").resolve("node_modules").resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"),
            "{\"name\":\"" + name + "\",\"version\":\"" + version + "\"}");
    }

    @Test
    void unchangedVersionEmitsProgressMessage() throws IOException {
        seedUser("npm:foo");
        markInstalled("foo", "1.0.0");

        manager.update(null);

        var progress = events.stream()
            .filter(e -> e.type() == ProgressEvent.Type.PROGRESS).toList();
        assertThat(progress).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo(ProgressEvent.Action.UPDATE);
            assertThat(event.source()).isEqualTo("npm:foo");
            assertThat(event.message()).isEqualTo("npm:foo is still 1.0.0 (registry reports 2.0.0); "
                + "npm registry metadata may be stale, try again in a few minutes");
        });
    }

    @Test
    void changedVersionEmitsNoProgressMessage() throws IOException {
        seedUser("npm:foo");
        markInstalled("foo", "1.0.0");
        npm.stale = false;

        manager.update(null);

        assertThat(events).noneMatch(e -> e.type() == ProgressEvent.Type.PROGRESS);
    }

    @Test
    void failedLookupHasNoTargetVersionSoNoMessage() throws IOException {
        seedUser("npm:foo");
        markInstalled("foo", "1.0.0");
        // 查询失败 ⇒ shouldUpdate 保守 true 但无 targetVersion ⇒ 不报「版本没变」。
        npm.latest = "1.0.0";
        npm.stale = true;

        manager.update(null);

        assertThat(events).noneMatch(e -> e.type() == ProgressEvent.Type.PROGRESS);
    }
}
