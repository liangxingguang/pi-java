package com.pijava.coding.agent.subcommand;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.core.TrustManager;

/**
 * {@code pi-java config} subcommand (Phase 3 design §9.4, P6-14；批 19 T5 重裁).
 *
 * <p>{@code config enable|disable <resource> <value> [-l]} 是 java 独有便利面（pi
 * 只有 TUI）——写回语义改 pi {@code toggleTopLevelResource}（config-selector.ts:
 * 538-584）的 pattern 代数：先剥 {@code !}/{@code +}/{@code -} 前缀去重，enable ⇒
 * 推 {@code +value}、disable ⇒ 推 {@code -value}（自动发现条目自此可关停）。写回走
 * 批 19 T2 的字段级 merge 面（保留未知键）。</p>
 *
 * <p>{@code -l} 作用于项目设置，过信任门（pi {@code handleConfigCommand} :858-862
 * 对位：未信任 ⇒ {@code Project is not trusted. Use --approve to modify local
 * resource config.}）；{@code -a}/{@code -na} 覆写信任判定。无参 {@code config}
 * 归 T6（开 TUI）。</p>
 */
public final class ConfigCommand {

    private static final List<String> RESOURCES =
        List.of("extensions", "skills", "prompts", "themes");

    private ConfigCommand() {}

    /**
     * Dispatch the config subcommand against the process environment.
     *
     * @param subArgs tokens after {@code config}
     * @return process exit code
     */
    public static int run(String[] subArgs) {
        var manager = SettingsManager.load(null);
        return run(subArgs, manager, TrustManager.defaultRoot(),
            Path.of(System.getProperty("user.dir")));
    }

    /** Testable variant with injected settings/trust root/project dir. */
    static int run(String[] subArgs, SettingsManager manager, Path trustRoot, Path cwd) {
        String command = null;
        String resource = null;
        String value = null;
        var local = false;
        Boolean trustOverride = null;
        for (var token : subArgs) {
            switch (token) {
                case "-l", "--local" -> local = true;
                case "-a", "--approve" -> trustOverride = Boolean.TRUE;
                case "-na", "--no-approve" -> trustOverride = Boolean.FALSE;
                default -> {
                    if (command == null) {
                        command = token;
                    } else if (resource == null) {
                        resource = token;
                    } else if (value == null) {
                        value = token;
                    }
                }
            }
        }
        if (command == null) {
            return list(manager);
        }
        return switch (command) {
            case "enable", "disable" -> toggle(manager, trustRoot, cwd, local,
                trustOverride, resource, value, "enable".equals(command));
            default -> {
                usage();
                yield 2;
            }
        };
    }

    /** 无参只读列表（T6 起退役换 TUI——暂留至 T6 提交）。 */
    private static int list(SettingsManager manager) {
        var effective = manager.effective();
        System.out.println("Resource switches (effective):");
        for (var res : RESOURCES) {
            System.out.println("  " + res + ": " + listOf(effective, res));
        }
        System.out.println("  enableSkillCommands: " + effective.enableSkillCommands);
        return 0;
    }

    private static int toggle(SettingsManager manager, Path trustRoot, Path cwd,
            boolean local, Boolean trustOverride, String resource, String value,
            boolean enable) {
        if (resource == null || value == null) {
            usage();
            return 1;
        }
        if (!RESOURCES.contains(resource)) {
            System.out.println("Unknown resource: " + resource + " (expected " + RESOURCES + ")");
            return 1;
        }
        if (local) {
            // D114：非交互 CLI 无询问臂——override 优先，否则按已存信任判定。
            manager.setProjectTrusted(trustOverride != null ? trustOverride
                : new TrustManager(trustRoot, manager.effective().defaultProjectTrust)
                    .isTrusted(cwd));
            if (!manager.isProjectTrusted()) {
                // pi :859 逐字（config 面是 resource config 文案）。
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
              pi-java config [-l]                          List resource switches
              pi-java config enable <resource> <value> [-l]  Enable a resource entry
              pi-java config disable <resource> <value> [-l] Disable a resource entry
            Resources: extensions | skills | prompts | themes
            -l  operate on the project settings (.pi-java/settings.json) instead of global
            -a/--approve, -na/--no-approve  override project trust for -l""");
    }
}
