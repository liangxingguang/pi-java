package com.pijava.ai.provider.builtin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.CatalogCompatRules;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * OpenRouter chat 目录（包 A-02，docs/59 §4.3）—— 转录自 pi 的生成数据
 * {@code providers/data/openrouter.json}（2026-09-29 快照）。
 *
 * <p><b>anthropic 车道 14 条全量</b>：不是数据洁癖 —— 未入目录的
 * {@code openrouter/anthropic/*} 会退化成 {@code ModelInfo.minimal}（api 缺席 ⇒
 * 走 provider 默认的 completions 协议 ⇒ <b>发错车道</b>）。每条 {@code api} 组件
 * 写 {@code "anthropic-messages"}（pi 数据里该组每个模型的 {@code api} 键）。</p>
 *
 * <p><b>completions 车道是精选子集</b>（10/378，裁决 R2）：4 条 {@code anthropic/*:batch}
 * （B105 {@code cacheControlFormat} 探测的<b>唯一</b>内置消费者族）＋ 6 条旗舰
 * （developer 角色、思考级别表三态、非 reasoning 对照各有样本）。其余 368 条走
 * {@code ModelInfo.minimal} 退化 —— 探测面（thinkingFormat/developer-role/maxTokensField）
 * 是 provider＋id 前缀的纯函数，行为不残缺；缺的只是价目/窗口元数据，属既有
 * 「内置目录数据规模」缺口行（docs/41:69），不在本包扩。</p>
 *
 * <p><b>compat 不硬编</b>：anthropic 车道经 {@link CatalogCompatRules#anthropic} 的三个
 * 谓词算 —— 设计期对 14 条逐条核过，与 pi 生成数据一致（fable-5→adaptive、
 * opus-4.7+→adaptive＋temperature:false、midConvo 因 provider≠"anthropic" 恒缺席，
 * pi 数据同样没有）；唯一差异 {@code supportsMidConvoEffort}（fable-5.1）java 不携带
 * （B98 家族）。completions 车道经 {@link CatalogCompatRules#completions}（openrouter 臂
 * 给 {@code thinkingFormat}，冗余照抄）；pi 数据里的 {@code sendSessionAffinityHeaders}/
 * {@code supportsDeveloperRole:false}/{@code cacheControlFormat:"anthropic"} 三个键
 * <b>不转录</b>——第一个 java 不携带（B103 家族），后两个与请求期探测同值
 * （A-07 原则：探测的差量不在目录标注）。</p>
 *
 * <p><b>能力集映射</b>（capabilities 是 pi-java 的发明，pi 只有 reasoning/input 两键）：
 * {@code reasoning→THINKING}、{@code "image"∈input→IMAGE_INPUT}、TEXT/TOOL_USE/STREAMING
 * 恒有；{@code PROMPT_CACHING} 当且仅当 pi 数据 {@code cost.cacheRead > 0}。</p>
 */
public final class OpenRouterModels {

    private OpenRouterModels() {}

    private static final String PROVIDER = "openrouter";

    // ── 思考级别表（转录自 pi 数据的 thinkingLevelMap；null 值 ≙ 显式不支持）────

    /** {@code {off:null, minimal:null, low..max}} —— fable-5/5.1、opus-5.5、fable-5:batch。 */
    private static final ThinkingLevelMap TLM_NULL_OFF = tlm(
        lv("off", null), lv("minimal", null), lv("low", "low"), lv("medium", "medium"),
        lv("high", "high"), lv("xhigh", "xhigh"), lv("max", "max"));

    /** {@code {off:"none", …, xhigh:null, max:"max"}} —— opus-4.6、sonnet-4.6。 */
    private static final ThinkingLevelMap TLM_NONE_OFF_NO_XHIGH = tlm(
        lv("off", "none"), lv("minimal", null), lv("low", "low"), lv("medium", "medium"),
        lv("high", "high"), lv("xhigh", null), lv("max", "max"));

    /** {@code {off:"none", …, xhigh:"xhigh", max:"max"}} —— opus-4.7/4.8/5、sonnet-5、opus-4.8:batch。 */
    private static final ThinkingLevelMap TLM_NONE_OFF_FULL = tlm(
        lv("off", "none"), lv("minimal", null), lv("low", "low"), lv("medium", "medium"),
        lv("high", "high"), lv("xhigh", "xhigh"), lv("max", "max"));

    /** {@code {off:"none", …, xhigh:null, max:null}} —— openai/gpt-5.1。 */
    private static final ThinkingLevelMap TLM_GPT_5_1 = tlm(
        lv("off", "none"), lv("minimal", null), lv("low", "low"), lv("medium", "medium"),
        lv("high", "high"), lv("xhigh", null), lv("max", null));

    /** {@code {off:null, …, xhigh:"xhigh", max:null}} —— openai/gpt-5.1-codex-max。 */
    private static final ThinkingLevelMap TLM_CODEX_MAX = tlm(
        lv("off", null), lv("minimal", null), lv("low", "low"), lv("medium", "medium"),
        lv("high", "high"), lv("xhigh", "xhigh"), lv("max", null));

    /** {@code {off:null}} —— deepseek-r1、gemini-2.5-pro（off 显式不支持 ⇒ 整键不发）。 */
    private static final ThinkingLevelMap TLM_OFF_UNSUPPORTED = tlm(lv("off", null));

    /** 全部内置 OpenRouter chat 模型（anthropic 车道 14 ＋ completions 精选 10）。 */
    public static ModelCatalog catalog() {
        var models = new ArrayList<ModelInfo>();
        models.addAll(anthropicLane());
        models.addAll(completionsLane());
        return BuiltinCatalog.of(List.copyOf(models));
    }

    /** anthropic-messages 车道 14 条（pi 数据该组全量），每条带 api 派发标记。 */
    static List<ModelInfo> anthropicLane() {
        return List.of(
            anthropic("anthropic/claude-fable-5", "Anthropic: Claude Fable 5",
                1_000_000, 128_000, 10, 50, 1, 12.5, TLM_NULL_OFF),
            anthropic("anthropic/claude-fable-5.1", "Anthropic: Claude Fable 5.1",
                1_000_000, 128_000, 10, 50, 0.25, 12.5, TLM_NULL_OFF),
            anthropic("anthropic/claude-haiku-4.5", "Anthropic: Claude Haiku 4.5",
                200_000, 64_000, 1, 5, 0.1, 1.25, ThinkingLevelMap.empty()),
            anthropic("anthropic/claude-opus-4.1", "Anthropic: Claude Opus 4.1",
                200_000, 32_000, 15, 75, 1.5, 18.75, ThinkingLevelMap.empty()),
            anthropic("anthropic/claude-opus-4.5", "Anthropic: Claude Opus 4.5",
                200_000, 64_000, 5, 25, 0.5, 6.25, ThinkingLevelMap.empty()),
            anthropic("anthropic/claude-opus-4.6", "Anthropic: Claude Opus 4.6",
                1_000_000, 128_000, 5, 25, 0.5, 6.25, TLM_NONE_OFF_NO_XHIGH),
            anthropic("anthropic/claude-opus-4.7", "Anthropic: Claude Opus 4.7",
                1_000_000, 128_000, 5, 25, 0.5, 6.25, TLM_NONE_OFF_FULL),
            anthropic("anthropic/claude-opus-4.8", "Anthropic: Claude Opus 4.8",
                1_000_000, 128_000, 5, 25, 0.5, 6.25, TLM_NONE_OFF_FULL),
            anthropic("anthropic/claude-opus-5", "Anthropic: Claude Opus 5",
                1_000_000, 128_000, 5, 25, 0.5, 6.25, TLM_NONE_OFF_FULL),
            anthropic("anthropic/claude-opus-5.5", "Anthropic: Claude Opus 5.5",
                1_000_000, 128_000, 4, 20, 0.2, 5, TLM_NULL_OFF),
            anthropic("anthropic/claude-sonnet-4", "Anthropic: Claude Sonnet 4",
                200_000, 64_000, 3, 15, 0.3, 3.75, ThinkingLevelMap.empty()),
            anthropic("anthropic/claude-sonnet-4.5", "Anthropic: Claude Sonnet 4.5",
                1_000_000, 64_000, 3, 15, 0.3, 3.75, ThinkingLevelMap.empty()),
            anthropic("anthropic/claude-sonnet-4.6", "Anthropic: Claude Sonnet 4.6",
                1_000_000, 128_000, 3, 15, 0.3, 3.75, TLM_NONE_OFF_NO_XHIGH),
            anthropic("anthropic/claude-sonnet-5", "Anthropic: Claude Sonnet 5",
                1_000_000, 128_000, 2, 10, 0.2, 2.5, TLM_NONE_OFF_FULL));
    }

    /** openai-completions 车道精选 10 条（api 缺席 ≙ provider 默认协议）。 */
    static List<ModelInfo> completionsLane() {
        return List.of(
            // anthropic/*:batch —— B105（cacheControlFormat 探测 :1632）的内置消费者族。
            completions("anthropic/claude-fable-5:batch", "Anthropic: Claude Fable 5 (batch)",
                true, true, 1_000_000, 128_000, 5, 25, 0.5, 6.25, TLM_NULL_OFF),
            completions("anthropic/claude-sonnet-4.5:batch",
                "Anthropic: Claude Sonnet 4.5 (batch)",
                true, true, 1_000_000, 64_000, 1.5, 7.5, 0.15, 1.875, ThinkingLevelMap.empty()),
            completions("anthropic/claude-haiku-4.5:batch", "Anthropic: Claude Haiku 4.5 (batch)",
                true, true, 200_000, 64_000, 0.5, 2.5, 0.05, 0.625, ThinkingLevelMap.empty()),
            completions("anthropic/claude-opus-4.8:batch", "Anthropic: Claude Opus 4.8 (batch)",
                true, true, 1_000_000, 128_000, 2.5, 12.5, 0.25, 3.125, TLM_NONE_OFF_FULL),
            // openai/* —— developer 角色探测（anthropic/ 与 openai/ 前缀）为真的对照。
            completions("openai/gpt-5.1", "OpenAI: GPT-5.1",
                true, true, 400_000, 128_000, 1.25, 10, 0.125, 0, TLM_GPT_5_1),
            completions("openai/gpt-5.1-codex-max", "OpenAI: GPT-5.1-Codex-Max",
                true, true, 400_000, 128_000, 1.25, 10, 0.125, 0, TLM_CODEX_MAX),
            // deepseek/* —— 非 reasoning 对照 ＋ off 显式不支持样本。
            completions("deepseek/deepseek-chat", "DeepSeek: DeepSeek V3",
                false, false, 163_840, 16_384, 0.32, 0.89, 0, 0, ThinkingLevelMap.empty()),
            completions("deepseek/deepseek-r1", "DeepSeek: R1",
                true, false, 64_000, 16_000, 0.7, 2.5, 0, 0, TLM_OFF_UNSUPPORTED),
            // google/* —— reasoning＋cacheRead>0 样本。
            completions("google/gemini-2.5-pro", "Google: Gemini 2.5 Pro",
                true, true, 1_048_576, 65_536, 1.25, 10, 0.125, 0.375, TLM_OFF_UNSUPPORTED),
            completions("google/gemini-2.5-flash", "Google: Gemini 2.5 Flash",
                true, true, 1_048_576, 65_535, 0.3, 2.5, 0.03, 0.083333,
                ThinkingLevelMap.empty()));
    }

    // ── 条目构造 ────────────────────────────────────────────────────

    /**
     * anthropic 车道条目：pi 数据该组全部 {@code reasoning:true}、{@code input:["text","image"]}、
     * {@code cacheRead>0} ⇒ 能力集恒定；compat 走谓词（§头注）；api 派发标记必带。
     */
    private static ModelInfo anthropic(String id, String display, int contextWindow,
            int maxTokens, double in, double out, double cacheRead, double cacheWrite,
            ThinkingLevelMap levels) {
        return new ModelInfo(
            ModelId.of(PROVIDER, id), display,
            Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT, ModelCapability.TOOL_USE,
                ModelCapability.THINKING, ModelCapability.STREAMING, ModelCapability.PROMPT_CACHING),
            contextWindow, maxTokens, false,
            pricing(in, out, cacheRead, cacheWrite), levels, Map.of(), Map.of(),
            CatalogCompatRules.anthropic(PROVIDER, id), "anthropic-messages");
    }

    /** completions 车道条目：能力集按 pi 数据的 reasoning/input 逐条给；api 缺席（默认协议）。 */
    private static ModelInfo completions(String id, String display, boolean reasoning,
            boolean image, int contextWindow, int maxTokens,
            double in, double out, double cacheRead, double cacheWrite,
            ThinkingLevelMap levels) {
        var caps = new LinkedHashSet<ModelCapability>();
        caps.add(ModelCapability.TEXT);
        caps.add(ModelCapability.TOOL_USE);
        caps.add(ModelCapability.STREAMING);
        if (reasoning) {
            caps.add(ModelCapability.THINKING);
        }
        if (image) {
            caps.add(ModelCapability.IMAGE_INPUT);
        }
        if (cacheRead > 0) {
            caps.add(ModelCapability.PROMPT_CACHING);
        }
        return new ModelInfo(
            ModelId.of(PROVIDER, id), display, Set.copyOf(caps),
            contextWindow, maxTokens, false,
            pricing(in, out, cacheRead, cacheWrite), levels, Map.of(), Map.of(),
            CatalogCompatRules.completions(PROVIDER, id), null);
    }

    private static PricingInfo pricing(double in, double out, double cacheRead,
            double cacheWrite) {
        return new PricingInfo(in, out, cacheRead, cacheWrite, List.of());
    }

    /** 一条级别表项：值 {@code null} ≙ pi 的显式 null（该级别不支持）。 */
    private static Map.Entry<ModelThinkingLevel, Optional<String>> lv(String level, String value) {
        return Map.entry(ModelThinkingLevel.parse(level).orElseThrow(),
            Optional.ofNullable(value));
    }

    @SafeVarargs
    private static ThinkingLevelMap tlm(Map.Entry<ModelThinkingLevel, Optional<String>>... entries) {
        var map = new LinkedHashMap<ModelThinkingLevel, Optional<String>>();
        for (var entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return ThinkingLevelMap.of(map);
    }
}
