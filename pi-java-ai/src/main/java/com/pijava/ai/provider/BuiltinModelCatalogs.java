package com.pijava.ai.provider;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.PromptCacheLifetimes;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.ModelsJsonSchema.ModelDef;

/**
 * 内置模型目录的数据面 —— 17 个 npm {@code @earendil-works/pi-ai@1.1.0} 生成分片
 * （{@code resources/catalog/*.json}，逐字节复制，MIT，见同目录 {@code ATTRIBUTION.txt}）
 * 的装载器，对齐 pi 的 {@code flattenChatModelCatalog}（{@code model-catalog.ts:41-63}）。
 *
 * <p><b>拍平语义</b>：分片是 {@code { "<api 组键>": { "<type>:<id>": <Model> } }} 两层；
 * 装载跨 api 组、只收 {@code type:"chat"}（image/classifier 是 R5 面，不携）、以 id 为键、
 * 保文件序。{@link BuiltinCatalog#of} 以 {@code provider/id} 为键再建索引。</p>
 *
 * <p><b>严格源</b>：与 models.json 的用户数据不同，这些分片是 pinned 不可变输入，pi 的
 * {@code scripts/model-data.ts} 已在发布期校验过身份与必填项；装载时把关键三条
 * （键名 {@code chat:<id>}、{@code provider == 分片名}、{@code api == 组键}）与
 * chat 必填项（contextWindow/maxTokens/cost 四费率）再断言一遍，数据损坏即启动期响亮失败，
 * 不做 models.json 那种宽松默认。</p>
 *
 * <p>compat／pricing／thinkingLevelMap／api 的转换<b>复用 models.json 链</b>
 * （{@link ModelsJsonCompat}／{@link ModelsJsonConfig}）：生成数据与 models.json 同 schema。
 * 资源里出现但本版本不认识的 compat 键（{@code supportsMidConvoEffort} 等）经
 * {@code ignoreUnknown} 跳过，与 models.json 宽松加载同口径。</p>
 */
public final class BuiltinModelCatalogs {

    /** 携带的 17 个分片（＝已注册主流 provider 中 pi 有生成数据者；ollama 无分片）。 */
    static final List<String> SHARD_IDS = List.of(
        "anthropic", "openai", "google", "deepseek", "mistral",
        "moonshotai", "moonshotai-cn", "zai", "zai-coding-cn",
        "qwen-token-plan-cn", "xiaomi", "xiaomi-token-plan-cn",
        "minimax", "minimax-cn", "ant-ling", "openrouter", "xai");

    private static final Set<String> SHARD_ID_SET = Set.copyOf(SHARD_IDS);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ConcurrentHashMap<String, ModelCatalog> CACHE = new ConcurrentHashMap<>();

    private static volatile Instant generatedAtCache;

    private BuiltinModelCatalogs() {}

    /** 携带分片的 provider id（文件序）；资源完整性夹具用。 */
    public static List<String> shardIds() {
        return SHARD_IDS;
    }

    /**
     * 某 provider 的拍平 chat 目录；懒加载、缓存、不可变。
     *
     * @throws IllegalStateException 分片不在携带集合、资源缺席或数据身份校验失败
     */
    public static ModelCatalog catalog(String providerId) {
        if (!SHARD_ID_SET.contains(providerId)) {
            throw new IllegalArgumentException("not a builtin catalog provider: " + providerId);
        }
        return CACHE.computeIfAbsent(providerId, BuiltinModelCatalogs::load);
    }

    /**
     * 分片数据的生成时刻（{@code .manifest.json#generatedAt}）—— 远程目录守卫
     * （coding-agent {@code RemoteCatalogProvider}）丢弃 Last-Modified 不晚于此值的
     * overlay，故必须与携带数据同源，不能是「代码编写时刻」。
     */
    public static Instant generatedAt() {
        var cached = generatedAtCache;
        if (cached != null) {
            return cached;
        }
        synchronized (BuiltinModelCatalogs.class) {
            if (generatedAtCache == null) {
                generatedAtCache = readGeneratedAt();
            }
            return generatedAtCache;
        }
    }

    private static ModelCatalog load(String providerId) {
        try (var in = openResource(providerId + ".json")) {
            return BuiltinCatalog.of(loadChatModels(providerId, MAPPER.readTree(in)));
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to read builtin model catalog: catalog/" + providerId + ".json", e);
        }
    }

    /**
     * 把一个分片根节点拍平成 chat {@link ModelInfo} 列表（保文件序）。包可见供夹具
     * 直接喂身份损坏的 {@link JsonNode}，不必伪造资源文件。
     */
    static List<ModelInfo> loadChatModels(String providerId, JsonNode root) {
        if (providerId == null || !root.isObject()) {
            throw new IllegalStateException("builtin catalog \"" + providerId
                + "\": shard root must be a JSON object");
        }
        var models = new ArrayList<ModelInfo>();
        var groups = root.fields();
        while (groups.hasNext()) {
            var group = groups.next();
            var groupKey = group.getKey();
            if (!group.getValue().isObject()) {
                throw new IllegalStateException("builtin catalog \"" + providerId
                    + "\": api group \"" + groupKey + "\" must be a JSON object");
            }
            var entries = group.getValue().fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                if ("chat".equals(entry.getValue().path("type").asText(""))) {
                    models.add(chatModel(providerId, groupKey, entry.getKey(), entry.getValue()));
                }
            }
        }
        return List.copyOf(models);
    }

    private static ModelInfo chatModel(String providerId, String groupKey,
                                        String entryKey, JsonNode node) {
        ModelDef def;
        try {
            def = MAPPER.treeToValue(node, ModelDef.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("builtin catalog \"" + providerId
                + "\": cannot parse chat entry \"" + entryKey + "\"", e);
        }
        var where = "builtin catalog \"" + providerId + "\"";
        if (def == null || def.id() == null || def.id().isBlank()) {
            throw new IllegalStateException(where + ": chat entry \"" + entryKey
                + "\" has no id");
        }
        // model-data.ts validator 的关键身份三条。
        if (!entryKey.equals("chat:" + def.id())) {
            throw new IllegalStateException(where + ": entry key \"" + entryKey
                + "\" does not match chat:" + def.id());
        }
        var wireProvider = node.path("provider").asText("");
        if (!providerId.equals(wireProvider)) {
            throw new IllegalStateException(where + ", model \"" + def.id()
                + "\": provider field \"" + wireProvider + "\" does not match shard id");
        }
        if (def.api() == null || def.api().isBlank()) {
            throw new IllegalStateException(where + ", model \"" + def.id()
                + "\": chat entry requires an api group");
        }
        if (!groupKey.equals(def.api())) {
            throw new IllegalStateException(where + ", model \"" + def.id()
                + "\": api \"" + def.api() + "\" does not match group key \"" + groupKey + "\"");
        }
        if (def.contextWindow() == null || def.maxTokens() == null) {
            throw new IllegalStateException(where + ", model \"" + def.id()
                + "\": chat entry requires contextWindow and maxTokens");
        }
        var cost = def.cost();
        if (cost == null || cost.input() == null || cost.output() == null
                || cost.cacheRead() == null || cost.cacheWrite() == null) {
            throw new IllegalStateException(where + ", model \"" + def.id()
                + "\": chat entry requires all four cost rates");
        }

        var caps = new LinkedHashSet<ModelCapability>();
        caps.add(ModelCapability.TEXT);
        caps.add(ModelCapability.TOOL_USE);
        caps.add(ModelCapability.STREAMING);
        if (Boolean.TRUE.equals(def.reasoning())) {
            caps.add(ModelCapability.THINKING);
        }
        if (def.input() != null && def.input().contains("image")) {
            caps.add(ModelCapability.IMAGE_INPUT);
        }
        // 不派生 PROMPT_CACHING/COMPUTER_USE —— pi 无此二布尔，Java 车道也无消费者。
        ModelCompat compat = ModelsJsonCompat.compatOf(providerId, def, def.compat(), null);
        var displayName = def.name() != null && !def.name().isBlank() ? def.name() : def.id();
        Map<String, String> headers = def.headers() != null ? def.headers() : Map.of();
        Map<String, Object> samplingParams =
            def.samplingParams() != null ? def.samplingParams() : Map.of();
        return new ModelInfo(
            ModelId.of(providerId, def.id()),
            displayName, Set.copyOf(caps),
            def.contextWindow(), def.maxTokens(), false,
            ModelsJsonConfig.pricingFrom(providerId, def.id(), cost),
            ModelsJsonConfig.thinkingLevelMapOf(def.thinkingLevelMap()),
            headers, samplingParams, compat,
            ModelsJsonConfig.modelApiOf(providerId, def.id(), def.api()),
            def.baseUrl(),
            // 批 3a（#86/#90/#91）：生成分片三字段与 models.json 同 schema、同转换。
            PromptCacheLifetimes.fromWire(def.promptCache()),
            ModelsJsonConfig.inputLimitsFrom(def.inputLimits()),
            ModelsJsonConfig.samplingParamsByThinkingLevelOf(
                def.samplingParamsByThinkingLevel()));
    }

    private static InputStream openResource(String fileName) {
        var in = BuiltinModelCatalogs.class.getResourceAsStream("/catalog/" + fileName);
        if (in == null) {
            throw new IllegalStateException("builtin model catalog missing on classpath: catalog/"
                + fileName + " (see resources/catalog/ATTRIBUTION.txt)");
        }
        return in;
    }

    private static Instant readGeneratedAt() {
        try (var in = openResource(".manifest.json")) {
            JsonNode root = MAPPER.readTree(in);
            var raw = root.path("generatedAt").asText("");
            if (raw.isEmpty()) {
                throw new IllegalStateException(
                    "catalog/.manifest.json has no generatedAt field");
            }
            if (root.path("schemaVersion").asInt(-1) != 6) {
                throw new IllegalStateException(
                    "catalog/.manifest.json schemaVersion is not 6");
            }
            return Instant.parse(raw);
        } catch (IOException | java.time.format.DateTimeParseException e) {
            throw new IllegalStateException(
                "Failed to read catalog/.manifest.json generatedAt", e);
        }
    }
}
