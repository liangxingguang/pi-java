package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * Settings root object, aligned with pi's {@code Settings} interface
 * (Phase 3 design §12.1).
 *
 * <p>Fields keep pi's snake_case JSON names (Jackson maps them directly).
 * Enum-like values ({@code theme}, {@code steeringMode}, …) are stored as
 * strings at this JSON boundary and mapped to strong types at consumption
 * points. Unknown fields are preserved via {@link #setUnknown}/{@link #unknown}
 * so future pi fields round-trip without loss.</p>
 */
public final class Settings {

    // ── Top-level fields (Phase 3 core subset) ───────────────
    public String defaultProvider;
    public String defaultModel;
    public String defaultThinkingLevel;
    public String transport;
    public String steeringMode;
    public String followUpMode;
    public String theme;
    public Compaction compaction;
    public Boolean hideThinkingBlock;
    public String externalEditor;
    public String shellPath;
    public Boolean quietStartup;
    public String defaultProjectTrust;
    public List<String> extensions;
    public List<String> skills;
    public List<String> prompts;
    public List<String> themes;
    public Boolean enableSkillCommands;
    public Terminal terminal;
    public Image images;
    public List<String> enabledModels;
    public String doubleEscapeAction;
    public String treeFilterMode;
    public Integer editorPaddingX;
    public Integer outputPad;
    public Integer autocompleteMaxVisible;
    public Markdown markdown;
    public String sessionDir;
    public String httpProxy;
    public String tuiMode;

    /** Unknown fields passthrough (aligned with pi's extensible settings). */
    private final Map<String, Object> unknown = new HashMap<>();

    @JsonAnySetter
    public void setUnknown(String key, Object value) {
        unknown.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> unknown() {
        return Map.copyOf(unknown);
    }

    /** Copy constructor used by the merge logic. */
    public Settings copy() {
        var copy = new Settings();
        copy.defaultProvider = defaultProvider;
        copy.defaultModel = defaultModel;
        copy.defaultThinkingLevel = defaultThinkingLevel;
        copy.transport = transport;
        copy.steeringMode = steeringMode;
        copy.followUpMode = followUpMode;
        copy.theme = theme;
        copy.compaction = compaction;
        copy.hideThinkingBlock = hideThinkingBlock;
        copy.externalEditor = externalEditor;
        copy.shellPath = shellPath;
        copy.quietStartup = quietStartup;
        copy.defaultProjectTrust = defaultProjectTrust;
        copy.extensions = extensions == null ? null : new ArrayList<>(extensions);
        copy.skills = skills == null ? null : new ArrayList<>(skills);
        copy.prompts = prompts == null ? null : new ArrayList<>(prompts);
        copy.themes = themes == null ? null : new ArrayList<>(themes);
        copy.enableSkillCommands = enableSkillCommands;
        copy.terminal = terminal;
        copy.images = images;
        copy.enabledModels = enabledModels == null ? null : new ArrayList<>(enabledModels);
        copy.doubleEscapeAction = doubleEscapeAction;
        copy.treeFilterMode = treeFilterMode;
        copy.editorPaddingX = editorPaddingX;
        copy.outputPad = outputPad;
        copy.autocompleteMaxVisible = autocompleteMaxVisible;
        copy.markdown = markdown;
        copy.sessionDir = sessionDir;
        copy.httpProxy = httpProxy;
        copy.tuiMode = tuiMode;
        copy.unknown.putAll(unknown);
        return copy;
    }

    /** Compaction settings (JSON boundary representation). */
    public record Compaction(
        boolean enabled,
        int reserveTokens,
        int keepRecentTokens
    ) {}

    /** Terminal settings (JSON boundary representation). */
    public record Terminal(
        boolean showImages,
        int imageWidthCells
    ) {}

    /** Image settings (JSON boundary representation). */
    public record Image(
        boolean autoResize,
        boolean blockImages
    ) {}

    /** Markdown settings (JSON boundary representation). */
    public record Markdown(
        int codeBlockIndent,
        boolean mermaid
    ) {}
}
