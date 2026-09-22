package com.pijava.ai.catalog;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * 模型目录 wire DTO —— 扁平 JSON 形状，可被 Jackson 直接 round-trip。
 *
 * <p>{@code ModelInfo} 含泛型 {@code ModelId<?>}、sealed {@code ModelCapability}
 * 与 {@code ThinkingLevelMap}，无法直接 JSON 反序列化。远程目录与
 * {@code FileModelsStore} 持久化统一走本 DTO，再转换为 {@link ModelInfo}。</p>
 */
public record CatalogModel(
    String provider,
    String model,
    String displayName,
    List<String> capabilities,
    int maxInputTokens,
    int maxOutputTokens,
    boolean deprecated,
    double inputPrice,
    double outputPrice,
    Map<String, String> thinkingLevelMap
) {
    /**
     * 九参便捷构造（包H5 之前的老形状）—— {@code thinkingLevelMap} 缺席 ≙ 空表。
     */
    public CatalogModel(
        String provider,
        String model,
        String displayName,
        List<String> capabilities,
        int maxInputTokens,
        int maxOutputTokens,
        boolean deprecated,
        double inputPrice,
        double outputPrice
    ) {
        this(provider, model, displayName, capabilities, maxInputTokens, maxOutputTokens,
            deprecated, inputPrice, outputPrice, null);
    }

    /** 转换为 {@link ModelInfo}（thinkingLevelMap 缺席 ≙ 空表）。 */
    public ModelInfo toModelInfo() {
        Set<ModelCapability> caps = capabilities == null ? Set.of()
            : capabilities.stream()
                .map(CatalogModel::capability)
                .collect(Collectors.toSet());
        return new ModelInfo(
            ModelId.of(provider, model),
            displayName == null ? model : displayName,
            caps, maxInputTokens, maxOutputTokens, deprecated,
            new PricingInfo(inputPrice, outputPrice),
            thinkingLevelMapOf());
    }

    /**
     * 目录 wire 形状 ⇒ {@link ThinkingLevelMap}。
     *
     * <p>⚠️ <b>三态靠 Map 保住</b>：JSON {@code {"xhigh": null}} 与「没有 xhigh 键」在
     * Jackson 上都读成 {@code null}，所以这里是 {@code Map<String, String>} 而不是
     * 7 个字段（与 {@code ModelsJsonConfig} 同口径）。</p>
     */
    private ThinkingLevelMap thinkingLevelMapOf() {
        if (thinkingLevelMap == null || thinkingLevelMap.isEmpty()) {
            return ThinkingLevelMap.empty();
        }
        var entries = new java.util.LinkedHashMap<ModelThinkingLevel, java.util.Optional<String>>();
        for (var entry : thinkingLevelMap.entrySet()) {
            ModelThinkingLevel.parse(entry.getKey()).ifPresent(level ->
                entries.put(level, java.util.Optional.ofNullable(entry.getValue())));
        }
        return ThinkingLevelMap.of(entries);
    }

    /** 从 {@link ModelInfo} 转换（thinkingLevelMap 双向保留）。 */
    public static CatalogModel fromModelInfo(ModelInfo info) {
        return new CatalogModel(
            info.id().provider(),
            info.id().modelName(),
            info.displayName(),
            info.capabilities().stream().map(CatalogModel::capabilityName).toList(),
            info.maxInputTokens(),
            info.maxOutputTokens(),
            info.deprecated(),
            info.pricing().inputPrice(),
            info.pricing().outputPrice(),
            fromThinkingLevelMap(info.thinkingLevelMap()));
    }

    /** {@link ThinkingLevelMap} ⇒ 目录 wire 形状（空值写成 JSON {@code null}）。 */
    private static Map<String, String> fromThinkingLevelMap(ThinkingLevelMap map) {
        if (map == null || map.entries().isEmpty()) {
            return null;
        }
        var out = new java.util.LinkedHashMap<String, String>();
        for (var entry : map.entries().entrySet()) {
            out.put(entry.getKey().label(), entry.getValue().orElse(null));
        }
        return out;
    }

    private static String capabilityName(ModelCapability c) {
        return switch (c) {
            case ModelCapability.Text() -> "text";
            case ModelCapability.ImageInput() -> "imageInput";
            case ModelCapability.ImageOutput() -> "imageOutput";
            case ModelCapability.ToolUse() -> "toolUse";
            case ModelCapability.Thinking() -> "thinking";
            case ModelCapability.Streaming() -> "streaming";
            case ModelCapability.PromptCaching() -> "promptCaching";
            case ModelCapability.ComputerUse() -> "computerUse";
        };
    }

    private static ModelCapability capability(String name) {
        return switch (name) {
            case "imageInput" -> ModelCapability.IMAGE_INPUT;
            case "imageOutput" -> ModelCapability.IMAGE_OUTPUT;
            case "toolUse" -> ModelCapability.TOOL_USE;
            case "thinking" -> ModelCapability.THINKING;
            case "streaming" -> ModelCapability.STREAMING;
            case "promptCaching" -> ModelCapability.PROMPT_CACHING;
            case "computerUse" -> ModelCapability.COMPUTER_USE;
            default -> ModelCapability.TEXT;
        };
    }
}
