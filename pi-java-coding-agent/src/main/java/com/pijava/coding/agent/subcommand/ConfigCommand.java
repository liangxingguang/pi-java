package com.pijava.coding.agent.subcommand;

import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.core.FileSettingsStorage;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.SettingsManager;

/**
 * {@code pi-java config} subcommand (Phase 3 design §9.4, P6-14).
 *
 * <p>资源开关的非交互式管理：{@code config [-l]} 列出有效开关；
 * {@code config enable|disable <resource> <value> [-l]} 增删资源列表
 * （extensions/skills/prompts/themes）。{@code -l} 作用于项目设置
 * （{@code <cwd>/.pi-java/settings.json}），否则作用于全局设置。</p>
 */
public final class ConfigCommand {

    private static final List<String> RESOURCES =
        List.of("extensions", "skills", "prompts", "themes");

    private ConfigCommand() {}

    /**
     * Dispatch the config subcommand against the default settings storage.
     *
     * @param subArgs tokens after {@code config}
     * @return process exit code
     */
    public static int run(String[] subArgs) {
        return run(subArgs, new FileSettingsStorage());
    }

    /** Testable variant with an injected settings storage. */
    static int run(String[] subArgs, FileSettingsStorage storage) {
        String command = null;
        String resource = null;
        String value = null;
        boolean local = false;
        for (var token : subArgs) {
            if ("-l".equals(token)) {
                local = true;
            } else if (command == null) {
                command = token;
            } else if (resource == null) {
                resource = token;
            } else if (value == null) {
                value = token;
            }
        }
        if (command == null) {
            return list(storage);
        }
        return switch (command) {
            case "enable", "disable" -> toggle(storage, local, resource, value,
                "enable".equals(command));
            default -> {
                usage();
                yield 2;
            }
        };
    }

    private static int list(FileSettingsStorage storage) {
        var effective = SettingsManager.withStorage(storage).effective();
        System.out.println("Resource switches (effective):");
        for (var res : RESOURCES) {
            System.out.println("  " + res + ": " + listOf(effective, res));
        }
        System.out.println("  enableSkillCommands: " + effective.enableSkillCommands);
        return 0;
    }

    private static int toggle(FileSettingsStorage storage, boolean local,
                              String resource, String value, boolean enable) {
        if (resource == null || value == null) {
            usage();
            return 1;
        }
        if (!RESOURCES.contains(resource)) {
            System.out.println("Unknown resource: " + resource + " (expected " + RESOURCES + ")");
            return 1;
        }
        var settings = local ? storage.readProject() : storage.readGlobal();
        var list = new ArrayList<>(listOf(settings, resource));
        if (enable) {
            if (!list.contains(value)) {
                list.add(value);
            }
        } else {
            list.remove(value);
        }
        setList(settings, resource, list);
        if (local) {
            storage.writeProject(settings);
        } else {
            storage.writeGlobal(settings);
        }
        System.out.println((enable ? "Enabled " : "Disabled ") + value + " for " + resource
            + (local ? " (project)" : " (global)"));
        return 0;
    }

    private static List<String> listOf(Settings settings, String resource) {
        return switch (resource) {
            case "extensions" -> settings.extensions == null ? List.of() : settings.extensions;
            case "skills" -> settings.skills == null ? List.of() : settings.skills;
            case "prompts" -> settings.prompts == null ? List.of() : settings.prompts;
            default -> settings.themes == null ? List.of() : settings.themes;
        };
    }

    private static void setList(Settings settings, String resource, List<String> values) {
        switch (resource) {
            case "extensions" -> settings.extensions = values;
            case "skills" -> settings.skills = values;
            case "prompts" -> settings.prompts = values;
            default -> settings.themes = values;
        }
    }

    private static void usage() {
        System.out.println("""
            Usage:
              pi-java config [-l]                          List resource switches
              pi-java config enable <resource> <value> [-l]  Enable a resource entry
              pi-java config disable <resource> <value> [-l] Disable a resource entry
            Resources: extensions | skills | prompts | themes
            -l  operate on the project settings (.pi-java/settings.json) instead of global""");
    }
}
