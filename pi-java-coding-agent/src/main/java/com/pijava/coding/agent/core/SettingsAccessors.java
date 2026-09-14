package com.pijava.coding.agent.core;

import java.util.List;

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

    public String getTheme() {
        return manager.effective().theme;
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

    public Boolean getQuietStartup() {
        return manager.effective().quietStartup;
    }

    /** Set the quiet-startup flag in the global scope. */
    public void setQuietStartup(Boolean quiet) {
        manager.global().quietStartup = quiet;
        manager.markModified("quietStartup");
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

    public String getDoubleEscapeAction() {
        return manager.effective().doubleEscapeAction;
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
}
