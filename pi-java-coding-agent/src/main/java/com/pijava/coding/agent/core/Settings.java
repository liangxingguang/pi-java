package com.pijava.coding.agent.core;

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
    /** API endpoint override (OpenAI/Anthropic-compatible relay); blank = provider default. */
    public String defaultBaseUrl;
    /** API key override; blank = environment variable / auth.json resolution. */
    public String defaultApiKey;
    public String transport;
    public String steeringMode;
    public String followUpMode;
    public String theme;
    public Compaction compaction;
    public Boolean hideThinkingBlock;
    public String externalEditor;
    public String shellPath;
    public String shellCommandPrefix;
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
    public String sessionBackend;
    public String httpProxy;
    public String tuiMode;
    public Tui tui;
    /** 自动重试设置（3d，docs/31 §8.22；对齐 pi {@code RetrySettings}）。 */
    public Retry retry;

    /** Unknown fields passthrough (aligned with pi's extensible settings). */
    private final Map<String, Object> unknown = new HashMap<>();

    /**
     * Capture an unrecognized field so it round-trips through JSON.
     *
     * @param key   unknown field name
     * @param value raw value
     */
    @JsonAnySetter
    public void setUnknown(String key, Object value) {
        unknown.put(key, value);
    }

    /** All unknown fields preserved from parsing, as an immutable copy. */
    @JsonAnyGetter
    public Map<String, Object> unknown() {
        return Map.copyOf(unknown);
    }

    /** Remove a legacy key from the unknown passthrough (migration). */
    public void removeUnknown(String key) {
        unknown.remove(key);
    }

    /**
     * Retry settings (JSON boundary representation) — 对齐 pi
     * {@code RetrySettings}（settings-manager.ts:41-47，3d/docs/31 §8.22）。
     * 全字段可选（≙ pi 的 {@code ?}），缺省值在消费点（{@code SettingsAccessors
     * #getRetrySettings}）按 pi 的 {@code ??} 链兜底。两环共用同一份预算：
     * post-run ① 与摘要重试。
     */
    public record Retry(
        Boolean enabled,
        Integer maxRetries,
        Long baseDelayMs,
        Long maxAgentDelayMs,
        ProviderRetry provider
    ) {}

    /**
     * pi {@code ProviderRetrySettings}（:35-38）—— SDK/provider 层的 HTTP
     * 重试形状。与上面两环<b>不同层</b>（登记清点项：↔ ai 层 {@code RetryPolicy}
     * 的映射对照）；此处仅保 JSON 往返不丢。
     */
    public record ProviderRetry(
        Long timeoutMs,
        Integer maxRetries,
        Long maxRetryDelayMs
    ) {}

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

    /**
     * TUI settings (JSON boundary representation), aligned with Codex TUI2
     * {@code tui.scroll_*} (PR #8357). Every component is optional at this
     * boundary; {@code com.pijava.tui.util.ScrollConfig.from(Settings)} falls
     * back to the documented defaults for null or invalid values.
     */
    public record Tui(
        String scrollMode,
        Integer scrollEventsPerTick,
        Integer scrollWheelLines,
        Integer scrollTrackpadLines,
        Integer scrollTrackpadAccelEvents,
        Integer scrollTrackpadAccelMax,
        Boolean scrollInvert,
        Integer scrollWheelTickDetectMaxMs,
        Integer scrollWheelLikeMaxDurationMs,
        Boolean disableMouseCapture
    ) {}
}
