package com.pijava.coding.agent.subcommand;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.pijava.coding.agent.core.SettingsManager;

/**
 * L5 W4（B277b）：{@code pi-java update} 的自更新面——pi
 * {@code getConfiguredSelfUpdateArgv}（config.ts:341-346）、
 * {@code makeConfiguredSelfUpdateCommand}（:349-359）与
 * {@code printSelfUpdateUnavailable}（package-manager-cli.ts:633-645）对位。
 *
 * <p>从 {@link PackageCommand} 顶出以守住文件 ≤500 行闸门（行为零变化）。
 * 环境变量 {@code PI_SELF_UPDATE_COMMAND} 优先于全局 {@code selfUpdateCommand}
 * setting；两者皆无 ⇒ 不可用说明臂＋退出码 1。</p>
 *
 * <p>【模型可见】环境变量名、占位符名与指引行逐字 pi（品牌改 {@code pi-java}）。</p>
 */
final class SelfUpdate {

    /** pi 的品牌名占位符取值（pi 为 {@code @mariozechner/pi-coding-agent}）。 */
    static final String PACKAGE_NAME = "pi-java";

    private SelfUpdate() {
    }

    /**
     * {@code update}/`update self` 臂的完整执行：有配置命令 ⇒ 跑它并透传退出码；
     * 无配置 ⇒ 不可用说明。
     *
     * @param settings 全局/项目设置（读 {@code selfUpdateCommand}）
     * @return 进程退出码
     */
    static int run(SettingsManager settings) {
        var configured = configuredSelfUpdateArgv(
            settings.accessors().getSelfUpdateCommand(),
            System.getenv("PI_SELF_UPDATE_COMMAND"));
        if (configured != null) {
            return runCommand(substitutePlaceholders(configured,
                com.pijava.coding.agent.cli.Version.VERSION));
        }
        printUnavailable();
        return 1;
    }

    /**
     * pi {@code getConfiguredSelfUpdateArgv}（config.ts:341-346）：env 值（空白切分，
     * 不解析引号）优先；否则全局 {@code selfUpdateCommand}。
     *
     * @return argv；两源皆空 ⇒ {@code null}
     */
    static List<String> configuredSelfUpdateArgv(List<String> setting, String envValue) {
        if (envValue != null && !envValue.trim().isEmpty()) {
            return List.of(envValue.trim().split("\\s+"));
        }
        return setting != null && !setting.isEmpty() ? List.copyOf(setting) : null;
    }

    /** pi {@code makeConfiguredSelfUpdateCommand}（config.ts:349-359）：三占位符替换。 */
    static List<String> substitutePlaceholders(List<String> argv, String version) {
        var installSpec = PACKAGE_NAME + "@" + version;
        return argv.stream()
            .map(arg -> arg
                .replace("{packageName}", PACKAGE_NAME)
                .replace("{version}", version)
                .replace("{installSpec}", installSpec))
            .toList();
    }

    /** 执行配置命令（继承 stdout/stderr），退出码透传。 */
    private static int runCommand(List<String> argv) {
        try {
            return new ProcessBuilder(argv).inheritIO().start().waitFor();
        } catch (IOException e) {
            System.err.println("Error: " + (e.getMessage() != null
                ? e.getMessage() : "Failed to run self-update command"));
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    /** pi {@code printSelfUpdateUnavailable}（:633-645）对位；说明按 Maven Central
     *  安装形态改写（裁决②），{@code SELF_UPDATE_NOTE} 语义保留升级。 */
    private static void printUnavailable() {
        System.err.println("error: pi-java cannot self-update this installation.");
        System.err.println("pi-java is distributed via Maven Central. Download the new "
            + "release and run it in place of the current jar.");
        var location = executableLocation();
        if (location != null) {
            System.err.println();
            System.err.println("Location of pi-java executable: " + location);
        }
        // pi :645 指引行（品牌 pi-java）。
        System.err.println("To make `pi-java update` run your own update command, set "
            + "PI_SELF_UPDATE_COMMAND or the selfUpdateCommand setting.");
    }

    private static String executableLocation() {
        try {
            var source = SelfUpdate.class.getProtectionDomain().getCodeSource();
            return source == null ? null : Path.of(source.getLocation().toURI()).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
