package com.pijava.coding.agent.core;

import java.nio.file.Path;

import com.pijava.coding.agent.extension.ExtensionManager;
import com.pijava.coding.agent.pkg.GitPackageStore;
import com.pijava.coding.agent.pkg.GitRunner;
import com.pijava.coding.agent.pkg.HttpNpmResolver;
import com.pijava.coding.agent.pkg.InstallLayout;
import com.pijava.coding.agent.pkg.PackageAutoDiscovery;
import com.pijava.coding.agent.pkg.PackageManager;
import com.pijava.coding.agent.pkg.SourceResolver;

/**
 * 批 19（docs/30 T4）：{@link PackageManager} 的公共装配缝——pi
 * {@code DefaultPackageManager} 构造对位（resource-loader.ts:382-390）。
 * CLI 子命令面（{@code subcommand/PackageCommand}）、{@code --help} 轻量装载
 * （{@code ExtensionFlagSupport}）、config TUI 壳共用；{@code SessionSetup} 的
 * 会话装配内部形委托到此类。
 */
public final class PackageManagers {

    private PackageManagers() {}

    /** 生产形：{@code homeDir} 取真 home（自动发现扫 {@code ~/.agents}）。 */
    public static PackageManager create(Path cwd, Path agentDir, SettingsManager settings) {
        return create(cwd, agentDir, settings, Path.of(PackageAutoDiscovery.getHomeDir()));
    }

    /** 全注入形（测试：{@code homeDir} 隔离真 home）。 */
    public static PackageManager create(Path cwd, Path agentDir, SettingsManager settings,
            Path homeDir) {
        var resolver = new SourceResolver(cwd, agentDir, settings::isProjectTrusted);
        var layout = new InstallLayout(resolver);
        return new PackageManager(resolver, settings,
            ExtensionManager.builtinExtensionNames(),
            new HttpNpmResolver(cwd, layout), layout,
            new GitPackageStore(new GitRunner(), layout, null), homeDir);
    }
}
