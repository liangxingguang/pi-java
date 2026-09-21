package com.pijava.ai.provider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.ModelsJsonSchema.CompatDef;
import com.pijava.ai.provider.ModelsJsonSchema.Cost;
import com.pijava.ai.provider.ModelsJsonSchema.ModelDef;
import com.pijava.ai.provider.ModelsJsonSchema.ProviderDef;
import com.pijava.ai.provider.ModelsJsonSchema.Root;
import com.pijava.ai.provider.builtin.ProviderCatalog;

/**
 * Loader for the user's custom-provider config at
 * {@code ~/.pi-java/agent/models.json} (pi-compatible schema).
 *
 * <p>Aligned with pi {@code model-config.ts} + {@code provider-composer.ts}
 * {@code modelFromJson}: the provider id is the {@code providers} map key;
 * model defaults are contextWindow 128k, maxTokens 16k, cost 0, reasoning
 * false, input ["text"]. Unknown fields are ignored.</p>
 *
 * <p>Path resolution mirrors {@code FileSettingsStorage.defaultAgentDir()} —
 * the {@code PI_JAVA_CODING_AGENT_DIR} environment variable overrides
 * {@code ~/.pi-java/agent} (this module cannot depend on coding-agent, same
 * precedent as {@code FileCredentialStore}).</p>
 */
public final class ModelsJsonConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, ProviderDef> providers;

    private ModelsJsonConfig(Map<String, ProviderDef> providers) {
        this.providers = Map.copyOf(providers);
    }

    /** Default location: {@code $PI_JAVA_CODING_AGENT_DIR/models.json} or {@code ~/.pi-java/agent/models.json}. */
    public static Path defaultPath() {
        var envDir = System.getenv("PI_JAVA_CODING_AGENT_DIR");
        var agentDir = envDir != null && !envDir.isBlank()
            ? Path.of(envDir)
            : Path.of(System.getProperty("user.home"), ".pi-java", "agent");
        return agentDir.resolve("models.json");
    }

    /** Load from the given path; missing file yields an empty config. */
    public static ModelsJsonConfig load(Path path) {
        if (!Files.exists(path)) {
            return new ModelsJsonConfig(Map.of());
        }
        Root root;
        try {
            root = MAPPER.readValue(path.toFile(), Root.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse models.json: " + path
                + " (" + e.getMessage() + ")", e);
        }
        var providers = new LinkedHashMap<String, ProviderDef>();
        if (root != null && root.providers() != null) {
            providers.putAll(root.providers());
        }
        return new ModelsJsonConfig(providers);
    }

    /** Load from {@link #defaultPath()}. */
    public static ModelsJsonConfig loadDefault() {
        return load(defaultPath());
    }

    /** Provider ids in file order. */
    public List<String> providerIds() {
        return List.copyOf(providers.keySet());
    }

    /** The raw definition for one provider, or null. */
    public ProviderDef provider(String id) {
        return providers.get(id);
    }

    /** Whether no providers are configured. */
    public boolean isEmpty() {
        return providers.isEmpty();
    }

    /**
     * Build a {@link ModelsJsonProvider} for every configured entry.
     *
     * <p>Validation errors (missing api/baseUrl/model id) throw with the
     * provider id in the message so users can fix the file.</p>
     */
    public List<Provider> buildProviders() {
        var result = new ArrayList<Provider>();
        for (var entry : providers.entrySet()) {
            result.add(buildProvider(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    /** Catalog of every model defined across all providers. */
    public ModelCatalog catalog() {
        var models = new ArrayList<ModelInfo>();
        for (var entry : providers.entrySet()) {
            var def = entry.getValue();
            if (def.models() == null) {
                continue;
            }
            for (var model : def.models()) {
                models.add(toModelInfo(entry.getKey(), def, model));
            }
        }
        return BuiltinCatalog.of(models);
    }

    /** Built-in catalog merged with every models.json model. */
    public static ModelCatalog allModels() {
        var models = new ArrayList<ModelInfo>(ProviderCatalog.allModels().listModels());
        var config = loadDefault();
        for (var entry : config.providers.entrySet()) {
            var def = entry.getValue();
            if (def.models() == null) {
                continue;
            }
            for (var model : def.models()) {
                models.add(toModelInfo(entry.getKey(), def, model));
            }
        }
        return BuiltinCatalog.of(models);
    }

    private static Provider buildProvider(String id, ProviderDef def) {
        if (def.api() == null || def.api().isBlank()) {
            throw new IllegalStateException(
                "models.json provider \"" + id + "\": \"api\" is required"
                + " (e.g. \"openai-completions\" or \"anthropic-messages\")");
        }
        var protocol = Protocol.valueOf(
            def.api().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        if (def.baseUrl() == null || def.baseUrl().isBlank()) {
            throw new IllegalStateException(
                "models.json provider \"" + id + "\": \"baseUrl\" is required");
        }
        if (def.models() != null) {
            for (var model : def.models()) {
                if (model.id() == null || model.id().isBlank()) {
                    throw new IllegalStateException(
                        "models.json provider \"" + id + "\": every model needs an \"id\"");
                }
            }
        }
        var displayName = def.name() != null && !def.name().isBlank() ? def.name() : id;
        var catalog = modelsCatalog(id, def);
        return new ModelsJsonProvider(id, displayName, def, protocol, catalog);
    }

    private static ModelCatalog modelsCatalog(String providerId, ProviderDef def) {
        var models = new ArrayList<ModelInfo>();
        if (def.models() != null) {
            for (var model : def.models()) {
                models.add(toModelInfo(providerId, def, model));
            }
        }
        return BuiltinCatalog.of(models);
    }

    private static ModelInfo toModelInfo(String providerId, ProviderDef def, ModelDef model) {
        if (model.id() == null || model.id().isBlank()) {
            throw new IllegalStateException(
                "models.json provider \"" + providerId + "\": every model needs an \"id\"");
        }
        var caps = new java.util.LinkedHashSet<ModelCapability>();
        caps.add(ModelCapability.TEXT);
        caps.add(ModelCapability.TOOL_USE);
        caps.add(ModelCapability.STREAMING);
        if (model.reasoning() != null && model.reasoning()) {
            caps.add(ModelCapability.THINKING);
        }
        if (model.input() != null && model.input().contains("image")) {
            caps.add(ModelCapability.IMAGE_INPUT);
        }
        var contextWindow = model.contextWindow() != null ? model.contextWindow() : 128_000;
        var maxTokens = model.maxTokens() != null ? model.maxTokens() : 16_384;
        var pricing = pricingFrom(providerId, model.id(), model.cost());
        var displayName = model.name() != null && !model.name().isBlank() ? model.name() : model.id();
        var headers = model.headers() != null ? model.headers() : Map.<String, String>of();
        var samplingParams = model.samplingParams() != null ? model.samplingParams() : Map.<String, Object>of();
        return new ModelInfo(
            ModelId.of(providerId, model.id()),
            displayName, Set.copyOf(caps), contextWindow, maxTokens, false,
            pricing, com.pijava.ai.thinking.ThinkingLevelMap.empty(), headers, samplingParams,
            compatOf(model.compat()));
    }

    /**
     * models.json 的 {@code cost} → {@link PricingInfo}（包 H1 步 6，
     * {@code docs/42} 的 J10/J11 ＋ 裁决 B/F）。三个分支对应 pi 的三种语义：
     *
     * <ul>
     *   <li><b>cost 整块缺席</b> ⇒ 四费率全 0（<b>免费</b>）—— 照抄 pi
     *       {@code provider-composer.ts:165} 的 {@code definition.cost ?? {input:0,
     *       output:0, cacheRead:0, cacheWrite:0}}；缺席是用户的<b>明示</b>。</li>
     *   <li><b>cost 存在但 input/output 不全</b> ⇒ {@link PricingInfo#UNKNOWN}
     *       （全 -1，<b>未知</b>）—— 裁决 F（T11 的钉子）：pi 的 zod 对存在的 cost
     *       强制四费率齐全（{@code model-config.ts:125-130}），半价在 pi 是<b>校验失败</b>；
     *       宽松加载器不炸文件，但绝不把残缺降级成免费（修复前正是 0/0 ⇒ 「免费」）。</li>
     *   <li><b>input/output 齐全</b> ⇒ cache 费率未写时取 -1（未知，裁决 B 的口径）；
     *       {@code tiers} 逐档映射，pi 的 {@code ModelCostTierSchema} 五字段全必填 ⇒
     *       缺任一按校验失败拒载（不静默补 0 造出假价）。</li>
     * </ul>
     */
    private static PricingInfo pricingFrom(String providerId, String modelId, Cost cost) {
        if (cost == null) {
            return new PricingInfo(0, 0, 0, 0, List.of());
        }
        if (cost.input() == null || cost.output() == null) {
            return PricingInfo.UNKNOWN;
        }
        double cacheRead = cost.cacheRead() != null ? cost.cacheRead() : -1;
        double cacheWrite = cost.cacheWrite() != null ? cost.cacheWrite() : -1;
        var tiers = new ArrayList<PricingInfo.CostTier>();
        if (cost.tiers() != null) {
            for (var tier : cost.tiers()) {
                if (tier.inputTokensAbove() == null || tier.input() == null
                        || tier.output() == null || tier.cacheRead() == null
                        || tier.cacheWrite() == null) {
                    throw new IllegalStateException("models.json provider \"" + providerId
                        + "\", model \"" + modelId + "\": every cost tier needs all five numbers"
                        + " (inputTokensAbove / input / output / cacheRead / cacheWrite)");
                }
                tiers.add(new PricingInfo.CostTier(
                    tier.inputTokensAbove(), tier.input(), tier.output(),
                    tier.cacheRead(), tier.cacheWrite()));
            }
        }
        return new PricingInfo(cost.input(), cost.output(), cacheRead, cacheWrite, tiers);
    }

    /**
     * Map a models.json {@code compat} block onto {@link ModelCompat}.
     *
     * <p>{@code allowEmptySignature} is normalized — an absent block, or an absent key inside it,
     * both mean {@code false} (pi {@code anthropic-messages.ts:193} normalizes with
     * {@code ?? false}), so there is no third state to preserve (docs/31 §8.34.4 决策 3).</p>
     *
     * <p>⚠️ {@code requiresReasoningContentOnAssistantMessages} is **not** normalized the same way:
     * it stays three-state (absent ⇒ {@code null}), because its absent meaning is "detect from the
     * provider/baseUrl" and only an explicit value overrides that (pi's {@code getCompat} is
     * {@code explicit ?? detected}, {@code openai-completions.ts:1643}). Collapsing {@code null}
     * into {@code false} here would silently disable the deepseek replay path.</p>
     *
     * <p>⚠️ {@code supportsFinishReason} is normalized with the **opposite** default —
     * absent ⇒ {@code true}. That is not an oversight: pi's detected value for this flag is the
     * constant {@code true} ({@code detectCompat:1638}, no branch anywhere in the function), so
     * {@code explicit ?? detected} really does collapse to "absent ⇒ strict", with no third state
     * to carry. See {@link ModelCompat}'s javadoc for the three flags side by side.</p>
     */
    private static ModelCompat compatOf(CompatDef def) {
        if (def == null) {
            return ModelCompat.NONE;
        }
        return new ModelCompat(
            def.allowEmptySignature() != null && def.allowEmptySignature(),
            def.requiresReasoningContentOnAssistantMessages(),
            def.supportsFinishReason() == null || def.supportsFinishReason());
    }
}
