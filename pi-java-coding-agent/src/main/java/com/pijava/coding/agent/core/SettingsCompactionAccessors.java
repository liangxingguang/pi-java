package com.pijava.coding.agent.core;

/**
 * Compaction / branch-summary / images / markdown 面设置访问器（结构债拆分：
 * {@link SettingsAccessors} 的 B223 块整块迁出，行为零变化；与
 * {@link SettingsNetworkAccessors} 等兄弟类同款，经 {@link SettingsManager#compaction()} 访问）。
 */
public final class SettingsCompactionAccessors {

    private final SettingsManager manager;

    SettingsCompactionAccessors(SettingsManager manager) {
        this.manager = manager;
    }

    // ── B223（原 docs/47）：compaction 设置 ──────────────────────────────

    /**
     * 自动压缩主开关（pi {@code getCompactionEnabled}，settings-manager.ts:914-916：
     * {@code compaction?.enabled ?? true} —— 默认<b>开</b>）。
     */
    public boolean getCompactionEnabled() {
        var c = manager.effective().compaction;
        return c == null || c.enabled() == null || c.enabled();
    }

    /**
     * pi {@code getCompactionSettings(model)}（:964-974）：每个压缩决策点现读合并
     * 视图，不缓存。预算三级回退 = {@code modelOverrides["provider/modelId"]}
     * 精确命中 → ordinary 字段 → 16384/20000（:23-26）。{@code model} 为 null 时
     * 不查 override（pi 的 model 缺席形状）。负数在解析点抛 pi 逐字异常。
     */
    public com.pijava.agent.compaction.CompactionSettings getCompactionSettings(
            com.pijava.ai.model.ModelId<?> model) {
        var c = manager.effective().compaction;
        return new com.pijava.agent.compaction.CompactionSettings(
            getCompactionEnabled(),
            compactionToken(c, model, true),
            compactionToken(c, model, false));
    }

    /** pi {@code settings-defaults.ts:14}：branchSummary.reserveTokens 默认值。 */
    private static final int BRANCH_SUMMARY_RESERVE_DEFAULT = 16_384;

    /**
     * pi {@code branchSummary.reserveTokens} 访问器（settings-manager.ts:867-876
     * 同族）：独立键，与 {@code compaction.reserveTokens} 无关（默认值同为 16384）。
     */
    public int getBranchSummaryReserveTokens() {
        var b = manager.effective().branchSummary;
        return b == null || b.reserveTokens() == null
            ? BRANCH_SUMMARY_RESERVE_DEFAULT : b.reserveTokens();
    }

    /** pi {@code branchSummary.skipPrompt}（settings-defaults.ts:15：默认 false）。 */
    public boolean getBranchSummarySkipPrompt() {
        var b = manager.effective().branchSummary;
        return b != null && Boolean.TRUE.equals(b.skipPrompt());
    }

    /** pi {@code getBlockImages}（settings-manager.ts:1311-1313）：
     *  {@code images?.blockImages ?? false}。 */
    public boolean getBlockImages() {
        var images = manager.effective().images;
        return images != null && Boolean.TRUE.equals(images.blockImages());
    }

    /**
     * pi {@code getCodeBlockIndent}（settings-manager.ts:1403-1405 逐字回落）：
     * {@code markdown?.codeBlockIndent ?? "  "}（SETTINGS_DEFAULTS）。消费点＝
     * tui {@code MarkdownTheme} 注入缝（批 25/docs/36 T7，E2-43）。
     */
    public String getMarkdownCodeBlockIndent() {
        var md = manager.effective().markdown;
        return md != null && md.codeBlockIndent() != null ? md.codeBlockIndent() : "  ";
    }

    /**
     * pi {@code getMermaidRenderingMode}（settings-manager.ts:1407-1410 逐字）：
     * {@code "off"/"final"} 直传，其余（含缺省）⇒ {@code "streaming"}。
     * <b>accessor 补上不消费</b>（mermaid 渲染本体＝R7 未落；E2-43 半键挂起）。
     */
    public String getMarkdownMermaid() {
        var md = manager.effective().markdown;
        var mode = md == null ? null : md.mermaid();
        return "off".equals(mode) || "final".equals(mode) ? mode : "streaming";
    }

    /** pi {@code getCompactionTokenSetting}（:927-953）的单字段三级回退 + 校验。 */
    private static int compactionToken(Settings.Compaction c,
                                        com.pijava.ai.model.ModelId<?> model,
                                        boolean reserve) {
        String field = reserve ? "reserveTokens" : "keepRecentTokens";
        int dflt = reserve ? 16_384 : 20_000;
        Integer ordinary = c == null
            ? null
            : reserve ? c.reserveTokens() : c.keepRecentTokens();
        if (ordinary != null && ordinary < 0) {
            // pi :935-938 逐字（Integer 必为安全整数，safe-integer 校验类型层已成立）。
            throw new IllegalArgumentException(
                "Invalid compaction." + field + " setting: " + ordinary
                    + ". Expected a non-negative safe integer.");
        }
        Settings.Compaction.ModelOverride entry = null;
        if (c != null && c.modelOverrides() != null && model != null) {
            entry = c.modelOverrides().get(model.provider() + "/" + model.modelName());
        }
        Integer override = entry == null
            ? null
            : reserve ? entry.reserveTokens() : entry.keepRecentTokens();
        if (override != null && override < 0) {
            // pi :947-950 逐字（含精确键名；field 名在 pi 串里不带引号）。
            throw new IllegalArgumentException(
                "Invalid compaction.modelOverrides[\""
                    + model.provider() + "/" + model.modelName() + "\"]." + field
                    + " setting: " + override
                    + ". Expected a non-negative safe integer.");
        }
        return override != null ? override : ordinary != null ? ordinary : dflt;
    }

    /**
     * 写全局 {@code compaction.enabled}（pi {@code setCompactionEnabled}，:918-924）：
     * 全局改写（保留其余组件与 modelOverrides）+ markModified + <b>即时</b> flush，
     * 与 {@link SettingsAccessors#setRetryEnabled} 同款。引擎晚读口在下次决策自然读到新值。
     */
    public void setCompactionEnabled(boolean enabled) {
        var global = manager.global();
        var c = global.compaction;
        global.compaction = c == null
            ? new Settings.Compaction(enabled, null, null, null)
            : new Settings.Compaction(enabled, c.reserveTokens(), c.keepRecentTokens(),
                c.modelOverrides());
        manager.markModified("compaction");
        manager.flush();
    }
}
