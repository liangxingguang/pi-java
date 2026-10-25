package com.pijava.coding.agent.core;

import java.util.LinkedHashSet;

/**
 * 批 16（T5b，docs/27）：pi {@code AgentSession.reload()}（agent-session.ts:3660-3701）
 * 的 java 对位。从 {@link AgentSession} 抽出以控制文件行数（CLAUDE.md ≤500 行约束）。
 *
 * <p>七步序（与 pi 逐行对位）：</p>
 * <ol>
 *   <li>{@code session_shutdown{reload}}（firer，hasHandlers 门逐字；pi 随后
 *       invalidate 旧 runner——java 不重建扩展运行时，窄差 D90 裁决③）；</li>
 *   <li>settings 重载<b>前</b>快照 defaultTools（{@code usesDefaultTools=false}
 *       ⇒ 空集）；</li>
 *   <li>{@code settingsManager.reload()}＋{@code syncQueueModesFromSettings}
 *       （steering/followUp 模式随设置）；</li>
 *   <li>provider 重置（裁决④：车道构造期烘焙 settings ⇒ 清内置重注册、快照保留
 *       扩展项；pi {@code resetApiProviders} ai/compat.ts:209-213 对位）；</li>
 *   <li>资源重扫：skills/prompts 整表替换重扫（pi {@code resourceLoader.reload()}
 *       对位）；themes 批 21 落地（重算 themePaths＋重装载，注册表/控制器在
 *       模式层收尾——裁决⑩）；context files 是晚读口恒新（无须动作）；</li>
 *   <li>defaultTools diff（B246 三分支）：新增进 defaultTools 的 ⇒ 激活；从中移除的
 *       ⇒ 保持激活（不动）；会话中 {@code -name} 移除的 ⇒ 修饰符照旧 apply 到新
 *       设置上，保持移除。{@code usesDefaultTools=false} ⇒ 整臂跳过；</li>
 *   <li>{@code session_start{reload}}＋resources_discover 重放（pi 的
 *       hasBindings 门不移植——恒发，窄差 D93 裁决⑫）。</li>
 * </ol>
 *
 * <p>flag 值跨 reload 存活：java 不重建扩展运行时 ⇒ 共享值表天然存活（pi 的
 * 快照回灌是重建的产物，净效果相同，docs/27 T5b 注记）。pi reload 的
 * {@code _pendingToolNames} 回填臂不移植（java 无 pending 概念，批 1 窄差 D4 同族）。</p>
 */
final class SessionReload {

    private SessionReload() {}

    static void reload(AgentSession session) {
        // 批 17（T7，docs/28 §3-T7.2）：pi handleReloadCommand:6440 对位——
        // resetExtensionUI 在真正 reload 之前（pi 的两道门〔流式/压缩中〕在调用方
        // 命令层，java 已有同门，本批不动）。七步序首步之**前**。
        session.extensionUI().onSessionInvalidated();
        // ① session_shutdown{reload}（pi :3662-3664；firer 的 hasHandlers 门逐字）。
        var firer = session.extensionFirer();
        if (firer != null) {
            firer.sessionShutdown("reload", null);
        }
        var settings = session.services().settings();
        var selection = session.toolSelection();
        // ② settings 重载前快照 defaultTools（pi :3665-3672）。
        var previousDefaultTools = new LinkedHashSet<>(resolvedDefaultTools(session));
        // ③ 设置重载＋队列模式随设置（pi :3673-3675 syncQueueModesFromSettings）。
        settings.reload();
        var effective = settings.effective();
        session.harness().setQueueModes(
            SessionSetup.queueMode(effective.steeringMode),
            SessionSetup.queueMode(effective.followUpMode));
        // ④ provider 重置（裁决④；pi resetApiProviders :3676 对位）。
        DefaultProviders.resetProviders(session.services().providers(),
            effective.catalogBaseUrl, session.extensionProviderNames());
        // ⑤ 资源重扫：skills/prompts 整表替换（pi resourceLoader.reload() :3677 对位；
        //    扩展臂在 ⑦ 随 resources_discover 重放叠加回来）。批 18 评审 M4：
        //    reload 单遍 resolve（pi 同）——两臂共享一份装配结果。
        var assembly = SessionSetup.resolveAll(session.sessionArgs(), settings);
        session.harness().skillManager().replaceAll(
            SessionSetup.discoverSkills(session.sessionArgs(), assembly).values());
        session.services().promptTemplates().replaceAll(
            SessionSetup.loadPromptTemplates(session.sessionArgs(), assembly));
        // 批 21（T8，原批 16 裁决⑨挂账）：themes 臂——重算 themePaths（resolve
        // 重扫的 themes 条目）并重装载（pi interactive-mode.ts:6490 的
        // setRegisteredThemes 由模式层在 reload 收尾经 Themes 写入——裁决⑩：
        // 会话层只重算 owner.themes，不碰全局注册表）。
        var reloadCli = SessionSetup.mergePaths(
            SessionSetup.cliThemePaths(session.sessionArgs()),
            SessionSetup.enabledPaths(assembly.cli().themes()));
        var reloadThemesPaths = session.sessionArgs().noThemes()
            ? reloadCli
            : SessionSetup.mergePaths(reloadCli,
                SessionSetup.enabledPaths(assembly.resolved().themes()));
        session.setThemePathsForReload(reloadThemesPaths);
        // ⑥ defaultTools diff（B246；pi :3678-3685）：新增 ⇒ 经注册表过滤并入
        //    activeTools；移除的保持激活（不动）；usesDefaultTools=false ⇒ 跳过。
        if (selection != null && selection.usesDefaultTools()) {
            var added = resolvedDefaultTools(session).stream()
                .filter(name -> !previousDefaultTools.contains(name))
                .toList();
            if (!added.isEmpty()) {
                session.harness().activateTools(added);
            }
        }
        // ⑦ session_start{reload}＋resources_discover 重放（pi :3686-3699；hasBindings
        //    门不移植——恒发恒重放，窄差 D93 裁决⑫）。
        if (firer != null) {
            firer.sessionStart("reload", null);
        }
        var manager = session.extensionManager();
        if (manager != null) {
            AgentSession.applySessionStartResources(session.harness(), session.services(),
                manager.sessionStartResources(), session.sessionArgs(), session);
        }
        // 批 21（T8）：resources_discover 重放可能追加扩展 themePaths ⇒ 重装载
        //（pi setExtensionPaths → updateThemesFromPaths 重跑对位）。
        session.reloadThemes();
        // 批 21（T8，裁决⑩）：模式层收尾口（pi interactive-mode.ts:6490-6492
        // 的 setRegisteredThemes＋applyFromSettings 由监听者完成）。
        session.fireReloaded();
    }

    /**
     * pi reload 内的 {@code getDefaultTools()}（agent-session.ts:3665-3669）：
     * {@code usesDefaultTools=false ⇒ 空集}；否则
     * {@code applyToolModifiers(settings.defaultTools ?? DEFAULT_TOOL_NAMES, modifiers)}。
     */
    private static java.util.List<String> resolvedDefaultTools(AgentSession session) {
        var selection = session.toolSelection();
        if (selection == null || !selection.usesDefaultTools()) {
            return java.util.List.of();
        }
        var configured = session.services().settings().accessors().getDefaultTools();
        return ToolSelection.applyToolModifiers(
            configured != null ? configured : ToolSelection.DEFAULT_TOOL_NAMES,
            selection.defaultToolModifiers());
    }
}
