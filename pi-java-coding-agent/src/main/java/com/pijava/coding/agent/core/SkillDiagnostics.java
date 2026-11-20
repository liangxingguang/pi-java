package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.skill.ResourceDiagnostic;

/**
 * docs/65（L7 W5，pi {@code interactive-mode.ts:1820-1871} {@code formatDiagnostics}
 * ＋ {@code :2015-2023} 的 {@code [Skill warnings]} 区段）——技能加载诊断的文本化。
 *
 * <p>两类行形逐字：</p>
 * <pre>
 *   "name" collision:
 *     ✓ /path/to/winner.md
 *     ✗ /path/to/loser.md (skipped)
 *   /path/to/file.md
 *     message
 * </pre>
 *
 * <p>pi 在 winner/loser 行尾还带 {@code (source)}（技能来源包）；Java 的
 * {@link ResourceDiagnostic.Collision} 没有 source 字段，故省略（D273）。</p>
 */
public final class SkillDiagnostics {

    /** 区段标题（pi {@code :2016}，warning 色；Java 走 dim 系统文本，D83 同族）。 */
    public static final String SKILL_WARNINGS_TITLE = "[Skill warnings]";

    private SkillDiagnostics() {}

    /**
     * pi {@code formatDiagnostics}：逐条展开成行（不折叠空诊断）。
     *
     * @param diagnostics 技能加载诊断（顺序保持）
     * @return 行列表（空入参 ⇒ 空表）
     */
    public static List<String> format(List<ResourceDiagnostic> diagnostics) {
        var out = new ArrayList<String>();
        if (diagnostics == null) {
            return out;
        }
        for (var diagnostic : diagnostics) {
            var collision = diagnostic.collision();
            if (collision != null) {
                out.add("  \"" + collision.name() + "\" collision:");
                out.add("    \u2713 " + collision.winnerPath());
                out.add("    \u2717 " + collision.loserPath() + " (skipped)");
                continue;
            }
            var path = diagnostic.filePath();
            if (path != null && !path.isBlank()) {
                out.add("  " + path);
            }
            out.add("    " + diagnostic.message());
        }
        return List.copyOf(out);
    }

    /** 防御性拷贝（{@code null} ⇒ 空表）；供会话装配面回填。 */
    public static List<String> copy(List<String> lines) {
        return lines == null ? List.of() : List.copyOf(lines);
    }

    /** 区段文本（标题＋诊断行）；无诊断 ⇒ {@code null}（pi 不出区段）。 */
    public static String block(List<ResourceDiagnostic> diagnostics) {
        var lines = format(diagnostics);
        if (lines.isEmpty()) {
            return null;
        }
        return SKILL_WARNINGS_TITLE + "\n" + String.join("\n", lines);
    }
}
