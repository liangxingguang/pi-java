package com.pijava.coding.agent.core;

import java.util.List;
import java.util.OptionalInt;

/**
 * Per-field settings getters/setters (Phase 3 design §12.2).
 *
 * <p>Getters read the merged view; setters write to the global scope, mark
 * the field modified, and rely on {@link SettingsManager#effective()} to
 * re-merge. CLI flags never write back through this class (CLI &gt; settings).</p>
 */
public final class SettingsAccessors {

    private final SettingsManager manager;

    SettingsAccessors(SettingsManager manager) {
        this.manager = manager;
    }

    public String getDefaultProvider() {
        return manager.effective().defaultProvider;
    }

    /** Set the default provider in the global scope. */
    public void setDefaultProvider(String provider) {
        manager.global().defaultProvider = provider;
        manager.markModified("defaultProvider");
    }

    public String getDefaultModel() {
        return manager.effective().defaultModel;
    }

    /** Set the default model in the global scope. */
    public void setDefaultModel(String model) {
        manager.global().defaultModel = model;
        manager.markModified("defaultModel");
    }

    public String getDefaultThinkingLevel() {
        return manager.effective().defaultThinkingLevel;
    }

    /** Set the default thinking level in the global scope. */
    public void setDefaultThinkingLevel(String level) {
        manager.global().defaultThinkingLevel = level;
        manager.markModified("defaultThinkingLevel");
    }

    /** pi {@code getModelThinkingLevel}（:768-770）：合并视图查 {@code provider/modelId}。 */
    public String getModelThinkingLevel(String provider, String modelId) {
        var levels = manager.effective().modelThinkingLevels;
        return levels == null ? null : levels.get(provider + "/" + modelId);
    }

    /** pi {@code getAllModelThinkingLevels}（:772-774）：浅拷贝，缺席 ⇒ 空表。 */
    public java.util.Map<String, String> getAllModelThinkingLevels() {
        var levels = manager.effective().modelThinkingLevels;
        return levels == null ? java.util.Map.of() : java.util.Map.copyOf(levels);
    }

    /** pi {@code setModelThinkingLevel}（:776-783）：写全局域。java 惯例 markModified
     *  （TUI 调用方统一 flush；pi 的 save 对位在调用方，同 B244）。 */
    public void setModelThinkingLevel(String provider, String modelId, String level) {
        var global = manager.global();
        if (global.modelThinkingLevels == null) {
            global.modelThinkingLevels = new java.util.LinkedHashMap<>();
        }
        global.modelThinkingLevels.put(provider + "/" + modelId, level);
        manager.markModified("modelThinkingLevels");
    }

    /** pi {@code removeModelThinkingLevel}（:785-793）：删空后整键抹除（JSON 不出空表）。 */
    public void removeModelThinkingLevel(String provider, String modelId) {
        var global = manager.global();
        if (global.modelThinkingLevels == null) {
            return;
        }
        global.modelThinkingLevels.remove(provider + "/" + modelId);
        if (global.modelThinkingLevels.isEmpty()) {
            global.modelThinkingLevels = null;
        }
        manager.markModified("modelThinkingLevels");
    }

    /**
     * pi {@code getThemeSetting}（settings-manager.ts:741-745）：原样返回
     * {@code theme} 设置串（可为 {@code "light/dark"} 自动模式对）。
     */
    public String getThemeSetting() {
        return manager.effective().theme;
    }

    /**
     * pi {@code getTheme}（settings-manager.ts:747-750）：含 {@code /} 的对形
     * ⇒ {@code null}（对形不经此口直读，走 ThemeSettings.resolveThemeSetting）。
     */
    public String getTheme() {
        var theme = getThemeSetting();
        return theme != null && theme.contains("/") ? null : theme;
    }

    /** Set the theme in the global scope. */
    public void setTheme(String theme) {
        manager.global().theme = theme;
        manager.markModified("theme");
    }

    public String getSteeringMode() {
        return manager.effective().steeringMode;
    }

    /** Set the steering mode in the global scope. */
    public void setSteeringMode(String mode) {
        manager.global().steeringMode = mode;
        manager.markModified("steeringMode");
    }

    public String getFollowUpMode() {
        return manager.effective().followUpMode;
    }

    /** Set the follow-up mode in the global scope. */
    public void setFollowUpMode(String mode) {
        manager.global().followUpMode = mode;
        manager.markModified("followUpMode");
    }

    /** pi {@code getPackages}（settings-manager.ts:1076-1078）：合并视图防御性
     *  拷贝，缺席 ⇒ 空表（pi {@code ?? []} 对位）。 */
    public List<PackageSource> getPackages() {
        var packages = manager.effective().packages;
        return packages == null ? List.of() : List.copyOf(packages);
    }

    /** pi {@code getNpmCommand}（settings-manager.ts:1013-1015）：防御性拷贝；
     *  缺席 ⇒ {@code null}（≙ pi undefined）。 */
    public List<String> getNpmCommand() {
        var command = manager.effective().npmCommand;
        return command == null ? null : List.copyOf(command);
    }

    public Boolean getQuietStartup() {
        return manager.effective().quietStartup;
    }

    /** Set the quiet-startup flag in the global scope. */
    public void setQuietStartup(Boolean quiet) {
        manager.global().quietStartup = quiet;
        manager.markModified("quietStartup");
    }

    /**
     * B244（pi {@code getEnableSkillCommands}，settings-manager.ts:1156-1158）：
     * 是否把技能注册为 {@code /skill:name} 补全命令；缺省回落 pi 默认
     * {@code true}（settings-defaults.ts:33）。
     */
    public boolean getEnableSkillCommands() {
        var value = manager.effective().enableSkillCommands;
        return value == null || value;
    }

    /**
     * B244（pi {@code setEnableSkillCommands}，settings-manager.ts:1160-1164）：
     * 写全局域＋标脏（pi 的 {@code save()} 对位在调用方 ——
     * {@code SettingsScreen.cycle} 统一 {@code flush()}）。
     */
    public void setEnableSkillCommands(Boolean enabled) {
        manager.global().enableSkillCommands = enabled;
        manager.markModified("enableSkillCommands");
    }

    public String getDefaultProjectTrust() {
        return manager.effective().defaultProjectTrust;
    }

    /** Set the default project trust in the global scope. */
    public void setDefaultProjectTrust(String trust) {
        manager.global().defaultProjectTrust = trust;
        manager.markModified("defaultProjectTrust");
    }

    /** The enabled models (empty list when unset), as an immutable copy. */
    public List<String> getEnabledModels() {
        var value = manager.effective().enabledModels;
        return value == null ? List.of() : List.copyOf(value);
    }

    /** Set the enabled models in the global scope. */
    public void setEnabledModels(List<String> models) {
        manager.global().enabledModels = List.copyOf(models);
        manager.markModified("enabledModels");
    }

    /**
     * pi {@code getDefaultTools}（settings-manager.ts:1328-1333）：解析后的
     * {@code defaultTools} 选择；无任何层设置 ⇒ {@code null}（≙ pi undefined ⇒ 消费点
     * 回落 {@link ToolSelection#DEFAULT_TOOL_NAMES}）。
     */
    public List<String> getDefaultTools() {
        var tools = manager.effective().defaultTools;
        // 批 24（D3）：显式 JSON null ⇒ Jackson 绑成 NullNode——与缺席同视
        //（pi undefined 对位），否则容忍读：非数组 ⇒ 空选择，非字符串成员滤掉
        if (tools == null || tools.isNull()) {
            return null;
        }
        return ToolSelection.resolveDefaultTools(ToolSelection.normalizeDefaultTools(tools));
    }

    public String getTuiMode() {
        return manager.effective().tuiMode;
    }

    /** Set the TUI mode in the global scope. */
    public void setTuiMode(String mode) {
        manager.global().tuiMode = mode;
        manager.markModified("tuiMode");
    }

    public String getShellPath() {
        return manager.effective().shellPath;
    }

    /** Set the shell path in the global scope. */
    public void setShellPath(String path) {
        manager.global().shellPath = path;
        manager.markModified("shellPath");
    }

    public String getShellCommandPrefix() {
        return manager.effective().shellCommandPrefix;
    }

    /** Set the shell command prefix in the global scope. */
    public void setShellCommandPrefix(String prefix) {
        manager.global().shellCommandPrefix = prefix;
        manager.markModified("shellCommandPrefix");
    }

    public String getExternalEditor() {
        return manager.effective().externalEditor;
    }

    /**
     * 批 12 T3（pi {@code getExternalEditorCommand}，settings-manager.ts:946-956
     * 逐字回落链）：配置非空白 ⇒ 配置；否则 {@code VISUAL || EDITOR}；否则平台默认
     * （Windows {@code notepad}，其余 {@code nano}）。
     */
    public String getExternalEditorCommand() {
        return externalEditorCommand(System::getenv);
    }

    /** 环境变量注入形态（测试面；pi 的 {@code process.env} 对位）。 */
    String externalEditorCommand(java.util.function.Function<String, String> env) {
        var configured = manager.effective().externalEditor;
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        var fromEnv = env.apply("VISUAL");
        if (fromEnv == null || fromEnv.isEmpty()) {
            fromEnv = env.apply("EDITOR");
        }
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }
        return isWindows() ? "notepad" : "nano";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** Set the external editor command in the global scope. */
    public void setExternalEditor(String editor) {
        manager.global().externalEditor = editor;
        manager.markModified("externalEditor");
    }

    public Boolean getHideThinkingBlock() {
        return manager.effective().hideThinkingBlock;
    }

    /** Set whether thinking blocks are hidden in the global scope. */
    public void setHideThinkingBlock(Boolean hide) {
        manager.global().hideThinkingBlock = hide;
        manager.markModified("hideThinkingBlock");
    }

    public String getTreeFilterMode() {
        return manager.effective().treeFilterMode;
    }

    /** Set the tree filter mode in the global scope. */
    public void setTreeFilterMode(String mode) {
        manager.global().treeFilterMode = mode;
        manager.markModified("treeFilterMode");
    }

    /**
     * 批 12 T2（pi {@code getDoubleEscapeAction}，settings-manager.ts:1341-1343）：
     * 非法/缺席 ⇒ 默认 {@code "tree"}（settings-defaults.ts:44）。
     */
    public String getDoubleEscapeAction() {
        var value = manager.effective().doubleEscapeAction;
        return "fork".equals(value) || "none".equals(value) ? value : "tree";
    }

    /** Set the action bound to double-Escape in the global scope. */
    public void setDoubleEscapeAction(String action) {
        manager.global().doubleEscapeAction = action;
        manager.markModified("doubleEscapeAction");
    }

    /**
     * 自动重试开关（pi {@code getRetryEnabled}，settings-manager.ts:914-916：
     * {@code retry?.enabled ?? true} —— 默认开）。
     */
    public boolean getRetryEnabled() {
        var retry = manager.effective().retry;
        return retry == null || retry.enabled() == null || retry.enabled();
    }

    /**
     * 两环共用的重试设置（pi {@code getRetrySettings}，:927-933 的 {@code ??} 链：
     * {@code enabled=true / maxRetries=3 / baseDelayMs=2000 /
     * maxAgentDelayMs=60_000}）。每次调用现读合并视图 —— pi 也是每决策现读
     * {@code getRetrySettings()}，不缓存。
     */
    public com.pijava.agent.harness.RetrySettings getRetrySettings() {
        var retry = manager.effective().retry;
        return new com.pijava.agent.harness.RetrySettings(
            getRetryEnabled(),
            retry == null || retry.maxRetries() == null ? 3 : retry.maxRetries(),
            retry == null || retry.baseDelayMs() == null ? 2_000 : retry.baseDelayMs(),
            retry == null || retry.maxAgentDelayMs() == null
                ? 60_000L : retry.maxAgentDelayMs());
    }

    /**
     * 写全局 {@code retry.enabled}（record 不可变 ⇒ 拷贝重建；其余字段原样保留）。
     * pi {@code setRetryEnabled}（:918-924）＝ 全局改写 + markModified + save ——
     * 与其余仅 markModified（close 时统一 flush）的 setter 不同，这里<b>即时</b>
     * flush，对齐 pi 的 {@code save()}。
     */
    /**
     * Provider/SDK 层重试设置（pi {@code getProviderRetrySettings}，
     * settings-manager.ts:966-972：timeoutMs/maxRetries 无默认（null ⇒ 缺席 ⇒
     * 0），maxRetryDelayMs ?? 60000）。
     */
    public ProviderRetrySettings getProviderRetrySettings() {
        var provider = manager.effective().retry == null
            ? null : manager.effective().retry.provider();
        return new ProviderRetrySettings(
            provider == null ? null : provider.timeoutMs(),
            provider == null ? null : provider.maxRetries(),
            provider == null || provider.maxRetryDelayMs() == null
                ? 60_000L : provider.maxRetryDelayMs());
    }

    /** pi {@code getProviderRetrySettings} 返回形状。 */
    public record ProviderRetrySettings(
            Long timeoutMs, Integer maxRetries, long maxRetryDelayMs) {}

    /**
     * B128：pi {@code getThinkingBudgets}（settings-manager.ts:1166-1168）。缺席 ⇒
     * {@code null}（≙ pi 的 {@code undefined}，ai 侧按默认表浅合并）；逐段转换，
     * 未配置的段落 {@link OptionalInt#empty()}。不做范围校验（pi schema 只验 number）。
     */
    public com.pijava.ai.thinking.ThinkingBudgets getThinkingBudgets() {
        var b = manager.effective().thinkingBudgets;
        if (b == null) {
            return null;
        }
        return new com.pijava.ai.thinking.ThinkingBudgets(
            b.minimal == null ? OptionalInt.empty() : OptionalInt.of(b.minimal),
            b.low == null ? OptionalInt.empty() : OptionalInt.of(b.low),
            b.medium == null ? OptionalInt.empty() : OptionalInt.of(b.medium),
            b.high == null ? OptionalInt.empty() : OptionalInt.of(b.high));
    }

    public void setRetryEnabled(boolean enabled) {
        var global = manager.global();
        var retry = global.retry;
        global.retry = retry == null
            ? new Settings.Retry(enabled, null, null, null, null)
            : new Settings.Retry(enabled, retry.maxRetries(), retry.baseDelayMs(),
                retry.maxAgentDelayMs(), retry.provider());
        manager.markModified("retry");
        manager.flush();
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
     * 与 {@link #setRetryEnabled} 同款。引擎晚读口在下次决策自然读到新值。
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
