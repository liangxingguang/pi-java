package com.pijava.coding.agent.subcommand;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.extension.ExtensionPackageManager;
import com.pijava.coding.agent.extension.InstalledExtension;

/**
 * Extension package-management subcommands (Phase 3 design §9.4, P6-16).
 *
 * <p>{@code install/remove/uninstall/update/list} 操作扩展目录（全局
 * {@code ~/.pi-java/agent/extensions/} 或 {@code -l} 指定项目级）。来源可为本地 JAR
 * 路径或 http(s) URL。每个命令输出人类可读结果并返回进程退出码。</p>
 */
public final class PackageCommand {

    private static final String SELF_UPDATE_NOTE =
        "self/pi update is handled by the Maven Central release pipeline (P6-11); "
            + "download the new release jar and run `pi-java install <path>`.";

    private PackageCommand() {}

    /**
     * Dispatch a package subcommand against the default (global/project) directory.
     *
     * @param subCommand {@code install|remove|uninstall|update|list}
     * @param subArgs    tokens after the subcommand
     * @return process exit code
     */
    public static int run(String subCommand, String[] subArgs) {
        var parsed = Parsed.of(subArgs);
        var manager = parsed.local() ? ExtensionPackageManager.project()
            : ExtensionPackageManager.global();
        return run(subCommand, subArgs, manager);
    }

    /** Testable variant with an injected extension directory. */
    static int run(String subCommand, String[] subArgs, ExtensionPackageManager manager) {
        return switch (subCommand) {
            case "install" -> install(manager, subArgs);
            case "remove", "uninstall" -> remove(manager, subArgs);
            case "update" -> update(manager, subArgs);
            case "list" -> list(manager, subArgs);
            default -> {
                usage();
                yield 2;
            }
        };
    }

    private static int install(ExtensionPackageManager manager, String[] subArgs) {
        var positionals = Parsed.of(subArgs).positionals();
        if (positionals.isEmpty()) {
            System.out.println("Usage: pi-java install <path|url> [-l]");
            return 1;
        }
        var source = positionals.get(0);
        try {
            var installed = isUrl(source)
                ? manager.install(URI.create(source))
                : manager.install(Path.of(source));
            System.out.println("Installed " + describe(installed)
                + " (" + manager.dir() + ")");
            return 0;
        } catch (IllegalArgumentException e) {
            System.out.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static int remove(ExtensionPackageManager manager, String[] subArgs) {
        var positionals = Parsed.of(subArgs).positionals();
        if (positionals.isEmpty()) {
            System.out.println("Usage: pi-java remove <name|file.jar> [-l]");
            return 1;
        }
        var target = positionals.get(0);
        if (manager.remove(target)) {
            return 0;
        }
        System.out.println("No installed extension matches: " + target);
        return 1;
    }

    private static int update(ExtensionPackageManager manager, String[] subArgs) {
        var positionals = Parsed.of(subArgs).positionals();
        if (positionals.isEmpty()) {
            System.out.println("Usage: pi-java update <path|url> [-l]");
            System.out.println(SELF_UPDATE_NOTE);
            return 1;
        }
        var source = positionals.get(0);
        if ("self".equals(source) || "pi".equals(source)) {
            System.out.println(SELF_UPDATE_NOTE);
            return 0;
        }
        var result = manager.update(source);
        if (result.isEmpty()) {
            System.out.println("error: source not found: " + source);
            return 1;
        }
        System.out.println("Updated " + describe(result.get()) + " (" + manager.dir() + ")");
        return 0;
    }

    private static int list(ExtensionPackageManager manager, String[] subArgs) {
        System.out.println("Installed extensions (" + manager.dir() + "):");
        var installed = manager.list();
        if (installed.isEmpty()) {
            System.out.println("  (none — run `pi-java install <path|url>`)");
            return 0;
        }
        for (var ext : installed) {
            System.out.println("  " + describe(ext));
        }
        return 0;
    }

    private static String describe(InstalledExtension ext) {
        var builder = new StringBuilder()
            .append(ext.name());
        if (!ext.version().isBlank()) {
            builder.append(' ').append(ext.version());
        }
        if (!ext.description().isBlank()) {
            builder.append("  ").append(ext.description());
        }
        builder.append("  (").append(ext.fileName()).append(')');
        return builder.toString();
    }

    private static boolean isUrl(String source) {
        return source.startsWith("http://") || source.startsWith("https://");
    }

    private static void usage() {
        System.out.println("""
            Usage:
              pi-java install <path|url> [-l]     Install an extension JAR
              pi-java remove <name|file> [-l]     Remove an extension (uninstall = alias)
              pi-java update <path|url> [-l]      Reinstall an extension; update self|pi
              pi-java list [-l]                   List installed extensions
            -l  use the project extension dir (.pi-java/extensions) instead of the global one""");
    }

    /** 子命令参数解析：位置参数 + 可选 {@code -l}。 */
    private record Parsed(List<String> positionals, boolean local) {
        static Parsed of(String[] subArgs) {
            var positionals = new ArrayList<String>();
            var local = false;
            for (var token : subArgs) {
                if ("-l".equals(token)) {
                    local = true;
                } else if (!token.startsWith("-")) {
                    positionals.add(token);
                }
            }
            return new Parsed(List.copyOf(positionals), local);
        }
    }
}
