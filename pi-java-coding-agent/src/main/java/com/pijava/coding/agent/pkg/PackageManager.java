package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import com.pijava.coding.agent.core.PackageSource;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.pkg.ParsedSource.GitSource;
import com.pijava.coding.agent.pkg.ParsedSource.LocalSource;
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;
import com.pijava.coding.agent.pkg.PathMetadata.Origin;
import com.pijava.coding.agent.pkg.ProgressEvent.Action;
import com.pijava.coding.agent.pkg.ResourceAccumulator.Slot;
import com.pijava.coding.agent.pkg.SourceResolver.ScopedPackage;

/**
 * pi {@code DefaultPackageManager} 的 resolve 内核（package-manager.ts:920-989/
 * :1296-1340 等）——包源（project 先入＋身份去重）→ settings 四类数组 →
 * 自动发现 → 内置扩展，输出五档优先级排序＋canonicalize 去重的路径表。
 *
 * <p>批 19（docs/30）并入公开安装面：{@code install/remove}（:1036-1079）＋
 * settings 写回链（委托 {@link PackageConfigWriter}）＋{@code update}/
 * {@code checkForAvailableUpdates}（委托 {@code PackageUpdater}，T3）；CLI
 * 子命令面在 {@code subcommand/PackageCommand}。</p>
 */
public final class PackageManager {

    /** pi {@code BUILTIN_PATH_PREFIX}（source-info.ts:15）。 */
    public static final String BUILTIN_PATH_PREFIX = "builtin:";

    private final SourceResolver resolver;
    private final SettingsManager settings;
    private final List<String> builtinExtensions;
    private final NpmResolver npmResolver;
    private final InstallLayout layout;
    private final GitPackageStore gitStore;
    private final PackageResourceCollector collector;
    private final PackageConfigWriter configWriter;
    private final PackageUpdater updater;
    private final Path homeDir;
    private volatile ProgressCallback progressCallback;

    public PackageManager(SourceResolver resolver, SettingsManager settings,
            List<String> builtinExtensions, NpmResolver npmResolver) {
        this(resolver, settings, builtinExtensions, npmResolver,
            new InstallLayout(resolver),
            new GitPackageStore(new GitRunner(), new InstallLayout(resolver), null),
            Path.of(PackageAutoDiscovery.getHomeDir()));
    }

    /** 全注入形（测试；{@code homeDir} 隔离真 home 的 {@code ~/.agents} 扫描）。 */
    public PackageManager(SourceResolver resolver, SettingsManager settings,
            List<String> builtinExtensions, NpmResolver npmResolver,
            InstallLayout layout, GitPackageStore gitStore, Path homeDir) {
        // pi getNpmCommand 校验面（package-manager.ts:1787-1789，docs/29 T1.4
        // 「构造/解析处」）：首项空 ⇒ 抛（文案逐字）。HTTP 臂不消费命令本体
        //（D104），但配置非法时与 pi 同文案报错。
        NpmCommand.resolve(settings.accessors().getNpmCommand());
        this.resolver = resolver;
        this.settings = settings;
        this.builtinExtensions = List.copyOf(builtinExtensions);
        this.npmResolver = npmResolver;
        this.layout = layout;
        this.gitStore = gitStore;
        this.collector = new PackageResourceCollector(resolver);
        this.configWriter = new PackageConfigWriter(resolver, settings, this::getInstalledPath);
        this.updater = new PackageUpdater(resolver, settings, layout, npmResolver, gitStore,
            this::withProgress);
        this.homeDir = homeDir;
    }

    /** pi {@code setProgressCallback}（:833-835）。 */
    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
        gitStore.setProgressCallback(callback);
    }

    public InstallLayout layout() {
        return layout;
    }

    // ── resolve（:920-989）───────────────────────────────────────

    /** pi {@code resolve()} 逐字总装。 */
    public ResolvedPaths resolve(MissingSourceHandler onMissing) {
        var accumulator = new ResourceAccumulator();
        var globalSettings = settings.global();
        var projectSettings = settings.project();

        // Collect all packages with scope (project first so cwd resources win collisions)
        var allPackages = new ArrayList<ScopedPackage>();
        if (projectSettings.packages != null) {
            for (var pkg : projectSettings.packages) {
                allPackages.add(new ScopedPackage(pkg, SourceScope.PROJECT));
            }
        }
        if (globalSettings.packages != null) {
            for (var pkg : globalSettings.packages) {
                allPackages.add(new ScopedPackage(pkg, SourceScope.USER));
            }
        }

        // Dedupe: project scope wins over global for same package identity
        var packageSources = resolver.dedupePackages(allPackages);
        resolvePackageSources(packageSources, accumulator, onMissing);

        var globalBaseDir = resolver.agentDir();
        var projectBaseDir = resolver.cwd().resolve(SourceResolver.CONFIG_DIR_NAME);

        for (var resourceType : ResourceType.ALL) {
            resolveLocalEntriesSafe(orEmpty(settingsList(projectSettings, resourceType)),
                resourceType, accumulator,
                new PathMetadata("local", SourceScope.PROJECT, Origin.TOP_LEVEL, null, null),
                projectBaseDir);
            resolveLocalEntriesSafe(orEmpty(settingsList(globalSettings, resourceType)),
                resourceType, accumulator,
                new PathMetadata("local", SourceScope.USER, Origin.TOP_LEVEL, null, null),
                globalBaseDir);
        }

        PackageAutoDiscovery.addAutoDiscoveredResources(accumulator, globalSettings,
            projectSettings, globalBaseDir, projectBaseDir, resolver.cwd(), homeDir,
            settings::isProjectTrusted);

        // Built-in extensions are enabled unless the user `extensions` setting excludes them,
        // for example with `-builtin:mcp`. A matching `+`, `-`, or `!` entry in the project
        // setting overrides that.
        for (var name : builtinExtensions) {
            var path = BUILTIN_PATH_PREFIX + name;
            var projectEnabled = ResourcePatterns.applyAutoloadDisabledPatterns(
                List.of(path),
                ResourcePatterns.getOverridePatterns(orEmpty(projectSettings.extensions)),
                projectBaseDir.toString()).get(path);
            accumulator.addResource(ResourceType.EXTENSIONS, path,
                new PathMetadata("builtin",
                    projectEnabled == null ? SourceScope.USER : SourceScope.PROJECT,
                    Origin.TOP_LEVEL, null, null),
                projectEnabled != null ? projectEnabled
                    : ResourcePatterns.isEnabledByOverrides(path,
                        orEmpty(globalSettings.extensions), globalBaseDir.toString()));
        }

        return toResolvedPaths(accumulator);
    }

    /** pi {@code resolveExtensionSources}（:991-1007）：CLI 显式源（{@code -e}），
     *  {@code builtin:} 前缀直收，余者按包源解析（temporary 不写 settings）。 */
    public ResolvedPaths resolveExtensionSources(List<String> sources, boolean local,
            boolean temporary) {
        var accumulator = new ResourceAccumulator();
        var scope = temporary ? SourceScope.TEMPORARY
            : local ? SourceScope.PROJECT : SourceScope.USER;
        for (var source : sources) {
            if (source.startsWith(BUILTIN_PATH_PREFIX)) {
                accumulator.addResource(ResourceType.EXTENSIONS, source,
                    new PathMetadata("builtin", scope, Origin.TOP_LEVEL, null, null), true);
            }
        }
        var packageSources = sources.stream()
            .filter(source -> !source.startsWith(BUILTIN_PATH_PREFIX))
            .map(source -> new ScopedPackage(PackageSource.of(source), scope))
            .toList();
        resolvePackageSources(packageSources, accumulator, null);
        return toResolvedPaths(accumulator);
    }

    // ── 批 19 T1：install/remove 公开面＋settings 写回链（:1036-1079/
    //    :832-879/:1008-1034）─────────────────────────────────────

    /**
     * pi {@code install}（:1036-1058）：npm→装／git→clone／local→仅校验路径存在
     * （{@code Path does not exist: <resolved>} 逐字，模型可见）；project 域前过
     * 信任门。https URL 由 {@link SourceParser} 归 git 源（pi 同，无 JAR 下载臂——
     * 裁决④）。
     */
    public void install(String source, boolean local) {
        var parsed = SourceParser.parseSource(source);
        var scope = local ? SourceScope.PROJECT : SourceScope.USER;
        resolver.assertProjectTrustedForScope(scope);
        try {
            withProgress(Action.INSTALL, source, "Installing " + source + "...", () -> {
                if (parsed instanceof NpmSource npm) {
                    var installRoot = layout.npmInstallRoot(scope, false);
                    layout.ensureNpmProject(installRoot);
                    npmResolver.install(npm, installRoot);
                    return;
                }
                if (parsed instanceof GitSource git) {
                    gitStore.installGit(git, scope);
                    return;
                }
                var resolved = resolver.resolvePath(((LocalSource) parsed).path());
                var path = PackageResourceScanner.parsePathOrNull(resolved);
                if (path == null || !Files.exists(path)) {
                    throw new IllegalStateException("Path does not exist: " + resolved);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** pi {@code installAndPersist}（:1060-1062）：先文件系统后 settings。 */
    public void installAndPersist(String source, boolean local) {
        install(source, local);
        addSourceToSettings(source, local);
    }

    /**
     * pi {@code remove}（:1064-1079）：npm→卸载（D113 目录删除）／git→删 clone／
     * local→无文件系统动作（只写 settings）。
     */
    public void remove(String source, boolean local) {
        var parsed = SourceParser.parseSource(source);
        var scope = local ? SourceScope.PROJECT : SourceScope.USER;
        resolver.assertProjectTrustedForScope(scope);
        try {
            withProgress(Action.REMOVE, source, "Removing " + source + "...", () -> {
                if (parsed instanceof NpmSource npm) {
                    npmResolver.uninstall(npm.name(), layout.npmInstallRoot(scope, false));
                    return;
                }
                if (parsed instanceof GitSource git) {
                    gitStore.removeGit(git, scope);
                }
                // local 臂：无文件系统动作（pi :1075-1077）。
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** pi {@code removeAndPersist}（:1081-1084）：remove 后写 settings，返回
     *  settings 是否有匹配条目被移除。 */
    public boolean removeAndPersist(String source, boolean local) {
        remove(source, local);
        return removeSourceFromSettings(source, local);
    }

    /** pi {@code addSourceToSettings}（:832-861）——委托 {@link PackageConfigWriter}。 */
    public boolean addSourceToSettings(String source, boolean local) {
        return configWriter.addSourceToSettings(source, local);
    }

    /** pi {@code removeSourceFromSettings}（:863-879）。 */
    public boolean removeSourceFromSettings(String source, boolean local) {
        return configWriter.removeSourceFromSettings(source, local);
    }

    /** pi {@code listConfiguredPackages}（:1008-1034）。 */
    public List<ConfiguredPackage> listConfiguredPackages() {
        return configWriter.listConfiguredPackages();
    }

    /** pi {@code update(source?)}（:1090-1120）——委托 {@link PackageUpdater}（T3）。 */
    public void update(String source) {
        updater.update(source);
    }

    /** pi {@code checkForAvailableUpdates}（:1217-1280）——启动更新通知操作数（T7）。 */
    public List<PackageUpdate> checkForAvailableUpdates() {
        return updater.checkForAvailableUpdates();
    }

    /** pi {@code getInstalledPath}（:883-897）：已装路径查询（不存在 ⇒ null）。 */
    public Path getInstalledPath(String source, SourceScope scope) {
        var parsed = SourceParser.parseSource(source);
        if (parsed instanceof NpmSource npm) {
            var path = layout.npmInstallPath(npm, scope);
            return Files.exists(path) ? path : null;
        }
        if (parsed instanceof GitSource git) {
            var path = layout.gitInstallPath(git, scope);
            return Files.exists(path) ? path : null;
        }
        if (parsed instanceof LocalSource local) {
            var baseDir = resolver.getBaseDirForScope(scope);
            var path = PackageResourceScanner.parsePathOrNull(
                resolver.resolvePathFromBase(local.path(), baseDir));
            return path != null && Files.exists(path) ? path : null;
        }
        return null;
    }

    // ── 包源解析（:1296-1340）─────────────────────────────────────

    private void resolvePackageSources(List<ScopedPackage> sources,
            ResourceAccumulator accumulator, MissingSourceHandler onMissing) {
        for (var entry : sources) {
            var sourceStr = SourceParser.getPackageSourceString(entry.pkg());
            var filter = entry.pkg().stringForm() ? null : PackageFilter.from(entry.pkg());
            var deltaBase = findAutoloadDeltaBase(entry.pkg(), entry.scope(), sources);
            var resolvedSource = deltaBase != null ? deltaBase.source() : sourceStr;
            var resolvedScope = deltaBase != null ? deltaBase.scope() : entry.scope();
            var parsed = SourceParser.parseSource(resolvedSource);
            var metadata = new PathMetadata(sourceStr, entry.scope(), Origin.PACKAGE, null, null);

            if (parsed instanceof LocalSource local) {
                var baseDir = resolver.getBaseDirForScope(resolvedScope);
                collector.resolveLocalExtensionSource(local, accumulator, filter, metadata, baseDir);
                continue;
            }

            if (parsed instanceof NpmSource npm) {
                var installedPath = layout.npmInstallPath(npm, resolvedScope);
                var needsInstall = !Files.exists(installedPath)
                    || !layout.installedNpmMatchesConfiguredVersion(npm, installedPath);
                if (needsInstall) {
                    if (!installMissing(onMissing, parsed, resolvedScope, resolvedSource)) {
                        continue;
                    }
                    installedPath = layout.npmInstallPath(npm, resolvedScope);
                }
                var packageMetadata = metadata.withBaseDir(installedPath.toString())
                    .withPackageRoot(installedPath.toString());
                collector.collectPackageResources(installedPath, accumulator, filter,
                    packageMetadata);
                continue;
            }

            var git = (GitSource) parsed;
            var gitInstalledPath = layout.gitInstallPath(git, resolvedScope);
            if (!Files.exists(gitInstalledPath)) {
                if (!installMissing(onMissing, parsed, resolvedScope, resolvedSource)) {
                    continue;
                }
            } else if (resolvedScope == SourceScope.TEMPORARY && !git.pinned()
                && !PackageEnvironment.isOfflineModeEnabled()) {
                gitStore.refreshTemporaryGitSource(git, resolvedSource);
            }
            var gitMetadata = metadata.withBaseDir(gitInstalledPath.toString())
                .withPackageRoot(gitInstalledPath.toString());
            collector.collectPackageResources(gitInstalledPath, accumulator, filter, gitMetadata);
        }
    }

    /** pi {@code installMissing} 闭包（:1302-1313）：离线 ⇒ 跳过；无回调 ⇒
     *  直装；回调 skip ⇒ 跳过、error ⇒ 抛（逐字）、install ⇒ 装。 */
    private boolean installMissing(MissingSourceHandler onMissing, ParsedSource parsed,
            SourceScope scope, String resolvedSource) {
        if (PackageEnvironment.isOfflineModeEnabled()) {
            return false;
        }
        if (onMissing == null) {
            installParsedSource(parsed, scope);
            return true;
        }
        var action = onMissing.onMissing(resolvedSource);
        if (action == MissingSourceAction.SKIP) {
            return false;
        }
        if (action == MissingSourceAction.ERROR) {
            // 模型可见文案，逐字。
            throw new IllegalStateException("Missing source: " + resolvedSource);
        }
        installParsedSource(parsed, scope);
        return true;
    }

    /** pi {@code installParsedSource}（:1393-1401）。 */
    private void installParsedSource(ParsedSource parsed, SourceScope scope) {
        try {
            if (parsed instanceof NpmSource npm) {
                var installRoot = layout.npmInstallRoot(scope, scope == SourceScope.TEMPORARY);
                layout.ensureNpmProject(installRoot);
                npmResolver.install(npm, installRoot);
                return;
            }
            if (parsed instanceof GitSource git) {
                gitStore.installGit(git, scope);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** pi {@code findAutoloadDeltaBase}（:1342-1357）：project autoload=false
     *  条目 ⇒ 资源从 user 同身份条目的安装收。 */
    private ScopedSource findAutoloadDeltaBase(PackageSource pkg, SourceScope scope,
            List<ScopedPackage> sources) {
        if (scope != SourceScope.PROJECT || pkg.stringForm()
            || !Boolean.FALSE.equals(pkg.autoload())) {
            return null;
        }
        var identity = resolver.getPackageIdentity(pkg.source(), scope);
        for (var entry : sources) {
            if (entry.scope() == SourceScope.USER
                && resolver.getPackageIdentity(
                    SourceParser.getPackageSourceString(entry.pkg()), SourceScope.USER)
                    .equals(identity)) {
                return new ScopedSource(SourceParser.getPackageSourceString(entry.pkg()),
                    SourceScope.USER);
            }
        }
        return null;
    }

    private record ScopedSource(String source, SourceScope scope) {}

    // ── 输出整形（:2637-2663）─────────────────────────────────────

    /** pi {@code toResolvedPaths}：每类按 rank 升序（稳定排序＝同 rank 插入序），
     *  再按 canonicalize 去重——同路径先到者胜。 */
    private static ResolvedPaths toResolvedPaths(ResourceAccumulator accumulator) {
        return new ResolvedPaths(
            mapToResolved(accumulator.target(ResourceType.EXTENSIONS)),
            mapToResolved(accumulator.target(ResourceType.SKILLS)),
            mapToResolved(accumulator.target(ResourceType.PROMPTS)),
            mapToResolved(accumulator.target(ResourceType.THEMES)));
    }

    private static List<ResolvedResource> mapToResolved(
            java.util.Map<String, Slot> entries) {
        var resolved = new ArrayList<ResolvedResource>();
        for (var entry : entries.entrySet()) {
            resolved.add(new ResolvedResource(entry.getKey(), entry.getValue().enabled(),
                entry.getValue().metadata()));
        }
        // Java sort 稳定 ≙ pi Array.sort 稳定（ES2019+）。
        resolved.sort(Comparator.comparingInt(
            r -> PathMetadata.resourcePrecedenceRank(r.metadata())));
        var seen = new HashSet<String>();
        var deduped = new ArrayList<ResolvedResource>();
        for (var entry : resolved) {
            if (seen.add(canonicalizePath(entry.path()))) {
                deduped.add(entry);
            }
        }
        return deduped;
    }

    /** pi {@code canonicalizePath}（utils/paths.ts:27-33）：realpath 失败回原串。
     *  java 对位 {@code toRealPath(NOFOLLOW_LINKS)}（docs/29 §3 T5 裁决文）。
     *  批 19 转 public：config TUI 的继承态键（{@code type:canonicalizePath}，
     *  config-selector.ts:850-852）共用。 */
    public static String canonicalizePath(String path) {
        try {
            return Path.of(path).toRealPath(LinkOption.NOFOLLOW_LINKS).toString();
        } catch (IOException | java.nio.file.InvalidPathException | SecurityException e) {
            return path;
        }
    }

    // ── 小件 ─────────────────────────────────────────────────────

    private void resolveLocalEntriesSafe(List<String> entries, ResourceType type,
            ResourceAccumulator accumulator, PathMetadata metadata, Path baseDir) {
        collector.resolveLocalEntries(entries, type, accumulator, metadata, baseDir);
    }

    private static List<String> orEmpty(List<String> entries) {
        return entries == null ? List.of() : entries;
    }

    private static List<String> settingsList(Settings settings, ResourceType type) {
        return switch (type) {
            case EXTENSIONS -> settings.extensions;
            case SKILLS -> settings.skills;
            case PROMPTS -> settings.prompts;
            case THEMES -> settings.themes;
        };
    }

    /** pi {@code withProgress}（:903-918）：操作前 start，成 complete，转发
     *  error（message＝异常 message）并把异常继续抛。批 19 转 public：install/
     *  remove/update 的 CLI 消费面（E6-9）经此发射。 */
    public void withProgress(Action action, String source, String message, IoRunnable operation)
            throws IOException {
        emitProgress(ProgressEvent.start(action, source, message));
        try {
            operation.run();
            emitProgress(ProgressEvent.complete(action, source));
        } catch (IOException | RuntimeException e) {
            emitProgress(ProgressEvent.error(action, source, e.getMessage()));
            throw e;
        }
    }

    private void emitProgress(ProgressEvent event) {
        var callback = progressCallback;
        if (callback != null) {
            callback.onProgress(event);
        }
    }

    @FunctionalInterface
    public interface IoRunnable {
        void run() throws IOException;
    }
}
