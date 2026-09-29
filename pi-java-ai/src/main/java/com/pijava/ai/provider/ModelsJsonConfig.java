package com.pijava.ai.provider;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import com.pijava.ai.catalog.ChatTemplateKwargValue;
import com.pijava.ai.catalog.MaxTokensField;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.ThinkingFormat;
import com.pijava.ai.catalog.ThinkingTokenBudgetField;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.ModelsJsonSchema.CompatDef;
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
            pricing, thinkingLevelMapOf(model.thinkingLevelMap()), headers, samplingParams,
            compatOf(providerId, model, model.compat()),
            // 包 A-02（pi provider-composer.ts:142 的 `definition.api ?? providerConfig.api`）：
            // per-model api 缺席 ⇒ null ≙ provider 级协议（派发点在宿主，docs/59 §4.6）。
            modelApiOf(providerId, model.id(), model.api()));
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
     * both mean {@code false} (pi {@code anthropic-messages.ts:215} normalizes with
     * {@code ?? false}), so there is no third state to preserve (docs/31 §8.34.4 决策 3).
     * {@code supportsTemperature} is normalized with the **opposite** default ({@code true},
     * exactly like {@code supportsFinishReason}): its detected value is the constant
     * {@code true}, so only an explicit {@code false} suppresses the field.</p>
     *
     * <p>⚠️ {@code requiresReasoningContentOnAssistantMessages} is **not** normalized the same way:
     * it stays three-state (absent ⇒ {@code null}), because its absent meaning is "detect from the
     * provider/baseUrl" and only an explicit value overrides that (pi's {@code getCompat} is
     * {@code explicit ?? detected}, {@code openai-completions.ts:1700}). Collapsing {@code null}
     * into {@code false} here would silently disable the deepseek replay path.</p>
     *
     * <p>⚠️ <b>包 A7 的六个新键一律保持三态</b>（原样传 {@code Boolean}，不在这里归一）：
     * {@code supportsMidConvoSystemMessages}／{@code supportsMidConvoToolAdditions}／
     * {@code supportsMidConvoToolChanges}／{@code supportsAdditionalTools}／
     * {@code supportsToolSearch}／{@code supportsStore}／{@code supportsDeveloperRole}／
     * {@code supportsStrictMode}。理由是「用户没写」与「用户写了缺省值」必须可区分 ——
     * 前者要**让目录值与探测值活下来**，后者要**压掉**它们。今天在
     * {@code models[]} 里重定义一个内置模型时两者行为相同（整条替换、
     * {@code docs/53 §3 F7} 的 path C），但 A-16 把逐字段合并的两条路补上时立刻需要
     * （{@code docs/53 §9 R6}）。</p>
     *
     * <p>⚠️ {@code supportsFinishReason} 的归一方向与 {@code allowEmptySignature} **相反**
     * —— 不是疏忽：pi 对该标志的探测值是常量 {@code true}（{@code detectCompat:1640}，
     * 函数体内无任何分支碰它），所以 {@code explicit ?? detected} 真的塌缩成「缺席即严格」，
     * 没有第三种状态可保。</p>
     */
    private static ModelCompat compatOf(String providerId, ModelDef model, CompatDef def) {
        if (def == null) {
            return ModelCompat.NONE;
        }
        return new ModelCompat(
            def.allowEmptySignature() != null && def.allowEmptySignature(),
            def.requiresReasoningContentOnAssistantMessages(),
            def.supportsFinishReason() == null || def.supportsFinishReason(),
            def.forceAdaptiveThinking() != null && def.forceAdaptiveThinking(),
            def.supportsMidConvoSystemMessages(),
            def.supportsMidConvoToolAdditions(),
            def.supportsMidConvoToolChanges(),
            def.supportsAdditionalTools(),
            def.supportsToolSearch(),
            def.supportsTemperature() == null || def.supportsTemperature(),
            maxTokensFieldOf(providerId, model.id(), def.maxTokensField()),
            def.supportsStore(),
            def.supportsDeveloperRole(),
            def.supportsStrictMode(),
            // 包 A-01：两个 cache 门**原样透传可空值**（不像 `supportsTemperature` 那样在这里
            // 就塌成 true）—— 它们的缺省由**解析层**按车道补（`forAnthropic` 的 `?? true`），
            // 与 supportsStore/supportsDeveloperRole/supportsStrictMode 同形。
            def.supportsLongCacheRetention(),
            def.supportsCacheControlOnTools(),
            // 包 A-10：两个预算字段同样**原样透传可空值** —— 缺省由解析层按车道补
            // （completions 的探测值是 `false`／`undefined`，见 CompatResolver.forCompletions）。
            thinkingTokenBudgetFieldOf(providerId, model.id(), def.thinkingTokenBudgetField()),
            def.supportsThinkingTokenBudget(),
            // 包 A-10 第 6 步：`supportsMaxOutputTokens` 的缺省（`true`）由解析层按车道补
            // （CompatResolver.forResponses）⇒ 这里同样不归一 —— 「用户没写」必须能与
            // 「用户写了 true」区分开，因为只有前者会吃车道缺省。
            def.supportsMaxOutputTokens(),
            // 包 A-09：四个思考开关字段。`thinkingFormat`/`supportsReasoningEffort` 原样
            // 透传可空值（缺省由 CompatResolver.forCompletions 的探测补）；两个模板 map
            // 在这里就完成校验（$var 闭集、Literal 只收标量），null 由 ModelCompat 的
            // compact 构造器归一成空表。
            thinkingFormatOf(providerId, model.id(), def.thinkingFormat()),
            chatTemplateValuesOf(providerId, model.id(), "chatTemplateKwargs",
                def.chatTemplateKwargs()),
            chatTemplateValuesOf(providerId, model.id(), "chatTemplateArgs",
                def.chatTemplateArgs()),
            def.supportsReasoningEffort());
    }

    /**
     * models.json 的 {@code thinkingFormat} 串 ⇒ {@link ThinkingFormat}（包 A-09）。
     *
     * <p>⚠️ 未知取值**响亮抛错**，理由与 {@link #maxTokensFieldOf} 完全相同：它是十一值
     * 闭集，写错一个字母会让思考开关的形状静默换掉（甚至整个不发），而那种偏离没有任何
     * 其它症状（pi 的 zod 联合同样拒绝未知取值）。</p>
     */
    private static ThinkingFormat thinkingFormatOf(String providerId, String modelId,
                                                   String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return ThinkingFormat.parse(value).orElseThrow(() -> new IllegalStateException(
            "models.json provider \"" + providerId + "\", model \"" + modelId
                + "\": unknown compat.thinkingFormat \"" + value + "\" (expected one of: "
                + "openai, openrouter, deepseek, together, baseten, zai, qwen, "
                + "chat-template, qwen-chat-template, string-thinking, ant-ling)"));
    }

    /**
     * models.json 的 {@code chatTemplateKwargs}/{@code chatTemplateArgs} 对象 ⇒
     * {@link ChatTemplateKwargValue} 表（包 A-09）。缺席（{@code null}）原样返回 ——
     * 空表归一在 {@link ModelCompat} 的 compact 构造器（pi 的 {@code ?? {}}）。
     */
    private static Map<String, ChatTemplateKwargValue> chatTemplateValuesOf(
            String providerId, String modelId, String keyName, Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        var out = new LinkedHashMap<String, ChatTemplateKwargValue>();
        raw.forEach((key, value) ->
            out.put(key, kwargValueOf(providerId, modelId, keyName, key, value)));
        return out;
    }

    /**
     * 一个声明值的分类（pi {@code ChatTemplateKwargValue}，{@code types.ts:87-95}）：
     * 标量（含 {@code null}）⇒ {@link ChatTemplateKwargValue.Literal}；
     * {@code {$var, omitWhenOff?}} ⇒ {@link ChatTemplateKwargValue.Var}。
     *
     * <p>⚠️ 其余形状（数组、缺 {@code $var} 的对象、{@code $var} 不在三值闭集、
     * {@code omitWhenOff} 不是布尔）**响亮抛错** —— 与本文件 {@code maxTokensField}
     * 同口径：静默降级会静默改变线格（pi 的 zod 同样拒绝）。</p>
     *
     * <p>⚠️ {@code Literal(null)} 是**合法值**（「写这个键，值是 null」，pi
     * {@code resolveChatTemplateKwargValue:1050} 的早返回）—— 别把 JSON null 当缺席吞掉
     * （{@code docs/58} R11）。</p>
     */
    private static ChatTemplateKwargValue kwargValueOf(String providerId, String modelId,
                                                       String keyName, String key, Object raw) {
        var where = "models.json provider \"" + providerId + "\", model \"" + modelId
            + "\": compat." + keyName + "." + key;
        if (raw instanceof Map<?, ?> obj) {
            if (!(obj.get("$var") instanceof String varName)) {
                throw new IllegalStateException(where
                    + " is an object without a string \"$var\" (expected a scalar or "
                    + "{\"$var\": \"thinking.enabled\"|\"thinking.effort\"|\"thinking.budget\"})");
            }
            var var = ChatTemplateKwargValue.ThinkingVar.parse(varName).orElseThrow(
                () -> new IllegalStateException(where + " has an unknown $var \"" + varName
                    + "\" (expected \"thinking.enabled\", \"thinking.effort\" or "
                    + "\"thinking.budget\")"));
            var omit = obj.get("omitWhenOff");
            if (omit != null && !(omit instanceof Boolean)) {
                throw new IllegalStateException(where + ".omitWhenOff must be a boolean");
            }
            return new ChatTemplateKwargValue.Var(var, Boolean.TRUE.equals(omit));
        }
        if (raw == null || raw instanceof String || raw instanceof Number
                || raw instanceof Boolean) {
            return new ChatTemplateKwargValue.Literal(raw);
        }
        throw new IllegalStateException(where + " must be a string, number, boolean, null "
            + "or a {$var} object");
    }

    /**
     * models.json 的 {@code thinkingTokenBudgetField} 串 ⇒ {@link ThinkingTokenBudgetField}
     * （包 A-10）。
     *
     * <p>⚠️ 未知取值**响亮抛错**，理由与 {@link #maxTokensFieldOf} 完全相同：它是三值闭集，
     * 写错一个字母会让顶层预算字段的名字静默换掉，而那种偏离没有任何其它症状
     * （pi 的 zod 联合 {@code ThinkingTokenBudgetField} 同样拒绝未知取值）。</p>
     */
    private static ThinkingTokenBudgetField thinkingTokenBudgetFieldOf(
            String providerId, String modelId, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value) {
            case "thinking_token_budget" -> ThinkingTokenBudgetField.THINKING_TOKEN_BUDGET;
            case "thinking_budget" -> ThinkingTokenBudgetField.THINKING_BUDGET;
            case "thinking_budget_tokens" -> ThinkingTokenBudgetField.THINKING_BUDGET_TOKENS;
            default -> throw new IllegalStateException("models.json provider \"" + providerId
                + "\", model \"" + modelId + "\": unknown compat.thinkingTokenBudgetField \""
                + value + "\" (expected \"thinking_token_budget\", \"thinking_budget\" or "
                + "\"thinking_budget_tokens\")");
        };
    }

    /**
     * models.json 的 {@code maxTokensField} 串 ⇒ {@link MaxTokensField}
     * （{@code docs/53 §4.3}）。
     *
     * <p>⚠️ 未知取值**响亮抛错**，而不是像同文件其它未知键那样被忽略：它是二值闭集，
     * 写错一个字母会让线格上的**字段名**静默换掉（{@code max_completion_tokens} ↔
     * {@code max_tokens}），而那种偏离没有任何其它症状。pi 的 zod 联合同样会拒绝未知取值
     * （{@code model-config.ts} 的 {@code ProviderCompatSchema}）。</p>
     */
    private static MaxTokensField maxTokensFieldOf(String providerId, String modelId, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value) {
            case "max_tokens" -> MaxTokensField.MAX_TOKENS;
            case "max_completion_tokens" -> MaxTokensField.MAX_COMPLETION_TOKENS;
            default -> throw new IllegalStateException("models.json provider \"" + providerId
                + "\", model \"" + modelId + "\": unknown compat.maxTokensField \"" + value
                + "\" (expected \"max_tokens\" or \"max_completion_tokens\")");
        };
    }
}
