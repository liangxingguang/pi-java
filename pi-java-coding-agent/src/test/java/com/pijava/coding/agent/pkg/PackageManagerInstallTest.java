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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pijava.coding.agent.core.InMemorySettingsStorage;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;
import com.pijava.coding.agent.pkg.ProgressEvent.Action;
import com.pijava.coding.agent.pkg.ProgressEvent.Type;

/**
 * 批 19 T1（docs/30 §3/§7）：{@code install}/{@code remove} 公开面——三型分派、
 * local 校验文案逐字、信任门、withProgress 发射序、npm 卸载补齐。
 */
class PackageManagerInstallTest {

    @TempDir
    Path cwd;

    @TempDir
    Path agentDir;

    @TempDir
    Path homeDir;

    private InMemorySettingsStorage storage;
    private SettingsManager settings;
    private RecordingNpmResolver npmResolver;
    private RecordingGitRunner git;
    private List<ProgressEvent> events;
    private PackageManager manager;

    private static final class RecordingNpmResolver implements NpmResolver {
        final List<String> installed = new ArrayList<>();
        final List<String> uninstalled = new ArrayList<>();

        @Override
        public List<String> versions(String name) {
            return List.of("1.0.0");
        }

        @Override
        public String latestSatisfying(String name, String range) {
            return "1.0.0";
        }

        @Override
        public Path install(NpmSource source, Path installRoot) throws IOException {
            installed.add(source.spec());
            var target = installRoot.resolve("node_modules").resolve(source.name());
            Files.createDirectories(target);
            Files.writeString(target.resolve("package.json"),
                "{\"name\":\"" + source.name() + "\",\"version\":\"1.0.0\"}");
            return target;
        }

        @Override
        public void uninstall(String name, Path installRoot) throws IOException {
            uninstalled.add(name);
            GitPackageStore.deleteRecursively(
                installRoot.resolve("node_modules").resolve(name));
        }
    }

    /** 记录 git 调用的 runner（不 spawn 真 git）。 */
    private static final class RecordingGitRunner extends GitRunner {
        final List<List<String>> ran = new ArrayList<>();

        @Override
        public void run(List<String> args, Path cwd) {
            ran.add(args);
        }
    }

    @BeforeEach
    void setUp() {
        storage = new InMemorySettingsStorage();
        settings = SettingsManager.withStorage(storage);
        npmResolver = new RecordingNpmResolver();
        git = new RecordingGitRunner();
        events = new ArrayList<>();
        var resolver = new SourceResolver(cwd, agentDir, settings::isProjectTrusted);
        var layout = new InstallLayout(resolver);
        manager = new PackageManager(resolver, settings, List.of(), npmResolver, layout,
            new GitPackageStore(git, layout, null), homeDir);
        manager.setProgressCallback(events::add);
    }

    @Test
    void installNpmDispatchesToResolverWithProgress() {
        manager.install("npm:@foo/bar@^1.0.0", false);

        assertThat(npmResolver.installed).containsExactly("@foo/bar@^1.0.0");
        assertThat(events).extracting(ProgressEvent::type)
            .containsExactly(Type.START, Type.COMPLETE);
        assertThat(events.get(0).action()).isEqualTo(Action.INSTALL);
        assertThat(events.get(0).message()).isEqualTo("Installing npm:@foo/bar@^1.0.0...");
        // pi installNpm：ensureNpmProject 脚手架（installRoot/package.json）
        assertThat(agentDir.resolve("npm").resolve("package.json")).exists();
    }

    @Test
    void installGitClones() {
        manager.install("git:github.com/user/repo", false);

        assertThat(git.ran).hasSize(1);
        assertThat(git.ran.get(0)).startsWith("git", "clone");
    }

    @Test
    void installLocalOnlyValidatesExistence() throws IOException {
        Files.createDirectories(cwd.resolve("pkg"));
        manager.install("./pkg", false);   // 不抛 = 通过；无文件系统写入
        assertThat(npmResolver.installed).isEmpty();
        assertThat(git.ran).isEmpty();
    }

    @Test
    void installLocalMissingThrowsVerbatim() {
        var resolved = cwd.resolve("nope").toString();
        assertThatThrownBy(() -> manager.install("./nope", false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Path does not exist: " + resolved);   // pi :1051 逐字
        assertThat(events).extracting(ProgressEvent::type)
            .containsExactly(Type.START, Type.ERROR);
    }

    @Test
    void installProjectScopeRequiresTrust() {
        settings.setProjectTrusted(false);
        assertThatThrownBy(() -> manager.install("npm:foo", true))
            .hasMessage("Project is not trusted; refusing to access project package storage");
    }

    @Test
    void removeNpmUninstalls() throws IOException {
        manager.install("npm:foo", false);
        assertThat(agentDir.resolve("npm/node_modules/foo")).exists();

        manager.remove("npm:foo", false);

        assertThat(npmResolver.uninstalled).containsExactly("foo");
        assertThat(agentDir.resolve("npm/node_modules/foo")).doesNotExist();
        assertThat(events).extracting(ProgressEvent::action)
            .containsExactly(Action.INSTALL, Action.INSTALL, Action.REMOVE, Action.REMOVE);
    }

    @Test
    void removeGitDeletesClone() {
        manager.install("git:github.com/user/repo", false);
        manager.remove("git:github.com/user/repo", false);
        assertThat(git.ran).hasSize(1);   // clone 一次；remove 纯文件系统
    }

    @Test
    void removeLocalHasNoFileSystemAction() throws IOException {
        Files.createDirectories(cwd.resolve("pkg"));
        manager.remove("./pkg", false);
        assertThat(cwd.resolve("pkg")).exists();   // local 臂不动文件系统
    }

    @Test
    void uninstallOnMissingRootIsNoOp() throws IOException {
        // pi existsSync(installRoot) 门（:1876-1878）。
        new HttpNpmResolver((java.net.http.HttpClient) null, "http://unused",
            new InstallLayout(new SourceResolver(cwd, agentDir, () -> true)))
            .uninstall("foo", cwd.resolve("absent"));
        // 不抛即通过
    }
}
