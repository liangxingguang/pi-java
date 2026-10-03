package com.pijava.ai.provider.builtin;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.CatalogCompatRules;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * Built-in model data for Phase 6 China-focused providers.
 *
 * <p>Unlike {@link BuiltinCatalog}'s name-heuristic helper, every model
 * here carries an explicit provider id.</p>
 */
public final class ModelData {

    /**
     * 静态模型表的快照时间（对齐 pi {@code modelDataManifest.generatedAt}，
     * docs/70 R5/§4.7）：所有内置 provider 的目录共用这一个时间戳。
     *
     * <p>语义是「这批数据对应的 pi 生成数据是哪天的」，不是「代码什么时候写的」——
     * 远程目录的守卫（{@code RemoteCatalogProvider.remoteModels}）会**丢弃
     * Last-Modified 不晚于此值**的远端目录，所以值取太大等于远程覆盖永远不生效。
     * 当前值 = pi 对齐锚点 {@code 3390bd9}（2026-09-20）的日期。<b>刷新内置模型表时
     * 必须同步更新它。</b></p>
     */
    private static final java.time.Instant GENERATED_AT =
        java.time.Instant.parse("2026-09-20T00:00:00Z");

    /** 静态模型表的快照时间；见 {@link #GENERATED_AT}。 */
    public static java.time.Instant generatedAt() {
        return GENERATED_AT;
    }

    private ModelData() {}

    /** Moonshot AI China (Kimi) models. */
    public static ModelCatalog moonshotAiCnModels() {
        return catalog(
            model("moonshotai-cn", "kimi-k2.5", "Kimi K2.5",
                256_000, 8_192, thinkingCaps(), 0.60, 2.50),
            model("moonshotai-cn", "kimi-k2-turbo-preview", "Kimi K2 Turbo",
                256_000, 8_192, chatCaps(), 0.30, 1.20));
    }

    /** Moonshot AI international models. */
    public static ModelCatalog moonshotAiModels() {
        return catalog(
            model("moonshotai", "kimi-k2.5", "Kimi K2.5",
                256_000, 8_192, thinkingCaps(), 0.60, 2.50),
            model("moonshotai", "kimi-k2-turbo-preview", "Kimi K2 Turbo",
                256_000, 8_192, chatCaps(), 0.30, 1.20));
    }

    /** Zhipu GLM coding (China) models. */
    public static ModelCatalog zaiCodingCnModels() {
        return catalog(
            model("zai-coding-cn", "glm-4.7", "GLM-4.7",
                200_000, 16_384, thinkingCaps(), 0.50, 2.00),
            model("zai-coding-cn", "glm-4.5-air", "GLM-4.5 Air",
                128_000, 8_192, chatCaps(), 0.20, 0.80));
    }

    /** Z.AI international coding models. */
    public static ModelCatalog zaiModels() {
        return catalog(
            model("zai", "glm-4.7", "GLM-4.7",
                200_000, 16_384, thinkingCaps(), 0.50, 2.00),
            model("zai", "glm-4.5-air", "GLM-4.5 Air",
                128_000, 8_192, chatCaps(), 0.20, 0.80));
    }

    /** Alibaba Qwen token-plan China models. */
    public static ModelCatalog qwenTokenPlanCnModels() {
        return catalog(
            model("qwen-token-plan-cn", "qwen3-coder-plus", "Qwen3 Coder Plus",
                256_000, 16_384, thinkingCaps(), 1.00, 4.00),
            model("qwen-token-plan-cn", "qwen3-max", "Qwen3 Max",
                256_000, 16_384, thinkingCaps(), 1.20, 6.00));
    }

    /** Xiaomi MiMo models. */
    public static ModelCatalog xiaomiModels() {
        return catalog(
            model("xiaomi", "mimo-v2-omni", "MiMo V2 Omni",
                128_000, 8_192, thinkingCaps(), 0.40, 1.60),
            model("xiaomi", "mimo-v2-flash", "MiMo V2 Flash",
                128_000, 8_192, chatCaps(), 0.15, 0.60));
    }

    /** Xiaomi MiMo China token-plan models. */
    public static ModelCatalog xiaomiTokenPlanCnModels() {
        return catalog(
            model("xiaomi-token-plan-cn", "mimo-v2-omni", "MiMo V2 Omni",
                128_000, 8_192, thinkingCaps(), 0.40, 1.60),
            model("xiaomi-token-plan-cn", "mimo-v2-flash", "MiMo V2 Flash",
                128_000, 8_192, chatCaps(), 0.15, 0.60));
    }

    /** MiniMax China (Anthropic-compatible) models. */
    public static ModelCatalog miniMaxCnModels() {
        return catalog(
            model("minimax-cn", "MiniMax-M2.5", "MiniMax M2.5",
                200_000, 16_384, thinkingCaps(), 0.30, 1.20),
            model("minimax-cn", "MiniMax-M2", "MiniMax M2",
                200_000, 8_192, chatCaps(), 0.20, 0.80));
    }

    /** MiniMax international (Anthropic-compatible) models. */
    public static ModelCatalog miniMaxModels() {
        return catalog(
            model("minimax", "MiniMax-M2.5", "MiniMax M2.5",
                200_000, 16_384, thinkingCaps(), 0.30, 1.20),
            model("minimax", "MiniMax-M2", "MiniMax M2",
                200_000, 8_192, chatCaps(), 0.20, 0.80));
    }

    /** Ant Ling models. */
    public static ModelCatalog antLingModels() {
        return catalog(
            model("ant-ling", "ling-1t", "Ling 1T",
                128_000, 8_192, thinkingCaps(), 0.50, 2.00),
            model("ant-ling", "ring-flash-2.0", "Ring Flash 2.0",
                128_000, 8_192, chatCaps(), 0.15, 0.60));
    }

    /** Local Ollama models (no pricing). */
    public static ModelCatalog ollamaModels() {
        return catalog(
            model("ollama", "llama3.2", "Llama 3.2",
                128_000, 8_192, chatCaps(), -1, -1),
            model("ollama", "qwen2.5-coder", "Qwen2.5 Coder",
                128_000, 8_192, chatCaps(), -1, -1));
    }

    private static ModelCatalog catalog(ModelInfo... models) {
        return BuiltinCatalog.of(List.of(models));
    }

    private static ModelInfo model(String provider, String name, String display,
            int maxIn, int maxOut, Set<ModelCapability> caps, double in, double out) {
        var pricing = in < 0 || out < 0
            ? PricingInfo.UNKNOWN
            : new PricingInfo(in, out);
        // 包 A-09：内置条目携带目录 compat（与 BuiltinCatalog.deepseekModel 的先例同形）
        // —— 此前恒给 ModelCompat.NONE，moonshotai／xiaomi／qwen-token-plan 的
        // thinkingFormat 目录值（探测给不出）就此丢失（docs/58 §3 缺口 7）。
        return new ModelInfo(
            ModelId.of(provider, name), display, caps, maxIn, maxOut, false, pricing,
            ThinkingLevelMap.empty(), Map.of(), Map.of(),
            CatalogCompatRules.completions(provider, name));
    }

    private static Set<ModelCapability> chatCaps() {
        return Set.of(ModelCapability.TEXT, ModelCapability.TOOL_USE,
            ModelCapability.STREAMING);
    }

    private static Set<ModelCapability> thinkingCaps() {
        return Set.of(ModelCapability.TEXT, ModelCapability.TOOL_USE,
            ModelCapability.THINKING, ModelCapability.STREAMING);
    }
}
