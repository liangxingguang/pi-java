package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 批 23（docs/34 T3）：pi {@code ProjectTrustOption}（trust-manager.ts:23-28）＋
 * {@code getProjectTrustOptions}（:67-97 逐字序）——启动信任弹窗的选项模型。
 *
 * @param label    选项文案（逐字）
 * @param trusted  选中后的信任值
 * @param updates  落盘动作序列（空＝session-only 不落盘）；{@code decision == null}
 *                 语义＝删该键（「Trust parent folder」同时删 cwd 自身条目）
 * @param savedPath 持久化型选项携带的目标路径（session-only 两项没有）
 */
public record ProjectTrustOption(String label, boolean trusted,
                                 List<ProjectTrustStore.Update> updates,
                                 String savedPath) {

    /**
     * pi {@code getProjectTrustOptions(cwd, {includeSessionOnly})} 逐字：
     * Trust →（有父目录）Trust parent folder →（sessionOnly）Trust (this session
     * only) → Do not trust →（sessionOnly）Do not trust (this session only)。
     */
    public static List<ProjectTrustOption> of(Path cwd, boolean includeSessionOnly) {
        var trustPath = ProjectTrustStore.normalizeCwd(cwd);
        var options = new ArrayList<ProjectTrustOption>();
        options.add(new ProjectTrustOption("Trust", true,
            List.of(new ProjectTrustStore.Update(trustPath, Boolean.TRUE)), trustPath));
        var parent = Path.of(trustPath).getParent();
        if (parent != null) {
            var parentPath = parent.toString();
            options.add(new ProjectTrustOption(
                "Trust parent folder (" + parentPath + ")", true,
                List.of(new ProjectTrustStore.Update(parentPath, Boolean.TRUE),
                    new ProjectTrustStore.Update(trustPath, null)),
                parentPath));
        }
        if (includeSessionOnly) {
            options.add(new ProjectTrustOption("Trust (this session only)", true,
                List.of(), null));
        }
        options.add(new ProjectTrustOption("Do not trust", false,
            List.of(new ProjectTrustStore.Update(trustPath, Boolean.FALSE)), trustPath));
        if (includeSessionOnly) {
            options.add(new ProjectTrustOption("Do not trust (this session only)", false,
                List.of(), null));
        }
        return List.copyOf(options);
    }
}
