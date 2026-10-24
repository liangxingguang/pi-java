package com.pijava.coding.agent.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Settings root object, aligned with pi's {@code Settings} interface
 * (Phase 3 design §12.1).
 *
 * <p>Top-level fields keep pi's camelCase JSON names (Jackson maps them
 * directly). Enum-like values ({@code theme}, {@code steeringMode}, …) are
 * stored as strings at this JSON boundary and mapped to strong types at
 * consumption points. Unknown fields are preserved via
 * {@link #setUnknown}/{@link #unknown} so future pi fields round-trip without
 * loss.</p>
 */
public final class Settings {

    // ── Top-level fields (Phase 3 core subset) ───────────────
    public String defaultProvider;
    public String defaultModel;
    public String defaultThinkingLevel;
    /** pi {@code modelThinkingLevels}（settings-schema.ts:213-217）：per-model 默认
     *  思考档覆盖，键形 {@code "provider/modelId"}。 */
    public Map<String, String> modelThinkingLevels;
    /** API endpoint override (OpenAI/Anthropic-compatible relay); blank = provider default. */
    public String defaultBaseUrl;
    /** API key override; blank = environment variable / auth.json resolution. */
    public String defaultApiKey;
    public String transport;
    public String steeringMode;
    public String followUpMode;
    public String theme;
    public Compaction compaction;
    /** pi {@code BranchSummarySettingsSchema}（settings-schema.ts:29-42）。 */
    public BranchSummary branchSummary;
    public Boolean hideThinkingBlock;
    public String externalEditor;
    public String shellPath;
    public String shellCommandPrefix;
    public Boolean quietStartup;
    public String defaultProjectTrust;
    /** pi {@code packages}（settings-schema.ts:295-299，元素形 {@code PackageSourceSchema}
     *  :170-190）：npm/git/本地包源表，string（全量装载）或 object（资源过滤）。 */
    public List<PackageSource> packages;
    /** pi {@code npmCommand}（settings-schema.ts:268-273）：npm 查找/安装命令的
     *  argv 形（如 {@code ["mise","exec","node@20","--","npm"]}）。HTTP 实现下不消费
     *  命令本体（窄差 D104）。 */
    public List<String> npmCommand;
    public List<String> extensions;
    public List<String> skills;
    public List<String> prompts;
    public List<String> themes;
    public Boolean enableSkillCommands;
    public Terminal terminal;
    public Image images;
    public List<String> enabledModels;
    /**
     * pi {@code defaultTools}（settings-schema.ts:326-331）：初始工具选择——裸名替换
     * 继承选择，{@code +name}/{@code -name} 增删。分层 merge 特判与解析见
     * {@link ToolSelection#mergeDefaultTools}/{@link ToolSelection#resolveDefaultTools}。
     */
    public List<String> defaultTools;
    public String doubleEscapeAction;
    public String treeFilterMode;
    public Integer editorPaddingX;
    public Integer outputPad;
    public Integer autocompleteMaxVisible;
    public Markdown markdown;
    public String sessionDir;
    public String sessionBackend;
    public String httpProxy;
    /** pi {@code httpIdleTimeoutMs}（settings-schema.ts:390-394）：number | "disabled"
     *  联合 ⇒ 边界原样保类型（同 {@link Terminal#hyperlinks} 先例）。 */
    public Object httpIdleTimeoutMs;
    /** pi {@code websocketConnectTimeoutMs}（settings-schema.ts:402-406）：number |
     *  "disabled" 联合，同 {@link #httpIdleTimeoutMs} 边界处理。 */
    public Object websocketConnectTimeoutMs;
    /** pi {@code deviceId}（settings-schema.ts:302-307）：稳定安装 UUID，首次认证需要
     *  时生成；仅全局域。 */
    public String deviceId;
    /** 远程模型目录的根 URL（原 docs/70 R7）；null/空 ⇒ {@code https://pi.dev}。 */
    public String catalogBaseUrl;
    public String tuiMode;
    public Tui tui;
    /** 自动重试设置（3d，原 docs/31 §8.22；对齐 pi {@code RetrySettings}）。 */
    public Retry retry;
    /**
     * B128：每级思考的自定义 token 预算（pi {@code settings-schema.ts:121-133}，
     * 四段全 optional、<b>无默认值</b>；缺席 ⇒ {@code null} ⇒ ai 侧默认表）。
     */
    public ThinkingBudgets thinkingBudgets;

    // ── 批 11 声明面（pi settings-schema.ts 行号逐键钉注）──────────────
    /** pi :209。changelog 流 java 暂无（声明面）。 */
    public String lastChangelogVersion;
    /** pi :239-244。 */
    public Boolean showCacheMissNotices;
    /** pi :274-279。 */
    public Boolean collapseChangelog;
    /** pi :280-285。 */
    public Boolean enableInstallTelemetry;
    /** pi :286-291。 */
    public Boolean enableAnalytics;
    /** pi :292-294。 */
    public String trackingId;
    /** pi :380（WarningSettingsSchema :144-147）。 */
    public Warnings warnings;
    /** pi :395-401（off|streaming|idle；仅全局域生效——accessor 只读全局）。 */
    public String cacheWarming;
    /** pi :376-378。 */
    public Boolean showHardwareCursor;
    /** pi :412-417（transcript|resume-hint）。 */
    public String fullscreenExitOutput;
    /** pi :418-423（auto|always|hidden）。 */
    public String fullscreenScrollbar;
    /** pi :424-429。 */
    public Boolean fullscreenCopyOnSelect;
    /** pi :430-436（number | "auto" 联合）。 */
    public Object fullscreenWheelScrollLines;

    /** pi {@code WarningSettingsSchema}（settings-schema.ts:144-147）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Warnings(
        @JsonProperty("anthropicExtraUsage") Boolean anthropicExtraUsage
    ) {}

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

    /**
     * All unknown fields preserved from parsing, as an immutable view.
     * Deliberately <em>not</em> {@code Map.copyOf}: an unrecognized field may
     * legitimately carry JSON {@code null} — e.g. a key written by a newer
     * build that an older binary has no field for — and the point of this map
     * is to round-trip such fields without loss or a load-time NPE.
     */
    @JsonAnyGetter
    public Map<String, Object> unknown() {
        return java.util.Collections.unmodifiableMap(new HashMap<>(unknown));
    }

    /** Remove a legacy key from the unknown passthrough (migration). */
    public void removeUnknown(String key) {
        unknown.remove(key);
    }

    /**
     * Retry settings (JSON boundary representation) — 对齐 pi
     * {@code RetrySettings}（settings-manager.ts:41-47，3d/原 docs/31 §8.22）。
     * 全字段可选（≙ pi 的 {@code ?}），缺省值在消费点（{@code SettingsAccessors
     * #getRetrySettings}）按 pi 的 {@code ??} 链兜底。两环共用同一份预算：
     * post-run ① 与摘要重试。
     */
    public record Retry(
        // @JsonProperty：Json mapper 关了 getter 可见性，record 访问器会被当作
        // getter 忽略（实测 valueToTree 得 {}）⇒ 显式钉住组件的 JSON 属性。
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("maxRetries") Integer maxRetries,
        @JsonProperty("baseDelayMs") Long baseDelayMs,
        @JsonProperty("maxAgentDelayMs") Long maxAgentDelayMs,
        @JsonProperty("provider") ProviderRetry provider
    ) {}

    /**
     * pi {@code ProviderRetrySettings}（:35-38）—— SDK/provider 层的 HTTP
     * 重试形状。与上面两环<b>不同层</b>（登记清点项：↔ ai 层 {@code RetryPolicy}
     * 的映射对照）；此处仅保 JSON 往返不丢。
     */
    public record ProviderRetry(
        @JsonProperty("timeoutMs") Long timeoutMs,
        @JsonProperty("maxRetries") Integer maxRetries,
        @JsonProperty("maxRetryDelayMs") Long maxRetryDelayMs
    ) {}

    /**
     * Compaction settings (JSON boundary representation)，对齐 pi
     * {@code CompactionSettings}（settings-manager.ts:28-34）。全组件可选
     * （≙ pi 的 {@code ?}），默认值在消费点按 pi 的 {@code ??} 链兜底。
     * 类级 NON_NULL ⇒ 缺席组件不出现在 JSON 里（pi 只写已修改子键）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Compaction(
        // @JsonProperty：Json mapper 关了 getter 可见性，record 访问器会被当作
        // getter 忽略（实测 valueToTree 得 {}）⇒ 显式钉住组件的 JSON 属性。
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("reserveTokens") Integer reserveTokens,
        @JsonProperty("keepRecentTokens") Integer keepRecentTokens,
        @JsonProperty("modelOverrides") Map<String, ModelOverride> modelOverrides
    ) {
        /** pi {@code CompactionModelOverride}（settings-manager.ts:18-21）。 */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record ModelOverride(
            @JsonProperty("reserveTokens") Integer reserveTokens,
            @JsonProperty("keepRecentTokens") Integer keepRecentTokens
        ) {}
    }

    /**
     * pi {@code BranchSummarySettingsSchema}（settings-schema.ts:29-42）。与压缩的
     * 同名 reserve <b>无关</b> —— 独立键 {@code branchSummary.reserveTokens}
     * （defaults 同值 16384，settings-defaults.ts:14；skipPrompt 默认 false :15）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BranchSummary(
        @JsonProperty("reserveTokens") Integer reserveTokens,
        @JsonProperty("skipPrompt") Boolean skipPrompt
    ) {}

    /**
     * Terminal settings (JSON boundary representation)，对齐 pi
     * {@code TerminalSettings}（settings-manager.ts:57-65）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Terminal(
        @JsonProperty("showImages") Boolean showImages,
        @JsonProperty("imageWidthCells") Integer imageWidthCells,
        @JsonProperty("clearOnShrink") Boolean clearOnShrink,
        @JsonProperty("showTerminalProgress") Boolean showTerminalProgress,
        // pi 形状＝ boolean | "auto"；本边界原样保类型，消费点再判。
        @JsonProperty("hyperlinks") Object hyperlinks,
        // pi 形状＝ "kitty" | "iterm2" | "auto" | false。
        @JsonProperty("images") Object images,
        // pi 形状＝ boolean | "auto"。
        @JsonProperty("trueColor") Object trueColor
    ) {}

    /** Image settings (JSON boundary representation)，对齐 pi
     *  {@code ImageSettings}（settings-manager.ts:67-71）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Image(
        @JsonProperty("autoResize") Boolean autoResize,
        @JsonProperty("blockImages") Boolean blockImages
    ) {}

    /** Markdown settings (JSON boundary representation)，对齐 pi
     *  {@code MarkdownSettings}（settings-manager.ts:85-90）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Markdown(
        @JsonProperty("codeBlockIndent") String codeBlockIndent,
        @JsonProperty("mermaid") String mermaid
    ) {}

    /**
     * Thinking token budgets (JSON boundary representation)，对齐 pi
     * {@code ThinkingBudgets}（settings-schema.ts:121-133）。只有 {@code minimal/low/
     * medium/high} 四段，全 optional 无默认（schema 只验 number，不做范围校验 ⇒
     * 负数也原样上线，服务端拒就拒）。类级 NON_NULL ⇒ 未配置的段不写回 JSON。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ThinkingBudgets {
        public Integer minimal;
        public Integer low;
        public Integer medium;
        public Integer high;
    }

    /**
     * TUI settings (JSON boundary representation) —— <b>pi-java 自有扩展</b>
     * （pi 锚点无此块；源自 Codex TUI2 PR #8357）。消费者＝
     * {@code com.pijava.tui.util.ScrollConfig.from(Settings)}。全组件可选，
     * null/非法值由 ScrollConfig 兜底为默认。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Tui(
        @JsonProperty("scrollMode") String scrollMode,
        @JsonProperty("scrollEventsPerTick") Integer scrollEventsPerTick,
        @JsonProperty("scrollWheelLines") Integer scrollWheelLines,
        @JsonProperty("scrollTrackpadLines") Integer scrollTrackpadLines,
        @JsonProperty("scrollTrackpadAccelEvents") Integer scrollTrackpadAccelEvents,
        @JsonProperty("scrollTrackpadAccelMax") Integer scrollTrackpadAccelMax,
        @JsonProperty("scrollInvert") Boolean scrollInvert,
        @JsonProperty("scrollWheelTickDetectMaxMs") Integer scrollWheelTickDetectMaxMs,
        @JsonProperty("scrollWheelLikeMaxDurationMs") Integer scrollWheelLikeMaxDurationMs,
        @JsonProperty("disableMouseCapture") Boolean disableMouseCapture
    ) {}
}
