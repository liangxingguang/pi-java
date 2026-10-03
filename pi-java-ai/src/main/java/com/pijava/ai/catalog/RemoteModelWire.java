package com.pijava.ai.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * pi.dev 远程目录的 {@code Model} wire DTO（pi
 * {@code types.ts:959-992}）的宽松映射 —— 只承载 Java 转 {@link ModelInfo}
 * 所需的子集；未知字段（api/baseUrl/headers/samplingParams/promptCache/…）
 * 一律忽略，前向兼容 pi 字段增长。
 *
 * <p>pi 的 {@code id} 是纯模型名（不含 provider）；provider 由请求路径
 * 给定，{@link #toModelInfo(String)} 负责填充。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteModelWire(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("contextWindow") int contextWindow,
    @JsonProperty("maxTokens") int maxTokens,
    @JsonProperty("reasoning") Boolean reasoning,
    @JsonProperty("input") List<String> input,
    @JsonProperty("cost") RemoteCostWire cost,
    @JsonProperty("thinkingLevelMap") Map<String, String> thinkingLevelMap
) {

    /** 转成 {@link ModelInfo}（provider 由请求路径给定，不在 wire 里）。 */
    public ModelInfo toModelInfo(String providerId) {
        var capabilities = new java.util.LinkedHashSet<ModelCapability>(java.util.List.of(
            ModelCapability.TEXT, ModelCapability.TOOL_USE, ModelCapability.STREAMING));
        if (Boolean.TRUE.equals(reasoning)) {
            capabilities.add(ModelCapability.THINKING);
        }
        if (input != null && input.contains("image")) {
            capabilities.add(ModelCapability.IMAGE_INPUT);
        }
        var rates = cost != null ? cost : RemoteCostWire.FREE;
        return new ModelInfo(
            ModelId.of(providerId, id),
            name != null && !name.isBlank() ? name : id,
            capabilities,
            contextWindow,
            maxTokens,
            false,
            new PricingInfo(rates.input(), rates.output()),
            thinkingMap());
    }

    /** 反向：{@link ModelInfo} ⇒ pi wire（缓存写入方向）。 */
    static RemoteModelWire fromModelInfo(ModelInfo info) {
        boolean imageInput = info.capabilities().contains(ModelCapability.IMAGE_INPUT);
        var modalities = imageInput ? List.of("text", "image") : List.of("text");
        return new RemoteModelWire(
            info.id().modelName(),
            info.displayName(),
            info.maxInputTokens(),
            info.maxOutputTokens(),
            info.capabilities().contains(ModelCapability.THINKING),
            modalities,
            new RemoteCostWire(
                info.pricing().inputPrice(), info.pricing().outputPrice(), 0, 0),
            fromThinkingMap(info.thinkingLevelMap()));
    }

    /** {@link ThinkingLevelMap} ⇒ wire map（空值写 null，空 map ⇒ null）。 */
    private static Map<String, String> fromThinkingMap(ThinkingLevelMap map) {
        if (map == null || map.entries().isEmpty()) {
            return null;
        }
        var out = new LinkedHashMap<String, String>();
        for (var entry : map.entries().entrySet()) {
            out.put(entry.getKey().label(), entry.getValue().orElse(null));
        }
        return out;
    }

    /**
     * Wire {@code thinkingLevelMap} ⇒ {@link ThinkingLevelMap}；null 值保留为
     * {@code Optional.empty}（pi 语义：null 标记该级别不支持），未知级别名忽略。
     */
    private ThinkingLevelMap thinkingMap() {
        if (thinkingLevelMap == null || thinkingLevelMap.isEmpty()) {
            return ThinkingLevelMap.empty();
        }
        var entries = new LinkedHashMap<ModelThinkingLevel, java.util.Optional<String>>();
        for (var entry : thinkingLevelMap.entrySet()) {
            ModelThinkingLevel.parse(entry.getKey()).ifPresent(level ->
                entries.put(level, java.util.Optional.ofNullable(entry.getValue())));
        }
        return ThinkingLevelMap.of(entries);
    }
}
