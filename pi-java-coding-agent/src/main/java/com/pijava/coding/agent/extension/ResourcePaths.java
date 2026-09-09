package com.pijava.coding.agent.extension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话开始时扩展贡献的额外资源路径（对齐 pi {@code resources_discover} 结果）。
 *
 * <p>三类资源各有一个有序路径列表：技能（skills）、提示模板（prompts）、主题
 * （themes）。{@link #plus} 合并两个实例并去重（按路径保序），供 {@code AgentSession}
 * 在资源发现前把扩展贡献并入 CLI 参数。</p>
 *
 * @param skillPaths  额外技能目录/文件路径
 * @param promptPaths 额外提示模板目录/文件路径
 * @param themePaths  额外主题候选（.tcss 文件或内置名），启动时按序选第一个可用
 */
public record ResourcePaths(
    List<Path> skillPaths,
    List<Path> promptPaths,
    List<Path> themePaths
) {
    /** 空贡献。 */
    public static ResourcePaths none() {
        return new ResourcePaths(List.of(), List.of(), List.of());
    }

    /** 紧凑构造：防御性复制。 */
    public ResourcePaths {
        skillPaths = copy(skillPaths);
        promptPaths = copy(promptPaths);
        themePaths = copy(themePaths);
    }

    /** 合并另一实例（保序去重；本实例在前，other 在后）。 */
    public ResourcePaths plus(ResourcePaths other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        return new ResourcePaths(
            merge(skillPaths, other.skillPaths),
            merge(promptPaths, other.promptPaths),
            merge(themePaths, other.themePaths));
    }

    /** 三类路径全空。 */
    public boolean isEmpty() {
        return skillPaths.isEmpty() && promptPaths.isEmpty() && themePaths.isEmpty();
    }

    private static List<Path> copy(List<Path> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    private static List<Path> merge(List<Path> first, List<Path> second) {
        var merged = new ArrayList<>(first);
        for (var p : second) {
            if (!merged.contains(p)) {
                merged.add(p);
            }
        }
        return List.copyOf(merged);
    }
}
