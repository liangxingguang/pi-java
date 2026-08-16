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
}
