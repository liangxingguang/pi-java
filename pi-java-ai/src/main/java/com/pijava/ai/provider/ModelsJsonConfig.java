package com.pijava.ai.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.ModelsJsonSchema.Cost;
import com.pijava.ai.provider.ModelsJsonSchema.ModelDef;
import com.pijava.ai.provider.ModelsJsonSchema.ProviderDef;
import com.pijava.ai.provider.ModelsJsonSchema.Root;
import com.pijava.ai.provider.builtin.ProviderCatalog;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

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
            result.add(buildProviderEntry(entry.getKey(), entry.getValue()));
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

    /** 为一个未命中内置 id 的 models.json 条目构建独立 provider（D-P1 接线用）。 */
    public static Provider buildProviderEntry(String id, ProviderDef def) {
        if (def.api() == null || def.api().isBlank()) {
            throw new IllegalStateException(
                "models.json provider \"" + id + "\": \"api\" is required"
                + " (e.g. \"openai-completions\" or \"anthropic-messages\")");
        }
        var protocol = Protocol.fromWire(def.api());
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

    /**
     * Convert one models.json model to {@link ModelInfo}. Package-visible for
     * {@link ModelJsonMerge} (D-P1, {@code 原 docs/65}).
     *
     * <p>D-P1：per-model {@code baseUrl} 三源（{@code model.baseUrl ?? def.baseUrl ??
     * null}）投影到 {@link ModelInfo#baseUrl()}（B136 结案）。</p>
     */
    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    static ModelInfo toModelInfo(String providerId, ProviderDef def, ModelDef model) {
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
        // B136：三源 model.baseUrl ?? def.baseUrl ?? null（null ⇒ 车道默认）。
        var effectiveBaseUrl = model.baseUrl() != null && !model.baseUrl().isBlank()
            ? model.baseUrl()
            : (def.baseUrl() != null && !def.baseUrl().isBlank() ? def.baseUrl() : null);
        return new ModelInfo(
            ModelId.of(providerId, model.id()),
            displayName, Set.copyOf(caps), contextWindow, maxTokens, false,
            pricing, thinkingLevelMapOf(model.thinkingLevelMap()), headers, samplingParams,
            // pi provider-composer.ts:171：compat ＝ mergeCompat(providerConfig.compat,
            // definition.compat)（D-P1 接线见 ModelJsonMerge）。
            ModelsJsonCompat.compatOf(providerId, model, model.compat(), def.compat()),
            // 包 A-02（pi provider-composer.ts:142 的 `definition.api ?? providerConfig.api`）：
            // per-model api 缺席 ⇒ null ≙ provider 级协议（派发点在宿主，原 docs/59 §4.6）。
            // 三源（pi provider-composer.ts:142）：model.api ?? provider.api ??
            // null（null ⇒ 合并器可再继承被替换模型的 api，R1）。
            modelApiOf(providerId, model.id(),
                nonBlank(model.api()) ? model.api() : def.api()),
            effectiveBaseUrl);
    }

    /**
     * models.json 的 per-model {@code api} ⇒ {@link ModelInfo#api()} 的线格名（包 A-02）。
     * 缺席/空串 ⇒ {@code null}（provider 默认协议）；未知取值**响亮抛错**——与
     * {@link #maxTokensFieldOf} 同口径：写错一个字母会让模型静默走错车道，而那种偏离
     * 没有任何其它症状（pi 的 TypeBox 对 api 只校验非空串，但 java 的派发要过
     * {@link Protocol#fromWire}，这里把失败提前到加载期、带上文件定位）。
     */
    private static String modelApiOf(String providerId, String modelId, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Protocol.fromWire(value).wireName();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("models.json provider \"" + providerId
                + "\", model \"" + modelId + "\": unknown api \"" + value + "\"", e);
        }
    }

    /**
     * models.json 的 {@code thinkingLevelMap} ⇒ {@link ThinkingLevelMap}
     * （pi {@code ThinkingLevelMapSchema}，{@code model-config.ts:55-64}）。
     *
     * <p>⚠️ <b>三态靠 {@code Map} 保住</b>：JSON {@code {"xhigh": null}} 与「没有 xhigh 键」
     * 在 Jackson 上都会读成 {@code null}，所以这里读的是 {@code Map<String, String>}
     * （<b>Map 保留「键在场」</b>）而不是 7 个 {@code String} 字段。
     * 值 {@code null} ⇒ {@link Optional#empty()} ≙ pi 的显式 {@code null}（不支持）。</p>
     *
     * <p>未知键被忽略（pi 的 schema 只列 {@code off|minimal|low|medium|high|xhigh|max}）。</p>
     */
    private static ThinkingLevelMap thinkingLevelMapOf(Map<String, String> def) {
        if (def == null || def.isEmpty()) {
            return ThinkingLevelMap.empty();
        }
        var entries = new LinkedHashMap<ModelThinkingLevel, Optional<String>>();
        for (var entry : def.entrySet()) {
            ModelThinkingLevel.parse(entry.getKey()).ifPresent(level ->
                entries.put(level, Optional.ofNullable(entry.getValue())));
        }
        return ThinkingLevelMap.of(entries);
    }

    /**
     * models.json 的 {@code cost} → {@link PricingInfo}（包 H1 步 6，
     * {@code 原 docs/42} 的 J10/J11 ＋ 裁决 B/F）。三个分支对应 pi 的三种语义：
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
}
