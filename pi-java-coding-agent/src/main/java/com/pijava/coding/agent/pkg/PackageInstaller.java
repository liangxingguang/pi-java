package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

import com.pijava.coding.agent.pkg.ParsedSource.GitSource;
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;

/**
 * L5 W5 拆件（docs/04 结构债惯例）：{@link PackageManager} 的「缺失源安装 /
 * temporary 刷新」簇——{@code installMissing}/{@code installParsedSource}/
 * temporary npm 刷新。自 {@code PackageManager} 纯移动（行为零变化），只换承载者
 * 以守住文件 ≤500 行闸门。
 */
final class PackageInstaller {

    private final InstallLayout layout;
    private final NpmResolver npmResolver;
    private final GitPackageStore gitStore;

    PackageInstaller(InstallLayout layout, NpmResolver npmResolver, GitPackageStore gitStore) {
        this.layout = layout;
        this.npmResolver = npmResolver;
        this.gitStore = gitStore;
    }

    /** pi {@code installMissing} 闭包（:1386-1406）：离线 ⇒ 跳过；无回调 ⇒
     *  直装；回调 skip ⇒ 跳过、error ⇒ 抛（逐字）、install ⇒ 装；
     *  <b>装不上 ⇒ 记 failure 并跳过该包，不中止启动</b>（issue #11088）。 */
    boolean installMissing(MissingSourceHandler onMissing, ParsedSource parsed,
            SourceScope scope, String resolvedSource,
            List<PackageInstallFailure> installFailures) {
        if (PackageEnvironment.isOfflineModeEnabled()) {
            return false;
        }
        if (onMissing != null) {
            var action = onMissing.onMissing(resolvedSource);
            if (action == MissingSourceAction.SKIP) {
                return false;
            }
            if (action == MissingSourceAction.ERROR) {
                // 模型可见文案，逐字。onMissing=error 不走失败臂（pi 同）。
                throw new IllegalStateException("Missing source: " + resolvedSource);
            }
        }
        // A source that cannot be installed (no network, missing package, failing git
        // prepare step) must not abort startup. Skip its resources, like offline mode
        // does, and report the failure.（pi :1396-1405）
        try {
            installParsedSource(parsed, scope);
            return true;
        } catch (RuntimeException e) {
            installFailures.add(new PackageInstallFailure(resolvedSource, scope,
                failureMessage(e)));
            return false;
        }
    }

    /** pi 记 {@code error.message}；java 的受检异常被 {@link UncheckedIOException}
     *  包了一层 ⇒ 取根因 message（否则文案多出 {@code java.io.IOException: } 前缀）。 */
    private static String failureMessage(RuntimeException e) {
        var message = e instanceof UncheckedIOException uio && uio.getCause() != null
            ? uio.getCause().getMessage() : e.getMessage();
        return message == null ? e.toString() : message;
    }

    /** pi {@code installParsedSource}（:1393-1401）。 */
    void installParsedSource(ParsedSource parsed, SourceScope scope) {
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

    /**
     * pi {@code resolvePackageSources} 的 temporary npm 刷新臂（:1408-1427）：
     * 未固定版本的 {@code -e} 源没有 {@code pi update} 通道，装载期发现 registry
     * 有新版本就重装——无版本 spec 时按 {@code name@latest} 装（裸 {@code npm
     * install name} 会沿用 temporary package.json 里已存的 range）。<b>刷新失败
     * 静默保留缓存</b>（pi {@code catch {}}）。
     */
    void refreshTemporaryNpmSource(NpmSource npm, Path installedPath) {
        var installedVersion = layout.installedNpmVersion(installedPath);
        if (installedVersion == null) {
            return;
        }
        final String target;
        try {
            target = npmResolver.latestSatisfying(npm.name(), npm.range());
        } catch (IOException | RuntimeException e) {
            // registry 不可达 ⇒ 当作没有更新（pi {@code npmHasAvailableUpdate} 同）。
            return;
        }
        if (!Semvers.gt(target, installedVersion)) {
            return;
        }
        // pi :1421-1423：有版本 spec 用原 spec，否则 {@code name@latest}。
        var spec = npm.version() != null ? npm.spec() : npm.name() + "@latest";
        try {
            installParsedSource(SourceParser.parseSource("npm:" + spec), SourceScope.TEMPORARY);
        } catch (RuntimeException e) {
            // Keep the cached install if the refresh fails.（pi :1425-1427）
        }
    }
}
