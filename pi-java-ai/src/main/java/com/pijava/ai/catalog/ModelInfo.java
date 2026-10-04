package com.pijava.ai.catalog;

import java.util.Map;
import java.util.Set;

import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * Metadata about a specific model.
 *
 * @param id                the model identifier
 * @param displayName       human-readable name
 * @param capabilities      the features this model supports
 * @param maxInputTokens    maximum context window size in tokens
 * @param maxOutputTokens   maximum output tokens per request
 * @param deprecated        {@code true} if the model is scheduled for removal
 * @param pricing           input/output price per million tokens, or {@link PricingInfo#UNKNOWN}
 * @param thinkingLevelMap  per-model translation from ThinkingLevel → provider config
 * @param headers           extra HTTP headers sent with requests for this model
 *                          (pi {@code Model.headers}; default empty)
 * @param samplingParams    arbitrary sampling params merged into the request body
 *                          (pi {@code Model.samplingParams}; default empty)
 * @param api               pi {@code Model.api} 的线格名（如 {@code "anthropic-messages"}；
 *                          包 A-02，pi {@code compat.ts:262} 的派发键）。{@code null} ≙
 *                          「provider 的默认协议」——单协议 provider 的目录全部保持缺席，
 *                          只有多协议 provider（内置 openrouter）与 models.json 的 per-model
 *                          {@code api} 会写它。⚠️ 存**字符串**而不是
 *                          {@link com.pijava.ai.provider.Protocol}：pi 的 {@code Api} 就是字符串
 *                          联合，且 {@code catalog} 包不依赖 {@code provider} 包（免包环，
 *                          {@code CacheRetention} 的同一裁决，{@code 原 docs/54 §9 R2}）
 * @param baseUrl           pi {@code Model.baseUrl}：该模型请求实际打到的端点（D-P1，
 *                          {@code 原 docs/65}）。{@code null} ≙ 沿用车道/provider 默认；
 *                          三源 {@code model.baseUrl ?? provider.baseUrl ?? 内置}（B136 结案）
 */
public record ModelInfo(
    ModelId<?> id,
    String displayName,
    Set<ModelCapability> capabilities,
    int maxInputTokens,
    int maxOutputTokens,
    boolean deprecated,
    PricingInfo pricing,
    ThinkingLevelMap thinkingLevelMap,
    Map<String, String> headers,
    Map<String, Object> samplingParams,
    ModelCompat compat,
    String api,
    String baseUrl
) {
    /** Compact constructor that defensively copies capabilities and defaults a null thinking map. */
    public ModelInfo {
        capabilities = Set.copyOf(capabilities);
        if (thinkingLevelMap == null) {
            thinkingLevelMap = ThinkingLevelMap.empty();
        }
        headers = Map.copyOf(headers);
        samplingParams = Map.copyOf(samplingParams);
        if (compat == null) {
            compat = ModelCompat.NONE;
        }
        // 空串归一成缺席（pi 的 `undefined`；models.json 写 "api": "" 不算「选了一条车道」）。
        api = api == null || api.isBlank() ? null : api;
        baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl;
    }

    /**
     * 十二参便捷构造（D-P1 加 {@code baseUrl} 之前的规范构造）：
     * {@code baseUrl} 缺席 ≙ 沿用默认。旧调用点**零改签**（同 A-01/A-09/A-10 手法）。
     */
    public ModelInfo(
        ModelId<?> id,
        String displayName,
        Set<ModelCapability> capabilities,
        int maxInputTokens,
        int maxOutputTokens,
        boolean deprecated,
        PricingInfo pricing,
        ThinkingLevelMap thinkingLevelMap,
        Map<String, String> headers,
        Map<String, Object> samplingParams,
        ModelCompat compat,
        String api
    ) {
        this(id, displayName, capabilities, maxInputTokens, maxOutputTokens, deprecated,
             pricing, thinkingLevelMap, headers, samplingParams, compat, api, null);
    }

    /**
     * 十一参便捷构造（包 A-02 之前的**规范**构造 —— 那时组件就这十一个）：
     * {@code api} 缺席 ≙ provider 默认协议。
     */
    public ModelInfo(
        ModelId<?> id,
        String displayName,
        Set<ModelCapability> capabilities,
        int maxInputTokens,
        int maxOutputTokens,
        boolean deprecated,
        PricingInfo pricing,
        ThinkingLevelMap thinkingLevelMap,
        Map<String, String> headers,
        Map<String, Object> samplingParams,
        ModelCompat compat
    ) {
        this(id, displayName, capabilities, maxInputTokens, maxOutputTokens, deprecated,
             pricing, thinkingLevelMap, headers, samplingParams, compat, null, null);
    }

    /** Convenience constructor for models without thinking support. */
    public ModelInfo(
        ModelId<?> id,
        String displayName,
        Set<ModelCapability> capabilities,
        int maxInputTokens,
        int maxOutputTokens,
        boolean deprecated,
        PricingInfo pricing
    ) {
        this(id, displayName, capabilities, maxInputTokens, maxOutputTokens,
             deprecated, pricing, ThinkingLevelMap.empty(), Map.of(), Map.of());
    }

    /** Convenience constructor with thinking map but no headers/sampling params. */
    public ModelInfo(
        ModelId<?> id,
        String displayName,
        Set<ModelCapability> capabilities,
        int maxInputTokens,
        int maxOutputTokens,
        boolean deprecated,
        PricingInfo pricing,
        ThinkingLevelMap thinkingLevelMap
    ) {
        this(id, displayName, capabilities, maxInputTokens, maxOutputTokens,
             deprecated, pricing, thinkingLevelMap, Map.of(), Map.of());
    }

    /** Convenience constructor with headers/sampling params (empty thinking map). */
    public ModelInfo(
        ModelId<?> id,
        String displayName,
        Set<ModelCapability> capabilities,
        int maxInputTokens,
        int maxOutputTokens,
        boolean deprecated,
        PricingInfo pricing,
        Map<String, String> headers,
        Map<String, Object> samplingParams
    ) {
        this(id, displayName, capabilities, maxInputTokens, maxOutputTokens,
             deprecated, pricing, ThinkingLevelMap.empty(), headers, samplingParams);
    }

    /** Convenience constructor with headers/sampling params but no compat flags. */
    public ModelInfo(
        ModelId<?> id,
        String displayName,
        Set<ModelCapability> capabilities,
        int maxInputTokens,
        int maxOutputTokens,
        boolean deprecated,
        PricingInfo pricing,
        ThinkingLevelMap thinkingLevelMap,
        Map<String, String> headers,
        Map<String, Object> samplingParams
    ) {
        this(id, displayName, capabilities, maxInputTokens, maxOutputTokens,
             deprecated, pricing, thinkingLevelMap, headers, samplingParams, ModelCompat.NONE);
    }

    /**
     * 这个模型能不能收图片 —— pi {@code model.input.includes("image")}
     * （{@code transform-messages.ts:36} 的降级闸、{@code openai-completions.ts:1424} 等四处车道门的唯一判据）。
     *
     * <p>⚠️ <b>「未知」按「支持」处理</b>，这是**刻意**的：{@link #minimal} 造出的模型
     * {@code capabilities} 为空，而它无法与「这个模型真的不支持图片」区分（见 {@link #minimal} 的
     * javadoc）。两个方向各有代价：</p>
     * <ul>
     *   <li>未知按**不支持** ⇒ 把用户读的图**静默换成占位文本**（正是包 H2 要修的 bug，
     *       会在目录未命中时原样重演）；</li>
     *   <li>未知按**支持** ⇒ 真不支持时由 provider **报错**（响亮、可诊断）。</li>
     * </ul>
     * <p>pi 没有这个三态：它的 {@code getModel} 查不到就抛，请求根本发不出去 ⇒ 本方法的选择
     * 是 pi-java 独有状态下的**唯一**判断点，方向取「响亮优于静默」（{@code 原 docs/44 D2}）。</p>
     *
     * @return {@code true} 当能力位里**有** {@link ModelCapability#IMAGE_INPUT}，或能力位**为空**（未知）
     */
    public boolean supportsImageInput() {
        return capabilities.isEmpty() || capabilities.contains(ModelCapability.IMAGE_INPUT);
    }

    /**
     * The least a {@link ModelInfo} can be: identity only, everything else empty.
     *
     * <p>Used when a request names a model the catalog does not know (原 docs/31 §8.34.4 决策 5) —
     * today any {@link ModelId} is accepted, and that tolerance is preserved by synthesizing
     * rather than failing.</p>
     *
     * <p>⚠️ The synthesized {@code capabilities} is **empty**, which is indistinguishable from
     * "this model really does not support images". Any consumer that reads capabilities to
     * *downgrade* content (pi's unsupported-image placeholder, {@code transform-messages.ts:35-57})
     * must therefore keep "unknown" and "unsupported" apart, or it will mangle requests for
     * catalog misses. {@link #supportsImageInput()} is that consumer-facing judgement
     * (unknown ⇒ supported; {@code 原 docs/44 D2}).</p>
     */
    public static ModelInfo minimal(ModelId<?> id) {
        return new ModelInfo(id, id.modelName(), Set.of(), 0, 0, false,
            PricingInfo.UNKNOWN, ThinkingLevelMap.empty(), Map.of(), Map.of(), ModelCompat.NONE);
    }
}
