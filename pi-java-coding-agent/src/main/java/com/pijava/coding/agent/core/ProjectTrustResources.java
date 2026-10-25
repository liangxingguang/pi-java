package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 批 23（docs/34 T3）：pi {@code hasTrustRequiringProjectResources}
 * （trust-manager.ts:186-208 逐字）——cwd 有没有「必须经信任门」的项目本地资源。
 *
 * <p>两段判定：</p>
 * <ol>
 *   <li>cwd 一级 {@code .pi-java/}（B182 目录名约定；pi 是 {@code .pi}）下 8 条目
 *       存在性——settings.json / mcp.json / extensions / skills / prompts / themes /
 *       SYSTEM.md / APPEND_SYSTEM.md（trust-manager.ts:30-39 清单）；</li>
 *   <li>祖先链爬升查 {@code <dir>/.agents/skills}，唯一豁免
 *       {@code $HOME/.agents/skills}（规范化后字符串等值，:188-189）。</li>
 * </ol>
 */
public final class ProjectTrustResources {

    /** pi {@code TRUST_REQUIRING_PROJECT_CONFIG_RESOURCES}（trust-manager.ts:30-39 逐字序）。 */
    private static final List<String> TRUST_REQUIRING_ENTRIES = List.of(
        "settings.json", "mcp.json", "extensions", "skills", "prompts", "themes",
        "SYSTEM.md", "APPEND_SYSTEM.md");

    private ProjectTrustResources() {}

    /** pi 同名函数（trust-manager.ts:186-208）。 */
    public static boolean hasTrustRequiringProjectResources(Path cwd) {
        var homeDir = Path.of(System.getProperty("user.home"))
            .toAbsolutePath().normalize();
        var userAgentsSkillsDir = homeDir.resolve(".agents").resolve("skills");
        var currentDir = cwd.toAbsolutePath().normalize();

        var configDir = currentDir.resolve(".pi-java");
        for (var entry : TRUST_REQUIRING_ENTRIES) {
            if (Files.exists(configDir.resolve(entry))) {
                return true;
            }
        }

        while (true) {
            var agentsSkillsDir = currentDir.resolve(".agents").resolve("skills");
            if (!agentsSkillsDir.equals(userAgentsSkillsDir) && Files.exists(agentsSkillsDir)) {
                return true;
            }
            var parentDir = currentDir.getParent();
            if (parentDir == null) {
                return false;
            }
            currentDir = parentDir;
        }
    }
}
