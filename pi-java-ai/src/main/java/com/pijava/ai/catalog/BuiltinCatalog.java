package com.pijava.ai.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * Built-in model catalog with static data for the 5 Phase 1 providers.
 *
 * <p>Model data aligns with pi's {@code providers/*.models.ts} generated data.
 * Phase 6 adds remote catalog refresh via ETag conditional requests.</p>
 */
public final class BuiltinCatalog implements ModelCatalog {

    private final Map<String, ModelInfo> modelsById;

    private BuiltinCatalog(List<ModelInfo> models) {
        this.modelsById = new HashMap<>();
        for (var m : models) {
            modelsById.put(m.id().provider() + "/" + m.id().modelName(), m);
        }
    }

    // ── Per-provider factories ────────────────────────────────

    /**
     * Catalog of Anthropic Claude models.
     *
     * <p>包 A7：每个模型经 {@link CatalogCompatRules#anthropic} 标注 compat ——
     * pi 的**生成期**规则（{@code generate-models.ts:1142}）在这四个 id 里命中三个
     * （`fable-5` / `opus-4-8` / `sonnet-4-6`），照抄谓词而不是硬编值。</p>
     */
    public static ModelCatalog anthropicModels() {
        return new BuiltinCatalog(List.of(
                anthropicModel("claude-fable-5", "Claude Fable 5",
                        200_000, 16_384, 3.00, 15.00),
                anthropicModel("claude-opus-4-8", "Claude Opus 4.8",
                        200_000, 32_768, 15.00, 75.00),
                anthropicModel("claude-sonnet-4-6", "Claude Sonnet 4.6",
                        200_000, 8_192, 3.00, 15.00),
                anthropicModel("claude-haiku-4-5-20251001", "Claude Haiku 4.5",
                        200_000, 8_192, 0.80, 4.00)
        ));
    }

    /** Catalog of OpenAI GPT models. */
    public static ModelCatalog openaiModels() {
        return new BuiltinCatalog(List.of(
                openaiChatModel("gpt-5", "GPT-5", 128_000, 16_384, frontierChatCaps(), 2.50, 10.00),
                openaiChatModel("gpt-5-mini", "GPT-5 Mini", 128_000, 8_192, frontierChatCaps(), 0.50, 2.00),
                openaiChatModel("gpt-5-nano", "GPT-5 Nano", 128_000, 4_096, chatCaps(), 0.15, 0.60),
                embeddingModel("text-embedding-3-small", "Text Embedding 3 Small"),
                embeddingModel("text-embedding-3-large", "Text Embedding 3 Large")
        ));
    }

    /** Catalog of OpenRouter image generation models (P6-28，对齐 pi
     *  {@code image-models.generated.ts} 的 openrouter 条目). */
    public static ModelCatalog openRouterImageModels() {
        return new BuiltinCatalog(List.of(
                imageModel("black-forest-labs/flux.2-flex", "Black Forest Labs: FLUX.2 Flex"),
                imageModel("black-forest-labs/flux.2-klein-4b", "Black Forest Labs: FLUX.2 Klein 4B"),
                imageModel("black-forest-labs/flux.2-max", "Black Forest Labs: FLUX.2 Max"),
                imageModel("black-forest-labs/flux.2-pro", "Black Forest Labs: FLUX.2 Pro"),
                imageModel("bytedance-seed/seedream-4.5", "Bytedance Seed: Seedream 4.5"),
                imageModel("google/gemini-2.5-flash-image", "Google: Gemini 2.5 Flash Image"),
                imageModel("google/gemini-3-pro-image", "Google: Gemini 3 Pro Image"),
                imageModel("google/gemini-3-pro-image-preview", "Google: Gemini 3 Pro Image Preview")
        ));
    }

    /** Catalog of Google Gemini models. */
    public static ModelCatalog googleModels() {
        return new BuiltinCatalog(List.of(
                model("gemini-2.5-pro", "Gemini 2.5 Pro",
                        2_097_152, 65_536, googleCaps(), 1.25, 10.00),
                model("gemini-2.5-flash", "Gemini 2.5 Flash",
                        1_048_576, 8_192, googleCaps(), 0.15, 0.60)
        ));
    }

    /**
     * Catalog of DeepSeek models (V4 series). 旧别名 {@code deepseek-chat}/
     * {@code deepseek-reasoner} 于 2026-07-24 弃用，故仅列 V4；Flash 支持工具调用。
     *
     * <p>包 A7：{@code deepseek-v4-pro} 经 {@link CatalogCompatRules#completions} 标注
     * {@code supportsMidConvoSystemMessages}（pi {@code applyOpenAICompletionsTranscriptMetadata:875}）；
     * 端点属性（`max_tokens`／store／developer 角色）由 {@link CompatResolver} 在请求期探测。</p>
     *
     * <p>⚠️ {@code deepseek-v4-flash} **不是** pi 的 id：pi 于 2026-09-10（`12f59336a`）把它改名成
     * {@code deepseek-flash}（commit message：*Replace retired Flash aliases with the canonical
     * deepseek-flash model*），而那条规则是**逐 id 写死**的 ⇒ flash 无论哪个名字都拿不到该标志。
     * 本仓的 id 漂移登记为 {@code docs/05 B97}（归 A-08），本包**不**改 id。</p>
     */
    public static ModelCatalog deepseekModels() {
        return new BuiltinCatalog(List.of(
                deepseekModel("deepseek-v4-flash", "DeepSeek V4 Flash",
                        1_048_576, 393_216, chatCaps(), 0.14, 0.28),
                deepseekModel("deepseek-v4-pro", "DeepSeek V4 Pro",
                        1_048_576, 393_216, reasoningCaps(), 1.74, 3.48)
        ));
    }

    /** Catalog of Mistral models. */
    public static ModelCatalog mistralModels() {
        return new BuiltinCatalog(List.of(
                model("mistral-large", "Mistral Large",
                        128_000, 8_192, frontierChatCaps(), 2.00, 6.00),
                model("mistral-small", "Mistral Small",
                        32_000, 4_096, chatCaps(), 0.20, 0.60)
        ));
    }

    /**
     * Aggregate catalog of all 5 built-in provider model lists.
     * Phase 3: used by the coding-agent assembly layer
     * ({@code AgentSession.create}) for CLI model resolution.
     */
    public static ModelCatalog all() {
        var models = new ArrayList<ModelInfo>();
        for (var catalog : List.of(
                anthropicModels(), openaiModels(), googleModels(),
                deepseekModels(), mistralModels())) {
            models.addAll(catalog.listModels());
        }
        return new BuiltinCatalog(models);
    }

    /** Build a catalog from an explicit model list. */
    public static ModelCatalog of(List<ModelInfo> models) {
        return new BuiltinCatalog(List.copyOf(models));
    }
    // ── ModelCatalog impl ─────────────────────────────────────

    @Override
    public List<ModelInfo> listModels() {
        return List.copyOf(modelsById.values());
    }

    @Override
    public Optional<ModelInfo> find(ModelId<?> id) {
        return Optional.ofNullable(modelsById.get(
                id.provider() + "/" + id.modelName()));
    }

    @Override
    public List<ModelInfo> search(String query) {
        var lower = query.toLowerCase();
        return modelsById.values().stream()
                .filter(m -> m.id().modelName().toLowerCase().contains(lower)
                        || m.displayName().toLowerCase().contains(lower))
                .toList();
    }

    // ── Helpers ───────────────────────────────────────────────

    /**
     * Anthropic 车道的内置条目：provider 固定为 {@code anthropic}，compat 由
     * {@link CatalogCompatRules#anthropic}（pi 的生成期规则）算。
     */
    private static ModelInfo anthropicModel(String name, String display, int maxInput,
                                             int maxOutput, double inPrice, double outPrice) {
        return model(name, display, maxInput, maxOutput, anthropicCaps(), inPrice, outPrice,
                CatalogCompatRules.anthropic("anthropic", name));
    }

    /**
     * openai chat 车道的内置条目：原 docs/66 标 {@code supportsStrictMode}
     * （{@link CatalogCompatRules#openaiResponses}）。
     */
    private static ModelInfo openaiChatModel(String name, String display, int maxInput,
                                               int maxOutput, Set<ModelCapability> caps,
                                               double inPrice, double outPrice) {
        return model(name, display, maxInput, maxOutput, caps, inPrice, outPrice,
                CatalogCompatRules.openaiResponses("openai", name));
    }

    /** completions 车道的内置条目（DeepSeek）：compat 由 {@link CatalogCompatRules#completions} 算。 */
    private static ModelInfo deepseekModel(String name, String display, int maxInput,
                                            int maxOutput, Set<ModelCapability> caps,
                                            double inPrice, double outPrice) {
        return model(name, display, maxInput, maxOutput, caps, inPrice, outPrice,
                CatalogCompatRules.completions("deepseek", name));
    }

    private static ModelInfo model(String name, String display, int maxInput,
                                    int maxOutput, Set<ModelCapability> caps,
                                    double inPrice, double outPrice) {
        return model(name, display, maxInput, maxOutput, caps, inPrice, outPrice,
                ModelCompat.NONE);
    }

    private static ModelInfo model(String name, String display, int maxInput,
                                    int maxOutput, Set<ModelCapability> caps,
                                    double inPrice, double outPrice, ModelCompat compat) {
        var provider = name.contains("claude") ? "anthropic"
                : name.contains("gpt") ? "openai"
                : name.contains("gemini") ? "google"
                : name.contains("deepseek") ? "deepseek"
                : name.contains("mistral") ? "mistral" : "unknown";
        return new ModelInfo(
                ModelId.of(provider, name),
                display, caps, maxInput, maxOutput, false,
                new PricingInfo(inPrice, outPrice), ThinkingLevelMap.empty(), Map.of(), Map.of(),
                compat);
    }

    /** OpenRouter image-generation model（provider 固定 openrouter-images）。 */
    private static ModelInfo imageModel(String id, String display) {
        return new ModelInfo(
                ModelId.of("openrouter-images", id), display,
                Set.of(ModelCapability.IMAGE_INPUT, ModelCapability.IMAGE_OUTPUT),
                0, 0, false, PricingInfo.UNKNOWN);
    }

    /** OpenAI embedding model. */
    private static ModelInfo embeddingModel(String id, String display) {
        return new ModelInfo(
                ModelId.of("openai", id), display,
                Set.of(ModelCapability.TEXT), 0, 0, false, PricingInfo.UNKNOWN);
    }

    private static Set<ModelCapability> frontierChatCaps() {
        return Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT,
                ModelCapability.TOOL_USE, ModelCapability.THINKING,
                ModelCapability.STREAMING, ModelCapability.PROMPT_CACHING);
    }

    private static Set<ModelCapability> anthropicCaps() {
        return Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT,
                ModelCapability.TOOL_USE, ModelCapability.THINKING,
                ModelCapability.STREAMING, ModelCapability.PROMPT_CACHING,
                ModelCapability.COMPUTER_USE);
    }

    private static Set<ModelCapability> googleCaps() {
        return Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT,
                ModelCapability.TOOL_USE, ModelCapability.THINKING,
                ModelCapability.STREAMING);
    }

    private static Set<ModelCapability> chatCaps() {
        return Set.of(ModelCapability.TEXT, ModelCapability.TOOL_USE,
                ModelCapability.STREAMING);
    }

    private static Set<ModelCapability> reasoningCaps() {
        return Set.of(ModelCapability.TEXT, ModelCapability.THINKING,
                ModelCapability.STREAMING);
    }
}
