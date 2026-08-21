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

    /** Catalog of Anthropic Claude models. */
    public static ModelCatalog anthropicModels() {
        return new BuiltinCatalog(List.of(
                model("claude-fable-5", "Claude Fable 5",
                        200_000, 16_384, anthropicCaps(), 3.00, 15.00),
                model("claude-opus-4-8", "Claude Opus 4.8",
                        200_000, 32_768, anthropicCaps(), 15.00, 75.00),
                model("claude-sonnet-4-6", "Claude Sonnet 4.6",
                        200_000, 8_192, anthropicCaps(), 3.00, 15.00),
                model("claude-haiku-4-5-20251001", "Claude Haiku 4.5",
                        200_000, 8_192, anthropicCaps(), 0.80, 4.00)
        ));
    }

    /** Catalog of OpenAI GPT models. */
    public static ModelCatalog openaiModels() {
        return new BuiltinCatalog(List.of(
                model("gpt-5", "GPT-5", 128_000, 16_384, frontierChatCaps(), 2.50, 10.00),
                model("gpt-5-mini", "GPT-5 Mini", 128_000, 8_192, frontierChatCaps(), 0.50, 2.00),
                model("gpt-5-nano", "GPT-5 Nano", 128_000, 4_096, chatCaps(), 0.15, 0.60),
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

    /** Catalog of DeepSeek models. */
    public static ModelCatalog deepseekModels() {
        return new BuiltinCatalog(List.of(
                model("deepseek-chat", "DeepSeek Chat",
                        128_000, 8_192, chatCaps(), 0.27, 1.10),
                model("deepseek-reasoner", "DeepSeek Reasoner",
                        128_000, 32_768, reasoningCaps(), 0.55, 2.19)
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

    private static ModelInfo model(String name, String display, int maxInput,
                                    int maxOutput, Set<ModelCapability> caps,
                                    double inPrice, double outPrice) {
        return new ModelInfo(
                ModelId.of(name.contains("claude") ? "anthropic"
                        : name.contains("gpt") ? "openai"
                        : name.contains("gemini") ? "google"
                        : name.contains("deepseek") ? "deepseek"
                        : name.contains("mistral") ? "mistral" : "unknown",
                        name),
                display, caps, maxInput, maxOutput, false,
                new PricingInfo(inPrice, outPrice));
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
