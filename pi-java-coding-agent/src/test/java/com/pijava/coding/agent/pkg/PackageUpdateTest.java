package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pijava.coding.agent.core.InMemorySettingsStorage;
import com.pijava.coding.agent.core.PackageSource;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;

/**
 * 批 19 T3（docs/30 §3/§7）：{@code update} 机制——离线外的全臂：temporary/
 * pinned 排除、shouldUpdate 三态（未装⇒true／查询失败⇒true 保守／gt 比较）、
 * 身份过滤＋Did-you-mean 文案逐字、npm 分批消息逐字、git pinned 参与、并发 4 闸、
 * 首错即抛＋其余 detached 继续。
 *
 * <p>离线短路臂：环境变量进程全局不可注入，由 {@link PackageEnvironment} 单测
 * 覆盖判定本身（批 18 惯例）。</p>
 */
class PackageUpdateTest {

    @TempDir
    Path cwd;

    @TempDir
    Path agentDir;

    @TempDir
    Path homeDir;

    private InMemorySettingsStorage storage;
    private SettingsManager settings;
    private StubNpmResolver npm;
    private StubGitRunner git;
    private InstallLayout layout;
    private PackageManager manager;

    /** 可控 npm stub：版本表＋查询失败表＋并发观测。 */
    private static class StubNpmResolver implements NpmResolver {
        final List<String> installed = Collections.synchronizedList(new ArrayList<>());
        final ConcurrentLinkedQueue<String> queried = new ConcurrentLinkedQueue<>();
        final List<String> failing = new ArrayList<>();
        volatile String latest = "2.0.0";
        volatile CountDownLatch queryGate;   // 非 null ⇒ latestSatisfying 阻塞等门
        final AtomicInteger activeQueries = new AtomicInteger();
        final AtomicInteger maxConcurrentQueries = new AtomicInteger();

        @Override
        public List<String> versions(String name) {
            return List.of("1.0.0", "2.0.0");
        }

        @Override
        public String latestSatisfying(String name, String range) throws IOException {
            var now = activeQueries.incrementAndGet();
            maxConcurrentQueries.accumulateAndGet(now, Math::max);
            try {
                if (queryGate != null) {
                    queryGate.await(10, TimeUnit.SECONDS);
                }
                queried.add(name);
                if (failing.contains(name)) {
                    throw new IOException("registry down for " + name);
                }
                return latest;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } finally {
                activeQueries.decrementAndGet();
            }
        }

        @Override
        public Path install(NpmSource source, Path installRoot) throws IOException {
            installed.add(source.name());
            var target = installRoot.resolve("node_modules").resolve(source.name());
            Files.createDirectories(target);
            Files.writeString(target.resolve("package.json"),
                "{\"name\":\"" + source.name() + "\",\"version\":\"" + latest + "\"}");
            return target;
        }

        @Override
        public void uninstall(String name, Path installRoot) {
            // 本套件不消费。
        }
    }

    /** 可控 git stub：40 位 sha 应答；fetch 失败表；门闩阻塞表。 */
    private static final class StubGitRunner extends GitRunner {
        static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        final List<List<String>> ran = Collections.synchronizedList(new ArrayList<>());
        final List<List<String>> captured = Collections.synchronizedList(new ArrayList<>());
        final List<String> fetchFails = new ArrayList<>();
        volatile CountDownLatch fetchGate;
        final List<String> fetched = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void run(List<String> args, Path cwd) {
            ran.add(args);
            if (args.contains("fetch")) {
                fetched.add(String.join(" ", args));
                // 失败先于门闩：失败任务不堵门（detached 语义测试依赖此序）
                if (fetchFails.stream().anyMatch(cwd.toString()::contains)) {
                    throw new GitCommandException("fetch failed for " + cwd);
                }
                if (fetchGate != null) {
                    try {
                        fetchGate.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        @Override
        public String capture(List<String> args, Path cwd, long timeoutMs) {
            captured.add(args);
            if (args.contains("--abbrev-ref")) {
                return "origin/main";
            }
            return SHA_A;
        }

        @Override
        public String remoteCapture(List<String> args, Path cwd) {
            return SHA_B + "\trefs/heads/main";
        }
    }

    @BeforeEach
    void setUp() {
        storage = new InMemorySettingsStorage();
        settings = SettingsManager.withStorage(storage);
        npm = new StubNpmResolver();
        git = new StubGitRunner();
        var resolver = new SourceResolver(cwd, agentDir, settings::isProjectTrusted);
        layout = new InstallLayout(resolver);
        manager = new PackageManager(resolver, settings, List.of(), npm, layout,
            new GitPackageStore(git, layout, null), homeDir);
    }

    private void seedUserPackages(String... sources) {
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
    void notInstalledNpmIsUpdated() {
        seedUserPackages("npm:foo");

        manager.update(null);

        assertThat(npm.installed).containsExactly("foo");   // 未安装 ⇒ shouldUpdate true
    }

    @Test
    void pinnedNpmIsSkipped() {
        seedUserPackages("npm:foo@1.0.0");

        manager.update(null);

        assertThat(npm.installed).isEmpty();
        assertThat(npm.queried).isEmpty();
    }

    @Test
    void upToDateNpmIsNotReinstalled() throws IOException {
        seedUserPackages("npm:foo");
        markInstalled("foo", "2.0.0");
        npm.latest = "2.0.0";   // gt(2.0.0, 2.0.0) = false

        manager.update(null);

        assertThat(npm.installed).isEmpty();
        assertThat(npm.queried).containsExactly("foo");
    }

    @Test
    void queryFailureMeansConservativeUpdate() throws IOException {
        seedUserPackages("npm:foo");
        markInstalled("foo", "1.0.0");
        npm.failing.add("foo");

        manager.update(null);

        assertThat(npm.installed).containsExactly("foo");   // shouldUpdate 查询失败 ⇒ true
    }

    @Test
    void bareNameIsLocalSourceAndSuggestsNpm() {
        // pi 源解析序钉死：裸名不是 npm 速写（isLocalPath 无前缀 ⇒ local）⇒
        // 与 npm:bar 身份不同 ⇒ Did-you-mean 建议臂。
        seedUserPackages("npm:foo@^1.0.0", "npm:bar");

        assertThatThrownBy(() -> manager.update("bar"))
            .hasMessage("No matching package found for bar. Did you mean npm:bar?");
        assertThat(npm.installed).isEmpty();
    }

    @Test
    void unknownSourceThrowsWithSuggestion() {
        seedUserPackages("npm:foo@1.0.0");

        assertThatThrownBy(() -> manager.update("foo"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("No matching package found for foo. Did you mean npm:foo@1.0.0?");
    }

    @Test
    void unknownSourceWithoutSuggestionThrowsPlain() {
        seedUserPackages("git:github.com/u/r");

        assertThatThrownBy(() -> manager.update("npm:absent"))
            .hasMessage("No matching package found for npm:absent");
    }

    @Test
    void gitShorthandSuggestsFullSource() {
        seedUserPackages("git:github.com/u/r@v1");

        assertThatThrownBy(() -> manager.update("github.com/u/r@v1"))
            .hasMessage("No matching package found for github.com/u/r@v1. "
                + "Did you mean git:github.com/u/r@v1?");
    }

    @Test
    void updateByExactIdentitySelectsPackage() throws IOException {
        seedUserPackages("npm:foo@^1.0.0", "npm:bar");

        manager.update("npm:bar@2.0.0");   // 身份忽略版本 ⇒ 命中 bar

        assertThat(npm.installed).containsExactly("bar");
    }

    @Test
    void pinnedGitParticipatesInUpdate() throws IOException {
        seedUserPackages("git:github.com/u/r@v1.0.0");
        var parsed = (ParsedSource.GitSource) SourceParser.parseSource("git:github.com/u/r@v1.0.0");
        Files.createDirectories(layout.gitInstallPath(parsed, SourceScope.USER));

        manager.update(null);

        // ref 臂：fetch origin <ref>（pi :1929-1932）
        assertThat(git.fetched).containsExactly("git fetch origin v1.0.0");
    }

    @Test
    void npmCheckConcurrencyCappedAtFour() throws IOException {
        for (int i = 0; i < 10; i++) {
            markInstalled("pkg" + i, "1.0.0");
        }
        seedUserPackages(java.util.stream.IntStream.range(0, 10)
            .mapToObj(i -> "npm:pkg" + i).toArray(String[]::new));
        npm.queryGate = new CountDownLatch(1);

        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(200);
                npm.queryGate.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        manager.update(null);

        assertThat(npm.maxConcurrentQueries.get()).isEqualTo(4);
        assertThat(npm.queried).hasSize(10);
        assertThat(npm.installed).hasSize(10);
    }

    @Test
    void firstErrorThrowsWhileOthersContinueDetached() throws Exception {
        seedUserPackages("git:github.com/u/good", "git:github.com/u/bad");
        for (var s : List.of("git:github.com/u/good", "git:github.com/u/bad")) {
            Files.createDirectories(layout.gitInstallPath(
                (ParsedSource.GitSource) SourceParser.parseSource(s), SourceScope.USER));
        }
        git.fetchFails.add("bad");
        git.fetchGate = new CountDownLatch(1);   // good 的 fetch 阻塞等门

        var thrown = new AtomicReference<Throwable>();
        var caller = Thread.startVirtualThread(() -> {
            try {
                manager.update(null);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        caller.join(10_000);
        // 首错（bad）整体抛；good 的 fetch 仍堵在门闩上（detached 未取消）
        assertThat(thrown.get()).hasMessageContaining("fetch failed");
        assertThat(git.fetched).hasSize(2);

        git.fetchGate.countDown();   // 放走 detached 任务，验证其能跑完
        var drained = new CountDownLatch(1);
        Thread.startVirtualThread(() -> {
            // good 全链＝4 次 capture（update-target 2＋ensureGitRef 2），bad 2 次 ⇒ 6
            while (git.captured.size() < 6) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            drained.countDown();
        });
        assertThat(drained.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
