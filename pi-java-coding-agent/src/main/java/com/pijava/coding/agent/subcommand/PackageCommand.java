package com.pijava.coding.agent.subcommand;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import com.pijava.ai.catalog.FileModelsStore;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.core.FileSettingsStorage;
import com.pijava.coding.agent.core.ModelCatalogRefresh;
import com.pijava.coding.agent.core.PackageManagers;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.core.TrustManager;
import com.pijava.coding.agent.pkg.ConfiguredPackage;
import com.pijava.coding.agent.pkg.PackageManager;
import com.pijava.coding.agent.pkg.ProgressEvent;
import com.pijava.coding.agent.pkg.SourceScope;

/**
 * 包管理 CLI 子命令面（批 19 T4，docs/30 §3）——pi {@code package-manager-cli.ts}
 * 的 {@code parsePackageCommand}（:397-595）＋四 handler（:978-1133）逐字对位
 * 的手写参数解析器（pi 亦非 commander/yargs）。
 *
 * <p>旧 JAR 拷贝安装面（{@code ExtensionPackageManager}）随裁决④删除——pi 的
 * http(s) URL 走 git 源解析，无 JAR 下载臂。</p>
 *
 * <p>窄差：信任面无交互询问臂，直接按已存信任判定（D114）；self-update 恒走
 * 不可用说明臂（D115，裁决②）；进度/输出无 ANSI 色（D116）。</p>
 */
public final class PackageCommand {

    /** pi 刷新臂 15s 超时（package-manager-cli.ts:607）。 */
    private static final long MODELS_REFRESH_TIMEOUT_MS = 15_000;

    private PackageCommand() {}

    /** pi {@code UpdateTarget}（:48）——变体携带不同字段 ⇒ sealed ADT。 */
    sealed interface UpdateTarget {
        record All() implements UpdateTarget {}
        record Self() implements UpdateTarget {}
        /** {@code source} 缺席（null）＝全量扩展。 */
        record Extensions(String source) implements UpdateTarget {}
        record Models() implements UpdateTarget {}
    }

    /** pi {@code PackageCommandOptions}（:262-275）。 */
    record Options(String command, String source, UpdateTarget updateTarget,
            boolean showExtensionsSkippedNote, boolean local, boolean force,
            Boolean projectTrustOverride, boolean help, String invalidOption,
            String invalidArgument, String missingOptionValue, String conflictingOptions) {}

    /** 环境注入缝（测试重定向 cwd/agentDir/信任根/模型刷新）。 */
    record CliEnv(Path cwd, Path agentDir, Path trustRoot, ModelsRefresher modelsRefresher) {
        static CliEnv system() {
            return new CliEnv(Path.of(System.getProperty("user.dir")),
                FileSettingsStorage.defaultAgentDir(), TrustManager.defaultRoot(),
                PackageCommand::refreshModelCatalogs);
        }
    }

    /** pi {@code refreshModelCatalogs}（:605-631）的结果语义：null＝成功，否则错误消息。 */
    interface ModelsRefresher {
        String refresh(Path agentDir);
    }

    /** Dispatch a package subcommand against the process environment. */
    public static int run(String subCommand, String[] subArgs) {
        return run(subCommand, subArgs, CliEnv.system());
    }

    /** Testable variant with injected environment. */
    static int run(String subCommand, String[] subArgs, CliEnv env) {
        var options = parse(subCommand, subArgs);
        if (options == null) {
            return 2;
        }
        if (options.help()) {
            PackageCommandHelp.print(options.command());
            return 0;
        }
        var usage = PackageCommandHelp.usage(options.command());
        if (options.invalidOption() != null) {
            System.err.println("Unknown option " + options.invalidOption()
                + " for \"" + options.command() + "\".");
            System.err.println("Use \"pi-java --help\" or \"" + usage + "\".");
            return 1;
        }
        if (options.missingOptionValue() != null) {
            System.err.println("Missing value for " + options.missingOptionValue() + ".");
            System.err.println("Usage: " + usage);
            return 1;
        }
        if (options.invalidArgument() != null) {
            System.err.println("Unexpected argument " + options.invalidArgument() + ".");
            System.err.println("Usage: " + usage);
            return 1;
        }
        if (options.conflictingOptions() != null) {
            System.err.println(options.conflictingOptions());
            System.err.println("Usage: " + usage);
            return 1;
        }
        if ((options.command().equals("install") || options.command().equals("remove"))
            && options.source() == null) {
            System.err.println("Missing " + options.command() + " source.");
            System.err.println("Usage: " + usage);
            return 1;
        }

        // pi :940-949：--models 短路在 settings/trust 加载之前。
        if ("update".equals(options.command()) && options.updateTarget() instanceof UpdateTarget.Models) {
            var error = env.modelsRefresher().refresh(env.agentDir());
            if (error != null) {
                System.err.println("Error: " + error);
                return 1;
            }
            System.out.println("Model catalogs refreshed");
            return 0;
        }

        var settings = SettingsManager.load(env.agentDir(), env.cwd(),
            options.projectTrustOverride());
        if (options.projectTrustOverride() == null) {
            // D114：非交互 CLI 无询问臂，直接按已存信任（TrustManager 标记）判定。
            settings.setProjectTrusted(new TrustManager(env.trustRoot(),
                settings.effective().defaultProjectTrust).isTrusted(env.cwd()));
        }
        var writesProjectPackageConfig =
            ("install".equals(options.command()) || "remove".equals(options.command()))
                && options.local();
        if (!settings.isProjectTrusted() && writesProjectPackageConfig) {
            // pi :963 逐字。
            System.err.println(
                "Project is not trusted. Use --approve to modify local package config.");
            return 1;
        }

        var packageManager = PackageManagers.create(env.cwd(), env.agentDir(), settings);
        // pi :972-976 逐字：只消费 start 型，plain 输出（D116）。
        packageManager.setProgressCallback(event -> {
            if (event.type() == ProgressEvent.Type.START) {
                System.out.println(event.message());
            }
        });

        try {
            return switch (options.command()) {
                case "install" -> install(packageManager, options);
                case "remove" -> remove(packageManager, options);
                case "list" -> list(packageManager);
                default -> update(packageManager, options);
            };
        } catch (RuntimeException e) {
            // pi :1135-1139：Error: <message>，UncheckedIOException 解包保文案。
            var message = e instanceof UncheckedIOException uio && uio.getCause() != null
                ? uio.getCause().getMessage() : e.getMessage();
            System.err.println("Error: " + (message != null ? message : "Unknown package command error"));
            return 1;
        }
    }

    // ── handler（pi :978-1133）───────────────────────────────────

    private static int install(PackageManager pm, Options options) {
        pm.installAndPersist(options.source(), options.local());
        System.out.println("Installed " + options.source());
        return 0;
    }

    private static int remove(PackageManager pm, Options options) {
        if (!pm.removeAndPersist(options.source(), options.local())) {
            System.err.println("No matching package found for " + options.source());
            return 1;
        }
        System.out.println("Removed " + options.source());
        return 0;
    }

    private static int list(PackageManager pm) {
        var configured = pm.listConfiguredPackages();
        var user = configured.stream().filter(p -> p.scope() == SourceScope.USER).toList();
        var project = configured.stream().filter(p -> p.scope() == SourceScope.PROJECT).toList();
        if (configured.isEmpty()) {
            System.out.println("No packages installed.");
            return 0;
        }
        if (!user.isEmpty()) {
            System.out.println("User packages:");
            user.forEach(PackageCommand::printPackage);
        }
        if (!project.isEmpty()) {
            if (!user.isEmpty()) {
                System.out.println();
            }
            System.out.println("Project packages:");
            project.forEach(PackageCommand::printPackage);
        }
        return 0;
    }

    private static void printPackage(ConfiguredPackage pkg) {
        // pi :1006-1012 逐字（两行式：源＋(filtered) 标记；dim 四格缩进路径）。
        System.out.println("  " + pkg.source() + (pkg.filtered() ? " (filtered)" : ""));
        if (pkg.installedPath() != null) {
            System.out.println("    " + pkg.installedPath());
        }
    }

    private static int update(PackageManager pm, Options options) {
        var target = options.updateTarget() != null ? options.updateTarget() : new UpdateTarget.Self();
        if (options.showExtensionsSkippedNote()) {
            System.out.println("Extensions are skipped. Run pi-java update --extensions "
                + "to update extensions.");
        }
        if (target instanceof UpdateTarget.All || target instanceof UpdateTarget.Extensions) {
            var updateSource = target instanceof UpdateTarget.Extensions e ? e.source() : null;
            pm.update(updateSource);
            System.out.println(updateSource != null ? "Updated " + updateSource : "Updated packages");
        }
        if (target instanceof UpdateTarget.All || target instanceof UpdateTarget.Self) {
            // 裁决②（D115）：java 无 npm/managed 安装形态 ⇒ 恒走 pi unknown 臂。
            printSelfUpdateUnavailable();
            return 1;
        }
        return 0;
    }

    /** pi {@code printSelfUpdateUnavailable}（:633-645）对位；说明按 Maven Central
     *  安装形态改写（裁决②），{@code SELF_UPDATE_NOTE} 语义保留升级。 */
    private static void printSelfUpdateUnavailable() {
        System.err.println("error: pi-java cannot self-update this installation.");
        System.err.println("pi-java is distributed via Maven Central. Download the new "
            + "release and run it in place of the current jar.");
        var location = executableLocation();
        if (location != null) {
            System.err.println();
            System.err.println("Location of pi-java executable: " + location);
        }
    }

    private static String executableLocation() {
        try {
            var source = PackageCommand.class.getProtectionDomain().getCodeSource();
            return source == null ? null : Path.of(source.getLocation().toURI()).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** pi {@code refreshModelCatalogs}（:605-631）：逐 provider 强制重拉（绕过 TTL），
     *  15s 超时／错误聚合文案逐字。 */
    private static String refreshModelCatalogs(Path agentDir) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() ->
                new ModelCatalogRefresh(FileModelsStore.defaultStore())
                    .refresh(ProviderRegistry.create().listAll(), true, true));
            try {
                var result = future.get(MODELS_REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (result.hasErrors()) {
                    return result.errors().entrySet().stream()
                        .map(e -> e.getKey() + ": " + e.getValue().getMessage())
                        .collect(Collectors.joining("; ",
                            "Could not refresh model catalogs: ", ""));
                }
                return null;
            } catch (TimeoutException e) {
                future.cancel(true);
                return "Model catalog refresh timed out.";
            } catch (Exception e) {
                var cause = e.getCause() != null ? e.getCause() : e;
                return cause.getMessage() != null ? cause.getMessage()
                    : "Unknown model catalog refresh error";
            }
        }
    }

    // ── 解析器（pi :397-595 逐字）─────────────────────────────────

    static Options parse(String rawCommand, String[] rest) {
        var command = switch (rawCommand) {
            case "uninstall" -> "remove";
            case "install", "remove", "update", "list" -> rawCommand;
            default -> null;
        };
        if (command == null) {
            return null;
        }

        var local = false;
        var force = false;
        Boolean projectTrustOverride = null;
        var help = false;
        String invalidOption = null;
        String invalidArgument = null;
        String missingOptionValue = null;
        String conflictingOptions = null;
        String source = null;
        var selfFlag = false;
        var extensionsFlag = false;
        var modelsFlag = false;
        var allFlag = false;
        String extensionFlagSource = null;

        for (int index = 0; index < rest.length; index++) {
            var arg = rest[index];
            if ("-h".equals(arg) || "--help".equals(arg)) {
                help = true;
                continue;
            }
            if ("-l".equals(arg) || "--local".equals(arg)) {
                if ("install".equals(command) || "remove".equals(command)) {
                    local = true;
                } else {
                    invalidOption = invalidOption != null ? invalidOption : arg;
                }
                continue;
            }
            if ("--self".equals(arg)) {
                if ("update".equals(command)) {
                    selfFlag = true;
                } else {
                    invalidOption = invalidOption != null ? invalidOption : arg;
                }
                continue;
            }
            if ("--extensions".equals(arg)) {
                if ("update".equals(command)) {
                    extensionsFlag = true;
                } else {
                    invalidOption = invalidOption != null ? invalidOption : arg;
                }
                continue;
            }
            if ("--models".equals(arg)) {
                if ("update".equals(command)) {
                    modelsFlag = true;
                } else {
                    invalidOption = invalidOption != null ? invalidOption : arg;
                }
                continue;
            }
            if ("--all".equals(arg)) {
                if ("update".equals(command)) {
                    allFlag = true;
                } else {
                    invalidOption = invalidOption != null ? invalidOption : arg;
                }
                continue;
            }
            if ("--approve".equals(arg) || "-a".equals(arg)) {
                projectTrustOverride = Boolean.TRUE;
                continue;
            }
            if ("--no-approve".equals(arg) || "-na".equals(arg)) {
                projectTrustOverride = Boolean.FALSE;
                continue;
            }
            if ("--force".equals(arg)) {
                if ("update".equals(command)) {
                    force = true;
                } else {
                    invalidOption = invalidOption != null ? invalidOption : arg;
                }
                continue;
            }
            if ("--extension".equals(arg)) {
                if (!"update".equals(command)) {
                    invalidOption = invalidOption != null ? invalidOption : arg;
                    continue;
                }
                var value = index + 1 < rest.length ? rest[index + 1] : null;
                if (value == null || value.startsWith("-")) {
                    missingOptionValue = missingOptionValue != null ? missingOptionValue : arg;
                } else if (extensionFlagSource != null) {
                    conflictingOptions = conflictingOptions != null ? conflictingOptions
                        : "--extension can only be provided once";
                    index++;
                } else {
                    extensionFlagSource = value;
                    index++;
                }
                continue;
            }
            if (arg.startsWith("-")) {
                invalidOption = invalidOption != null ? invalidOption : arg;
                continue;
            }
            if (source == null) {
                source = arg;
            } else {
                invalidArgument = invalidArgument != null ? invalidArgument : arg;
            }
        }

        // update 目标归约（pi :526-579 逐字）。
        UpdateTarget updateTarget = null;
        var showExtensionsSkippedNote = false;
        if ("update".equals(command)) {
            if (allFlag && (selfFlag || extensionsFlag || modelsFlag || extensionFlagSource != null)) {
                conflictingOptions = conflictingOptions != null ? conflictingOptions
                    : "--all cannot be combined with --self, --extensions, --models, or --extension";
            }
            if (allFlag && source != null) {
                conflictingOptions = conflictingOptions != null ? conflictingOptions
                    : "--all cannot be combined with a positional source";
            }
            if (modelsFlag) {
                if (selfFlag || extensionsFlag || allFlag || extensionFlagSource != null) {
                    conflictingOptions = conflictingOptions != null ? conflictingOptions
                        : "--models cannot be combined with --self, --extensions, --all, or --extension";
                }
                if (source != null) {
                    conflictingOptions = conflictingOptions != null ? conflictingOptions
                        : "--models cannot be combined with a positional source";
                }
                updateTarget = new UpdateTarget.Models();
            } else if (extensionFlagSource != null) {
                if (selfFlag || extensionsFlag || allFlag) {
                    conflictingOptions = conflictingOptions != null ? conflictingOptions
                        : "--extension cannot be combined with --self, --extensions, or --all";
                }
                if (source != null) {
                    conflictingOptions = conflictingOptions != null ? conflictingOptions
                        : "--extension cannot be combined with a positional source";
                }
                updateTarget = new UpdateTarget.Extensions(extensionFlagSource);
            } else if (source != null) {
                var sourceIsSelf = "self".equals(source) || "pi".equals(source);
                if (sourceIsSelf) {
                    updateTarget = extensionsFlag ? new UpdateTarget.All() : new UpdateTarget.Self();
                } else {
                    if (extensionsFlag || selfFlag || allFlag) {
                        conflictingOptions = conflictingOptions != null ? conflictingOptions
                            : "positional update targets cannot be combined with "
                                + "--self, --extensions, or --all";
                    }
                    updateTarget = new UpdateTarget.Extensions(source);
                }
            } else if (allFlag) {
                updateTarget = new UpdateTarget.All();
            } else if (selfFlag && extensionsFlag) {
                updateTarget = new UpdateTarget.All();
            } else if (selfFlag) {
                updateTarget = new UpdateTarget.Self();
            } else if (extensionsFlag) {
                updateTarget = new UpdateTarget.Extensions(null);
            } else {
                updateTarget = new UpdateTarget.Self();
                showExtensionsSkippedNote = true;
            }
        }

        return new Options(command, source, updateTarget, showExtensionsSkippedNote,
            local, force, projectTrustOverride, help, invalidOption, invalidArgument,
            missingOptionValue, conflictingOptions);
    }
}
