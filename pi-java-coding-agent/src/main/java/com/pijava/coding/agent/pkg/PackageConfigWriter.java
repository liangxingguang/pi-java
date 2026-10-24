package com.pijava.coding.agent.pkg;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import com.pijava.coding.agent.core.PackageSource;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.SettingsManager;

/**
 * pi {@code DefaultPackageManager} 的 settings 写回链（批 19 T1，docs/30 §3）——
 * {@code addSourceToSettings}/{@code removeSourceFromSettings}/
 * {@code listConfiguredPackages}（package-manager.ts:832-879/:1008-1034）逐字对位。
 *
 * <p>去重键＝包身份（{@link SourceResolver#packageSourcesMatch}：npm 忽略版本、
 * git SSH⇄HTTPS 归一、local 解析后绝对路径）；同身份不同写法 ⇒ 原位替换（对象形
 * 保留过滤字段只换 source）；写回走 {@link SettingsManager} 的字段级 merge 面
 * （批 19 T2）。</p>
 */
final class PackageConfigWriter {

    private final SourceResolver resolver;
    private final SettingsManager settings;
    private final BiFunction<String, SourceScope, Path> installedPathLookup;

    PackageConfigWriter(SourceResolver resolver, SettingsManager settings,
            BiFunction<String, SourceScope, Path> installedPathLookup) {
        this.resolver = resolver;
        this.settings = settings;
        this.installedPathLookup = installedPathLookup;
    }

    /** pi {@code addSourceToSettings}（:832-861）：同身份同串 ⇒ false；同身份
     *  不同写法 ⇒ 原位替换；否则追加。 */
    boolean addSourceToSettings(String source, boolean local) {
        var scope = scopeOf(local);
        var currentPackages = packagesOf(scope);
        var normalizedSource = resolver.normalizePackageSourceForSettings(source, scope);
        for (int i = 0; i < currentPackages.size(); i++) {
            var existing = currentPackages.get(i);
            if (!resolver.packageSourcesMatch(existing, source, scope)) {
                continue;
            }
            if (SourceParser.getPackageSourceString(existing).equals(normalizedSource)) {
                return false;
            }
            var next = new ArrayList<>(currentPackages);
            next.set(i, existing.stringForm()
                ? PackageSource.of(normalizedSource) : existing.withSource(normalizedSource));
            persist(scope, next);
            return true;
        }
        var next = new ArrayList<>(currentPackages);
        next.add(PackageSource.of(normalizedSource));
        persist(scope, next);
        return true;
    }

    /** pi {@code removeSourceFromSettings}（:863-879）：按身份过滤，无变化 ⇒ false。 */
    boolean removeSourceFromSettings(String source, boolean local) {
        var scope = scopeOf(local);
        var currentPackages = packagesOf(scope);
        var next = currentPackages.stream()
            .filter(existing -> !resolver.packageSourcesMatch(existing, source, scope))
            .toList();
        if (next.size() == currentPackages.size()) {
            return false;
        }
        persist(scope, next);
        return true;
    }

    /** pi {@code listConfiguredPackages}（:1008-1034）：user 组先于 project 组；
     *  {@code filtered}＝对象形判定。 */
    List<ConfiguredPackage> listConfiguredPackages() {
        var configured = new ArrayList<ConfiguredPackage>();
        collect(configured, settings.global(), SourceScope.USER);
        collect(configured, settings.project(), SourceScope.PROJECT);
        return configured;
    }

    private void collect(List<ConfiguredPackage> out, Settings scopeSettings,
            SourceScope scope) {
        if (scopeSettings.packages == null) {
            return;
        }
        for (var pkg : scopeSettings.packages) {
            var source = SourceParser.getPackageSourceString(pkg);
            var installedPath = installedPathLookup.apply(source, scope);
            out.add(new ConfiguredPackage(source, scope, !pkg.stringForm(),
                installedPath != null ? installedPath.toString() : null));
        }
    }

    private List<PackageSource> packagesOf(SourceScope scope) {
        var packages = scope == SourceScope.PROJECT
            ? settings.project().packages : settings.global().packages;
        return packages == null ? List.of() : packages;
    }

    private void persist(SourceScope scope, List<PackageSource> packages) {
        if (scope == SourceScope.PROJECT) {
            settings.setProjectPackages(packages);
        } else {
            settings.setPackages(packages);
        }
    }

    private static SourceScope scopeOf(boolean local) {
        return local ? SourceScope.PROJECT : SourceScope.USER;
    }
}
