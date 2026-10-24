package com.pijava.coding.agent.subcommand;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import com.pijava.coding.agent.core.FileSettingsStorage;
import com.pijava.coding.agent.core.PackageManagers;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.core.TrustManager;
import com.pijava.coding.agent.spi.ConfigSelectorProvider;
import com.pijava.coding.agent.spi.ScopedResolvedPaths;

/**
 * {@code pi-java config} subcommand（批 19 T5+T6，docs/30 §3）——pi
 * {@code handleConfigCommand}（package-manager-cli.ts:813-888）对位。
 *
 * <p>无参/纯 flag 形 ⇒ 开资源开关 TUI（SPI 发现，pi {@code selectConfig} 对位；
 * 关闭后返回 0 ≙ pi {@code process.exit(0)} :887）。{@code config enable|disable
 * <resource> <value> [-l]} 是 java 独有便利面（pi 只有 TUI），写回语义＝pi
 * {@code toggleTopLevelResource} 的 pattern 代数（config-selector.ts:538-584）。</p>
 *
 * <p>信任面（D114）：无交互询问臂，{@code -a}/{@code -na} 覆写优先，否则按已存
 * 信任标记判定；{@code -l} 未信任 ⇒ 报错（pi :859 逐字）。</p>
 */
public final class ConfigCommand {

    /** pi {@code CONFIG_COMMAND_USAGE}（:300）。 */
    static final String USAGE = "pi-java config [-l] [--approve|--no-approve]";

    private static final List<String> RESOURCES =
        List.of("extensions", "skills", "prompts", "themes");

    private ConfigCommand() {}

    /** 环境注入缝（测试重定向 settings/信任根/cwd/agentDir/homeDir/TUI）。 */
    record ConfigEnv(SettingsManager manager, Path trustRoot, Path cwd, Path agentDir,
            Path homeDir, ConfigSelectorProvider tui) {
        static ConfigEnv system() {
            return new ConfigEnv(SettingsManager.load(null), TrustManager.defaultRoot(),
                Path.of(System.getProperty("user.dir")),
                FileSettingsStorage.defaultAgentDir(),
                Path.of(com.pijava.coding.agent.pkg.PackageAutoDiscovery.getHomeDir()),
                ServiceLoader.load(ConfigSelectorProvider.class).findFirst().orElse(null));
        }
    }

    /**
     * Dispatch the config subcommand against the process environment.
     *
     * @param subArgs tokens after {@code config}
     * @return process exit code
     */
    public static int run(String[] subArgs) {
        return run(subArgs, ConfigEnv.system());
    }

    /** Testable variant with injected environment. */
    static int run(String[] subArgs, ConfigEnv env) {
        // pi :822-825：help 先赢。
        for (var token : subArgs) {
            if ("-h".equals(token) || "--help".equals(token)) {
                PackageCommandHelp.printConfig();
                return 0;
            }
        }
        var local = false;
        Boolean trustOverride = null;
        String command = null;
        String resource = null;
        String value = null;
        for (var token : subArgs) {
            switch (token) {
                case "-l", "--local" -> local = true;
                case "-a", "--approve" -> trustOverride = Boolean.TRUE;
                case "-na", "--no-approve" -> trustOverride = Boolean.FALSE;
                default -> {
                    if (token.startsWith("-")) {
                        // pi :836-840 逐字。
                        System.err.println("Unknown option " + token + " for \"config\".");
                        System.err.println("Use \"pi-java --help\" or \"" + USAGE + "\".");
                        return 1;
                    }
                    if (command == null) {
                        command = token;
                    } else if (resource == null) {
                        resource = token;
                    } else if (value == null) {
                        value = token;
                    } else {
                        // pi :841-846 逐字。
                        System.err.println("Unexpected argument " + token + ".");
                        System.err.println("Usage: " + USAGE);
                        return 1;
                    }
                }
            }
        }
        if (command == null) {
            return openTui(env, local, trustOverride);
        }
        return switch (command) {
            case "enable", "disable" -> toggle(env, local, trustOverride, resource, value,
                "enable".equals(command));
            default -> {
                // pi 无子命令形：多余位置参数报 Unexpected argument（:841-846）。
                System.err.println("Unexpected argument " + command + ".");
                System.err.println("Usage: " + USAGE);
                yield 1;
            }
        };
    }

    // ── TUI 臂（pi :849-887 逐字序）──────────────────────────────

    private static int openTui(ConfigEnv env, boolean local, Boolean trustOverride) {
        var manager = env.manager();
        // D114：override 优先，否则按已存信任标记判定（无交互询问臂）。
        manager.setProjectTrusted(trustOverride != null ? trustOverride
            : new TrustManager(env.trustRoot(), manager.effective().defaultProjectTrust)
                .isTrusted(env.cwd()));
        if (local && !manager.isProjectTrusted()) {
            // pi :859 逐字（resource config 文案）。
            System.err.println(
                "Project is not trusted. Use --approve to modify local resource config.");
            return 1;
        }
        if (env.tui() == null) {
            System.err.println("error: config requires pi-java-tui on the classpath");
            return 1;
        }
        // pi :867-872：global 份＝信任恒拒的 settings（project 资源全剔除）。
        var globalManager = SettingsManager.load(env.agentDir(), env.cwd(), Boolean.FALSE);
        var globalResolved = PackageManagers.create(env.cwd(), env.agentDir(), globalManager,
            env.homeDir()).resolve(null);
        // pi :873-876：未信任 ⇒ project 份复用 global 副本。
        var projectResolved = manager.isProjectTrusted()
            ? PackageManagers.create(env.cwd(), env.agentDir(), manager, env.homeDir())
                .resolve(null)
            : globalResolved;
        // pi :887 process.exit(0) 对位（Main 统一 System.exit）；评审 I-1：
        // TUI 失败（无终端等）的退出码由 provider 上报，不恒 0。
        return env.tui().selectConfig(new ScopedResolvedPaths(globalResolved, projectResolved),
            manager, env.cwd(), env.agentDir(),
            local ? ConfigSelectorProvider.WriteScope.PROJECT
                : ConfigSelectorProvider.WriteScope.GLOBAL,
            manager.isProjectTrusted());
    }

    // ── enable/disable 便利臂（java 独有；pi pattern 代数写回）────

    private static int toggle(ConfigEnv env, boolean local, Boolean trustOverride,
            String resource, String value, boolean enable) {
        if (resource == null || value == null) {
            usage();
            return 1;
        }
        if (!RESOURCES.contains(resource)) {
            System.out.println("Unknown resource: " + resource + " (expected " + RESOURCES + ")");
            return 1;
        }
        var manager = env.manager();
        if (local) {
            manager.setProjectTrusted(trustOverride != null ? trustOverride
                : new TrustManager(env.trustRoot(), manager.effective().defaultProjectTrust)
                    .isTrusted(env.cwd()));
            if (!manager.isProjectTrusted()) {
                System.err.println(
                    "Project is not trusted. Use --approve to modify local resource config.");
                return 1;
            }
        }
        var scopeSettings = local ? manager.project() : manager.global();
        var current = new ArrayList<>(listOf(scopeSettings, resource));
        // pi :549-554 逐字：剥 !/+/- 前缀后与 pattern 等值的条目一律剔除。
        current.removeIf(entry -> stripPrefix(entry).equals(value));
        current.add((enable ? "+" : "-") + value);
        setList(manager, local, resource, current);
        System.out.println((enable ? "Enabled " : "Disabled ") + value + " for " + resource
            + (local ? " (project)" : " (global)"));
        return 0;
    }

    /** pi toggleTopLevelResource 的剥前缀（:550-551）。 */
    private static String stripPrefix(String entry) {
        return entry.startsWith("!") || entry.startsWith("+") || entry.startsWith("-")
            ? entry.substring(1) : entry;
    }

    private static List<String> listOf(Settings settings, String resource) {
        return switch (resource) {
            case "extensions" -> settings.extensions == null ? List.of() : settings.extensions;
            case "skills" -> settings.skills == null ? List.of() : settings.skills;
            case "prompts" -> settings.prompts == null ? List.of() : settings.prompts;
            default -> settings.themes == null ? List.of() : settings.themes;
        };
    }

    /** 批 19 T2 字段级 merge 写回面（替代旧直写 FileSettingsStorage 整本写）。 */
    private static void setList(SettingsManager manager, boolean local, String resource,
            List<String> values) {
        if (local) {
            switch (resource) {
                case "extensions" -> manager.setProjectExtensions(values);
                case "skills" -> manager.setProjectSkills(values);
                case "prompts" -> manager.setProjectPrompts(values);
                default -> manager.setProjectThemes(values);
            }
            return;
        }
        switch (resource) {
            case "extensions" -> manager.setExtensions(values);
            case "skills" -> manager.setSkills(values);
            case "prompts" -> manager.setPrompts(values);
            default -> manager.setThemes(values);
        }
    }

    private static void usage() {
        System.out.println("""
            Usage:
              pi-java config [-l] [--approve|--no-approve]     Open the resource config TUI
              pi-java config enable <resource> <value> [-l]  Enable a resource entry
              pi-java config disable <resource> <value> [-l] Disable a resource entry
            Resources: extensions | skills | prompts | themes
            -l  project scope (.pi-java/settings.json) instead of global
            -a/--approve, -na/--no-approve  override project trust""");
    }
}
