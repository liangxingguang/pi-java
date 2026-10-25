package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.function.Consumer;

import com.pijava.coding.agent.extension.SessionEventFirer;

/**
 * 批 23（docs/34 T3）：pi {@code resolveProjectTrusted}（project-trust.ts:47-97）
 * 的七级优先级链逐字：
 *
 * <ol>
 *   <li>{@code trustOverride} 显式覆盖 ⇒ 直接用；</li>
 *   <li>无信任敏感资源 ⇒ 恒 true（不问）；</li>
 *   <li>扩展 {@code project_trust} 事件（有 result 即定夺；{@code remember=true}
 *       落 store；错误经 {@code onExtensionError} 上报后继续）；</li>
 *   <li>trustStore 已存决策（最近祖先继承）；</li>
 *   <li>{@code defaultProjectTrust}（always⇒true / never⇒false / ask⇒继续）；</li>
 *   <li>无 UI ⇒ false；</li>
 *   <li>弹窗（选中才写回 store，取消 ⇒ false）。</li>
 * </ol>
 */
public final class ProjectTrustResolver {

    private ProjectTrustResolver() {}

    /** pi {@code SETTINGS_DEFAULTS.defaultProjectTrust}（settings-defaults.ts:29）。 */
    public static final String DEFAULT_ASK = "ask";

    /**
     * 解析项目信任。
     *
     * @param cwd                 项目目录
     * @param trustOverride       {@code --approve}/{@code --no-approve}（null ≙ 未给）
     * @param store               trust.json 存储
     * @param defaultProjectTrust 全局默认（非法/缺席 ⇒ "ask"，pi
     *                            settings-manager.ts:993-994 归一）
     * @param firer               预信任扩展事件发射器（null ⇒ 跳过第 ③ 级，
     *                            例如 {@code --no-extensions}）
     * @param hasUi               有无对话级 UI（pi projectTrustContext.hasUI）
     * @param prompter            弹窗缝（null 且 hasUi ⇒ 取消臂 ⇒ false）
     * @param onExtensionError    扩展错误上报（pi project-trust.ts:60-63 文案）
     */
    public static boolean resolve(Path cwd, Boolean trustOverride,
                                  ProjectTrustStore store, String defaultProjectTrust,
                                  SessionEventFirer firer, boolean hasUi,
                                  ProjectTrustPrompter prompter,
                                  Consumer<String> onExtensionError) {
        // ① 显式覆盖
        if (trustOverride != null) {
            return trustOverride;
        }
        // ② 无敏感资源恒 true
        if (!ProjectTrustResources.hasTrustRequiringProjectResources(cwd)) {
            return true;
        }
        // ③ 扩展 project_trust 事件
        if (firer != null) {
            var outcome = firer.projectTrust(ProjectTrustStore.normalizeCwd(cwd));
            for (var error : outcome.errors()) {
                onExtensionError.accept("Extension \"" + error.extensionName()
                    + "\" project_trust error: " + error.message());
            }
            if (outcome.result().isPresent()) {
                var result = outcome.result().get();
                var trusted = result.trusted()
                    == com.pijava.coding.agent.extension.event.ExtensionResults
                        .TrustDecision.YES;
                if (Boolean.TRUE.equals(result.remember())) {
                    store.set(cwd, trusted);
                }
                return trusted;
            }
        }
        // ④ 已存决策（最近祖先）
        var decision = store.get(cwd);
        if (decision != null) {
            return decision;
        }
        // ⑤ 全局默认
        switch (normalizeDefault(defaultProjectTrust)) {
            case "always":
                return true;
            case "never":
                return false;
            default:
                break; // ask ⇒ 继续
        }
        // ⑥ 无 UI 恒 false
        if (!hasUi) {
            return false;
        }
        // ⑦ 弹窗（选中才写回；取消返 false）
        var selected = prompter == null ? null
            : prompter.prompt(cwd, ProjectTrustOption.of(cwd, true));
        if (selected != null) {
            if (!selected.updates().isEmpty()) {
                store.setMany(selected.updates());
            }
            return selected.trusted();
        }
        return false;
    }

    /** pi {@code getDefaultProjectTrust} 归一（settings-manager.ts:993-994）。 */
    public static String normalizeDefault(String value) {
        return "always".equals(value) || "never".equals(value) ? value : DEFAULT_ASK;
    }
}
