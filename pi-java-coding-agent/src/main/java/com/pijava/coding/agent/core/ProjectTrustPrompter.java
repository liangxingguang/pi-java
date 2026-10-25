package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.List;

/**
 * 批 23（docs/34 T3）：启动信任弹窗缝——pi {@code selectProjectTrustOption}
 * （project-trust.ts:36-44，{@code ctx.ui.select} 走 startup selector）的 java 对位。
 *
 * <p>core 不依赖 TUI；TUI 侧实现走启动壳（批 14 的 SessionSelectorRunner 同
 * niche）。非 TUI 模式 / 测试道恒 {@code null}（＝pi 无 UI 臂）。</p>
 */
@FunctionalInterface
public interface ProjectTrustPrompter {

    /**
     * 弹「信任此项目？」选项。
     *
     * @param cwd     项目目录
     * @param options pi 五选项（{@link ProjectTrustOption#of}）
     * @return 选中项；取消 ⇒ {@code null}（pi select 返 undefined ⇒ 不信任）
     */
    ProjectTrustOption prompt(Path cwd, List<ProjectTrustOption> options);
}
