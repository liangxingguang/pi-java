package com.pijava.ai.catalog;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
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
    double outputPrice
) {
    /** 转换为 {@link ModelInfo}（thinkingLevelMap 回落 empty）。 */
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
            ThinkingLevelMap.empty());
    }

    /** 从 {@link ModelInfo} 转换（丢弃 thinkingLevelMap）。 */
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
            info.pricing().outputPrice());
    }

    private static String capabilityName(ModelCapability c) {
        return switch (c) {
            case ModelCapability.Text() -> "text";
            case ModelCapability.ImageInput() -> "imageInput";
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
            case "toolUse" -> ModelCapability.TOOL_USE;
            case "thinking" -> ModelCapability.THINKING;
            case "streaming" -> ModelCapability.STREAMING;
            case "promptCaching" -> ModelCapability.PROMPT_CACHING;
            case "computerUse" -> ModelCapability.COMPUTER_USE;
            default -> ModelCapability.TEXT;
        };
    }
}
