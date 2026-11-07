package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import com.pijava.coding.agent.core.PackageSource;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.pkg.ParsedSource.GitSource;
import com.pijava.coding.agent.pkg.ParsedSource.LocalSource;
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;
import com.pijava.coding.agent.pkg.ProgressEvent.Action;
import com.pijava.coding.agent.pkg.SourceResolver.ScopedPackage;

/**
 * pi {@code DefaultPackageManager} 的 update 机制（批 19 T3，docs/30 §3）——
 * {@code update}/{@code updateConfiguredSources}/{@code shouldUpdateNpmSource}/
 * {@code updateNpmBatch}/{@code checkForAvailableUpdates}/{@code runWithConcurrency}
 * （package-manager.ts:1090-1280/:1691-1713）对位。
 *
 * <p>并发闸＝共享游标 worker-pool（虚拟线程＋{@link AtomicInteger}）：<b>首错即整体
 * 抛、其余 worker 后台继续</b>（pi detached 语义，裁决⑥——{@code StructuredTaskScope}
 * 默认取消其余任务，语义不符故自写）。npm 更新按 scope 分批<b>逐包顺装</b>（pi
 * 一次 npm install 多 spec，窄差 D113）。</p>
 */
final class PackageUpdater {

    /** pi {@code UPDATE_CHECK_CONCURRENCY}（:51）。 */
    static final int UPDATE_CHECK_CONCURRENCY = 4;
    /** pi {@code GIT_UPDATE_CONCURRENCY}（:52）。 */
    static final int GIT_UPDATE_CONCURRENCY = 4;

    private final SourceResolver resolver;
    private final SettingsManager settings;
    private final InstallLayout layout;
    private final NpmResolver npmResolver;
    private final GitPackageStore gitStore;
    private final ProgressRunner progress;

    /** {@code PackageManager::withProgress}＋{@code ::emitProgress} 的注入形
     *  （构造序解环；后者供 {@code progress} 型事件）。 */
    interface ProgressRunner {
        void run(Action action, String source, String message, PackageManager.IoRunnable op)
                throws IOException;

        void emit(ProgressEvent event);
    }

    /** 可抛受检异常的任务（pi async task 对位）。 */
    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** pi {@code ConfiguredUpdateSource}／{@code NpmUpdateTarget} 合并形。 */
    private record UpdateTarget(String source, SourceScope scope, ParsedSource parsed) {}

    /** pi {@code shouldUpdateNpmSource} 的三态返回（:1178-1195）：未装 ⇒ 无版本；
     *  查询失败 ⇒ 保守 shouldUpdate＝true 且无 targetVersion。 */
    record NpmCheck(UpdateTarget entry, boolean shouldUpdate, String installedVersion,
                    String targetVersion) {}

    PackageUpdater(SourceResolver resolver, SettingsManager settings, InstallLayout layout,
            NpmResolver npmResolver, GitPackageStore gitStore, ProgressRunner progress) {
        this.resolver = resolver;
        this.settings = settings;
        this.layout = layout;
        this.npmResolver = npmResolver;
        this.gitStore = gitStore;
        this.progress = progress;
    }

    // ── update（:1090-1120）──────────────────────────────────────

    /** pi {@code update(source?)}：枚举 user+project settings packages（temporary
     *  不参与），按身份过滤；指定却无匹配 ⇒ 带 {@code Did you mean ...?} 的错误。 */
    void update(String source) {
        var identity = source != null ? resolver.getPackageIdentity(source, null) : null;
        var matched = false;
        var updateSources = new ArrayList<UpdateTarget>();
        matched |= collect(settings.global(), SourceScope.USER, identity, updateSources);
        matched |= collect(settings.project(), SourceScope.PROJECT, identity, updateSources);
        if (source != null && !matched) {
            // 模型可见文案逐字（:1430-1436）。
            throw new IllegalStateException(
                buildNoMatchingPackageMessage(source, allPackages()));
        }
        updateConfiguredSources(updateSources);
    }

    private boolean collect(Settings scopeSettings, SourceScope scope, String identity,
            List<UpdateTarget> out) {
        if (scopeSettings.packages == null) {
            return false;
        }
        var matched = false;
        for (var pkg : scopeSettings.packages) {
            var sourceStr = SourceParser.getPackageSourceString(pkg);
            if (identity != null
                && !resolver.getPackageIdentity(sourceStr, scope).equals(identity)) {
                continue;
            }
            matched = true;
            out.add(new UpdateTarget(sourceStr, scope, SourceParser.parseSource(sourceStr)));
        }
        return matched;
    }

    // ── updateConfiguredSources（:1122-1179）─────────────────────

    private void updateConfiguredSources(List<UpdateTarget> sources) {
        if (PackageEnvironment.isOfflineModeEnabled() || sources.isEmpty()) {
            return;
        }
        var npmCandidates = new ArrayList<UpdateTarget>();
        var gitCandidates = new ArrayList<UpdateTarget>();
        for (var entry : sources) {
            // Pinned npm versions are fixed. Pinned git refs are configured checkout
            // targets, so include them to reconcile an existing clone.（pi :1135-1144）
            if (entry.parsed() instanceof NpmSource npm) {
                if (!npm.pinned()) {
                    npmCandidates.add(entry);
                }
            } else if (entry.parsed() instanceof GitSource) {
                gitCandidates.add(entry);
            }
        }

        List<ThrowingSupplier<NpmCheck>> checkTasks = npmCandidates.stream()
            .<ThrowingSupplier<NpmCheck>>map(entry -> () ->
                shouldUpdateNpmSource(entry))
            .toList();
        var checkResults = runWithConcurrency(checkTasks, UPDATE_CHECK_CONCURRENCY);

        var userUpdates = new ArrayList<NpmCheck>();
        var projectUpdates = new ArrayList<NpmCheck>();
        for (var result : checkResults) {
            if (!result.shouldUpdate()) {
                continue;
            }
            (result.entry().scope() == SourceScope.USER ? userUpdates : projectUpdates)
                .add(result);
        }

        // pi :1164-1178：user-npm 批、project-npm 批、git 任务组三者并行。
        var tasks = new ArrayList<ThrowingSupplier<Void>>();
        if (!userUpdates.isEmpty()) {
            tasks.add(() -> {
                updateNpmBatch(userUpdates, SourceScope.USER);
                return null;
            });
        }
        if (!projectUpdates.isEmpty()) {
            tasks.add(() -> {
                updateNpmBatch(projectUpdates, SourceScope.PROJECT);
                return null;
            });
        }
        if (!gitCandidates.isEmpty()) {
            List<ThrowingSupplier<Void>> gitTasks = gitCandidates.stream()
                .<ThrowingSupplier<Void>>map(entry -> () -> {
                    progress.run(Action.UPDATE, entry.source(),
                        "Updating " + entry.source() + "...", () ->
                            gitStore.updateGit((GitSource) entry.parsed(), entry.scope()));
                    return null;
                })
                .toList();
            tasks.add(() -> {
                runWithConcurrency(gitTasks, GIT_UPDATE_CONCURRENCY);
                return null;
            });
        }
        runWithConcurrency(tasks, tasks.size());
    }

    /** pi {@code shouldUpdateNpmSource}（:1181-1195）：未安装 ⇒ true；查询失败
     *  ⇒ <b>true（保守更新）</b>；否则 latest &gt; installed。返回三态以支撑
     *  安装后「版本没变」的进度警告（{@link #reportUnchangedNpmUpdates}）。 */
    NpmCheck shouldUpdateNpmSource(UpdateTarget entry) {
        var source = (NpmSource) entry.parsed();
        var installedPath = layout.managedNpmInstallPath(source, entry.scope());
        var installedVersion = Files.exists(installedPath)
            ? layout.installedNpmVersion(installedPath) : null;
        if (installedVersion == null) {
            return new NpmCheck(entry, true, null, null);
        }
        try {
            // pi 传 spec 给 npm view；HTTP 臂 seam 取 name+range 同效（批 18 seam）。
            var target = npmResolver.latestSatisfying(source.name(), source.range());
            return new NpmCheck(entry, Semvers.gt(target, installedVersion), installedVersion, target);
        } catch (Exception e) {
            // Preserve existing update behavior when version lookup fails.（pi :1191-1194）
            return new NpmCheck(entry, true, installedVersion, null);
        }
    }

    /** pi {@code updateNpmBatch}（:1197-1209）：进度消息逐字；java 逐包顺装（D113）。
     *  装完走 {@link #reportUnchangedNpmUpdates}（pi :1211-1233）。 */
    private void updateNpmBatch(List<NpmCheck> sources, SourceScope scope)
            throws IOException {
        if (sources.isEmpty()) {
            return;
        }
        var single = sources.size() == 1;
        var sourceLabel = single ? sources.get(0).entry().source() : scope.id() + " npm packages";
        var message = single
            ? "Updating " + sources.get(0).entry().source() + "..."
            : "Updating " + scope.id() + " npm packages...";
        progress.run(Action.UPDATE, sourceLabel, message, () -> {
            var installRoot = layout.npmInstallRoot(scope, false);
            layout.ensureNpmProject(installRoot);
            for (var entry : sources) {
                // pi spec＝version ? spec : name@latest；HTTP 臂 range 语义同（null ⇒ 最新）。
                npmResolver.install((NpmSource) entry.entry().parsed(), installRoot);
            }
        });
        reportUnchangedNpmUpdates(sources, scope);
    }

    /**
     * pi {@code reportUnchangedNpmUpdates}（:1211-1233）：npm 在发布后短时间内可能
     * 解析到过期 registry 元数据、静默装回旧版本——announced 了更新却没变版本时
     * 发一条 progress 消息（文案逐字；品牌无插值）。
     */
    private void reportUnchangedNpmUpdates(List<NpmCheck> sources, SourceScope scope) {
        for (var check : sources) {
            if (check.installedVersion() == null || check.targetVersion() == null) {
                continue;
            }
            var source = check.entry().source();
            var installedPath = layout.managedNpmInstallPath(
                (NpmSource) check.entry().parsed(), scope);
            var versionAfter = layout.installedNpmVersion(installedPath);
            if (!check.installedVersion().equals(versionAfter)) {
                continue;
            }
            emitProgress(ProgressEvent.progress(Action.UPDATE, source,
                source + " is still " + versionAfter + " (registry reports "
                    + check.targetVersion()
                    + "); npm registry metadata may be stale, try again in a few minutes"));
        }
    }

    private void emitProgress(ProgressEvent event) {
        progress.emit(event);
    }

    // ── checkForAvailableUpdates（:1217-1280）────────────────────

    /** pi {@code checkForAvailableUpdates}：离线 ⇒ []；排除 temporary/local/pinned/
     *  未安装；并发 4。 */
    List<PackageUpdate> checkForAvailableUpdates() {
        if (PackageEnvironment.isOfflineModeEnabled()) {
            return List.of();
        }
        var allPackages = new ArrayList<ScopedPackage>();
        if (settings.project().packages != null) {
            for (var pkg : settings.project().packages) {
                allPackages.add(new ScopedPackage(pkg, SourceScope.PROJECT));
            }
        }
        if (settings.global().packages != null) {
            for (var pkg : settings.global().packages) {
                allPackages.add(new ScopedPackage(pkg, SourceScope.USER));
            }
        }
        var packageSources = resolver.dedupePackages(allPackages);

        List<ThrowingSupplier<PackageUpdate>> checks = packageSources.stream()
            .filter(entry -> entry.scope() != SourceScope.TEMPORARY)
            .<ThrowingSupplier<PackageUpdate>>map(entry -> () -> checkOne(entry))
            .toList();
        return runWithConcurrency(checks, UPDATE_CHECK_CONCURRENCY).stream()
            .filter(Objects::nonNull)
            .toList();
    }

    private PackageUpdate checkOne(ScopedPackage entry) throws IOException {
        var source = SourceParser.getPackageSourceString(entry.pkg());
        var parsed = SourceParser.parseSource(source);
        if (parsed instanceof LocalSource) {
            return null;
        }
        if (parsed instanceof NpmSource npm) {
            if (npm.pinned()) {
                return null;
            }
            var installedPath = layout.npmInstallPath(npm, entry.scope());
            if (!Files.exists(installedPath) || !npmHasAvailableUpdate(npm, installedPath)) {
                return null;
            }
            return new PackageUpdate(source, npm.name(), PackageUpdate.Type.NPM, entry.scope());
        }
        var git = (GitSource) parsed;
        if (git.pinned()) {
            return null;
        }
        var installedPath = layout.gitInstallPath(git, entry.scope());
        if (!Files.exists(installedPath) || !gitStore.gitHasAvailableUpdate(installedPath)) {
            return null;
        }
        return new PackageUpdate(source, git.host() + "/" + git.path(),
            PackageUpdate.Type.GIT, entry.scope());
    }

    /** pi {@code npmHasAvailableUpdate}（:1515-1531）：查询失败 ⇒ <b>false</b>
     *  （与 {@link #shouldUpdateNpmSource} 相反，钉死）。 */
    private boolean npmHasAvailableUpdate(NpmSource source, Path installedPath) {
        if (PackageEnvironment.isOfflineModeEnabled()) {
            return false;
        }
        var installedVersion = layout.installedNpmVersion(installedPath);
        if (installedVersion == null) {
            return false;
        }
        try {
            var target = npmResolver.latestSatisfying(source.name(), source.range());
            return Semvers.gt(target, installedVersion);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Did-you-mean（:1430-1461）─────────────────────────────────

    /** pi {@code buildNoMatchingPackageMessage}——模型可见文案逐字。 */
    String buildNoMatchingPackageMessage(String source, List<PackageSource> configured) {
        var suggestion = findSuggestedConfiguredSource(source, configured);
        if (suggestion == null) {
            return "No matching package found for " + source;
        }
        return "No matching package found for " + source + ". Did you mean " + suggestion + "?";
    }

    /** pi {@code findSuggestedConfiguredSource}：npm 匹配 name/spec；git 匹配
     *  {@code host/path[@ref]} 速写；首个命中（插入序）。 */
    private String findSuggestedConfiguredSource(String source, List<PackageSource> configured) {
        var trimmed = source.trim();
        var suggestions = new LinkedHashSet<String>();
        for (var pkg : configured) {
            var sourceStr = SourceParser.getPackageSourceString(pkg);
            var parsed = SourceParser.parseSource(sourceStr);
            if (parsed instanceof NpmSource npm) {
                if (trimmed.equals(npm.name()) || trimmed.equals(npm.spec())) {
                    suggestions.add(sourceStr);
                }
                continue;
            }
            if (parsed instanceof GitSource git) {
                var shorthand = git.host() + "/" + git.path();
                var withRef = git.ref() != null ? shorthand + "@" + git.ref() : null;
                if (trimmed.equals(shorthand) || trimmed.equals(withRef)) {
                    suggestions.add(sourceStr);
                }
            }
        }
        return suggestions.isEmpty() ? null : suggestions.getFirst();
    }

    private List<PackageSource> allPackages() {
        var all = new ArrayList<PackageSource>();
        if (settings.global().packages != null) {
            all.addAll(settings.global().packages);
        }
        if (settings.project().packages != null) {
            all.addAll(settings.project().packages);
        }
        return all;
    }

    // ── 并发闸（:1691-1713，裁决⑥）───────────────────────────────

    /**
     * pi {@code runWithConcurrency}：共享游标 worker-pool；<b>无错误聚合</b>——任一
     * 任务失败 ⇒ 首错整体抛（该 worker 停拉新任务），其余 worker 后台跑完不等待。
     */
    static <T> List<T> runWithConcurrency(List<? extends ThrowingSupplier<T>> tasks,
            int limit) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        var results = new AtomicReferenceArray<T>(tasks.size());
        var nextIndex = new AtomicInteger();
        var firstError = new AtomicReference<Throwable>();
        // TRUE＝worker 正常退出；FALSE＝出错退出（pi worker 拒绝即停拉）。
        var signals = new LinkedBlockingQueue<Boolean>();
        var workerCount = Math.max(1, Math.min(limit, tasks.size()));
        for (int w = 0; w < workerCount; w++) {
            Thread.startVirtualThread(() -> {
                while (true) {
                    var index = nextIndex.getAndIncrement();
                    if (index >= tasks.size()) {
                        signals.add(Boolean.TRUE);
                        return;
                    }
                    try {
                        results.set(index, tasks.get(index).get());
                    } catch (Throwable t) {
                        firstError.compareAndSet(null, t);
                        signals.add(Boolean.FALSE);
                        return;
                    }
                }
            });
        }
        for (int i = 0; i < workerCount; i++) {
            final boolean ok;
            try {
                ok = signals.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("concurrent task wait interrupted", e);
            }
            if (!ok) {
                throw propagate(firstError.get());
            }
        }
        var out = new ArrayList<T>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            out.add(results.get(i));
        }
        return out;
    }

    /** pi reject 原样传播对位：RuntimeException 直抛，受检异常包
     *  {@link UncheckedIOException}/{@link IllegalStateException}。 */
    private static RuntimeException propagate(Throwable error) {
        if (error instanceof RuntimeException runtime) {
            return runtime;
        }
        if (error instanceof IOException io) {
            return new UncheckedIOException(io);
        }
        return new IllegalStateException(error);
    }
}
