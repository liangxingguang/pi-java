package com.pijava.coding.agent.pkg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.pijava.coding.agent.core.Settings;

/**
 * pi {@code addAutoDiscoveredResources}（package-manager.ts:2413-2577）逐字
 * 对位——user/project 四目录＋{@code ~/.agents/skills}＋project 祖先
 * {@code .agents/skills}（git 根截断）；<b>project 臂过信任门</b>（pi :2478
 * 对位）。java 侧配置目录名用既定 {@code .pi-java} 翻译。
 */
public final class PackageAutoDiscovery {

    private PackageAutoDiscovery() {}

    /** pi 主函数逐字（顺序即插入序，同 rank 先到先得语义相关）。
     *  {@code homeDir} 注入（≙ pi {@code getHomeDir()}）供测试隔离真 home。 */
    public static void addAutoDiscoveredResources(ResourceAccumulator accumulator,
            Settings globalSettings, Settings projectSettings, Path globalBaseDir,
            Path projectBaseDir, Path cwd, Path homeDir, BooleanSupplier projectTrusted) {
        var userMetadata = new PathMetadata("auto", SourceScope.USER,
            PathMetadata.Origin.TOP_LEVEL, globalBaseDir.toString(), null);
        var projectMetadata = new PathMetadata("auto", SourceScope.PROJECT,
            PathMetadata.Origin.TOP_LEVEL, projectBaseDir.toString(), null);

        var userAgentsSkillsDir = homeDir.resolve(".agents").resolve("skills");
        var trusted = projectTrusted.getAsBoolean();
        var projectAgentsSkillDirs = trusted
            ? PackageResourceScanner.collectAncestorAgentsSkillDirs(cwd).stream()
                .filter(dir -> !normalize(dir).equals(normalize(userAgentsSkillsDir)))
                .toList()
            : List.<Path>of();

        if (trusted) {
            // Project extensions from .pi-java/
            addResources(accumulator, ResourceType.EXTENSIONS,
                PackageResourceScanner.collectAutoExtensionEntries(
                    projectBaseDir.resolve("extensions")),
                projectMetadata, orEmpty(projectSettings.extensions), projectBaseDir);

            // Project skills from .pi-java/
            addResources(accumulator, ResourceType.SKILLS,
                PackageResourceScanner.collectSkillEntries(
                    projectBaseDir.resolve("skills"), PackageResourceScanner.SkillMode.PI),
                projectMetadata, orEmpty(projectSettings.skills), projectBaseDir);
        }

        // Project skills from .agents/ (each with its own baseDir)
        for (var agentsSkillsDir : projectAgentsSkillDirs) {
            var agentsBaseDir = agentsSkillsDir.getParent(); // the .agents directory
            var agentsMetadata = new PathMetadata("auto", SourceScope.PROJECT,
                PathMetadata.Origin.TOP_LEVEL, agentsBaseDir.toString(), null);
            addResources(accumulator, ResourceType.SKILLS,
                PackageResourceScanner.collectSkillEntries(
                    agentsSkillsDir, PackageResourceScanner.SkillMode.AGENTS),
                agentsMetadata, orEmpty(projectSettings.skills), agentsBaseDir);
        }

        if (trusted) {
            addResources(accumulator, ResourceType.PROMPTS,
                PackageResourceScanner.collectAutoPromptEntries(projectBaseDir.resolve("prompts")),
                projectMetadata, orEmpty(projectSettings.prompts), projectBaseDir);
            addResources(accumulator, ResourceType.THEMES,
                PackageResourceScanner.collectAutoThemeEntries(projectBaseDir.resolve("themes")),
                projectMetadata, orEmpty(projectSettings.themes), projectBaseDir);
        }

        // User extensions from ~/.pi-java/agent/
        addResources(accumulator, ResourceType.EXTENSIONS,
            PackageResourceScanner.collectAutoExtensionEntries(globalBaseDir.resolve("extensions")),
            userMetadata, orEmpty(globalSettings.extensions), globalBaseDir);

        // User skills from ~/.pi-java/agent/
        addResources(accumulator, ResourceType.SKILLS,
            PackageResourceScanner.collectSkillEntries(
                globalBaseDir.resolve("skills"), PackageResourceScanner.SkillMode.PI),
            userMetadata, orEmpty(globalSettings.skills), globalBaseDir);

        // User skills from ~/.agents/ (with its own baseDir)
        var userAgentsBaseDir = userAgentsSkillsDir.getParent();
        var userAgentsMetadata = new PathMetadata("auto", SourceScope.USER,
            PathMetadata.Origin.TOP_LEVEL, userAgentsBaseDir.toString(), null);
        addResources(accumulator, ResourceType.SKILLS,
            PackageResourceScanner.collectSkillEntries(
                userAgentsSkillsDir, PackageResourceScanner.SkillMode.AGENTS),
            userAgentsMetadata, orEmpty(globalSettings.skills), userAgentsBaseDir);

        addResources(accumulator, ResourceType.PROMPTS,
            PackageResourceScanner.collectAutoPromptEntries(globalBaseDir.resolve("prompts")),
            userMetadata, orEmpty(globalSettings.prompts), globalBaseDir);
        addResources(accumulator, ResourceType.THEMES,
            PackageResourceScanner.collectAutoThemeEntries(globalBaseDir.resolve("themes")),
            userMetadata, orEmpty(globalSettings.themes), globalBaseDir);
    }

    /** pi 内部 {@code addResources} 闭包（:2469-2480）：逐路径
     *  {@code isEnabledByOverrides} 后入累加器。 */
    private static void addResources(ResourceAccumulator accumulator, ResourceType type,
            List<String> paths, PathMetadata metadata, List<String> overrides, Path baseDir) {
        for (var path : paths) {
            var enabled = ResourcePatterns.isEnabledByOverrides(path, overrides,
                baseDir.toString());
            accumulator.addResource(type, path, metadata, enabled);
        }
    }

    /** pi {@code getHomeDir}（:56-58）：{@code HOME || homedir()}。 */
    public static String getHomeDir() {
        var home = System.getenv("HOME");
        return home != null && !home.isEmpty() ? home : System.getProperty("user.home");
    }

    private static Path normalize(Path dir) {
        return dir.toAbsolutePath().normalize();
    }

    private static List<String> orEmpty(List<String> entries) {
        return entries == null ? List.of() : entries;
    }
}
