package com.pijava.coding.agent.extension;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.core.virtual.VirtualModelDefinition;

/**
 * docs/50 §2.1：pi {@code Model} 的只读投影 —— 只含示例实际读取的字段
 * （{@code provider}/{@code id}/{@code contextWindow}）与 {@code complete}
 * 需要的身份/上限。
 *
 * @param id           模型标识
 * @param name         显示名
 * @param contextWindow 上下文窗口（{@link ModelInfo#maxInputTokens()} /
 *                      {@link VirtualModelDefinition#contextWindow()}）
 * @param maxTokens     单次输出上限（{@link ModelInfo#maxOutputTokens()} /
 *                      {@link VirtualModelDefinition#maxTokens()}）
 */
public record ExtensionModelInfo(
        ModelId<?> id, String name, int contextWindow, int maxTokens) {

    /** pi {@code model.provider}。 */
    public String provider() {
        return id.provider();
    }

    /** pi {@code model.id}（模型名，不是 provider）。 */
    public String modelId() {
        return id.modelName();
    }

    /** 目录模型投影。 */
    public static ExtensionModelInfo from(ModelInfo info) {
        return new ExtensionModelInfo(info.id(), info.displayName(),
            info.maxInputTokens(), info.maxOutputTokens());
    }

    /** 虚拟模型投影（虚拟表优先，与 pi runtime 同一模型表）。 */
    public static ExtensionModelInfo from(VirtualModelDefinition definition) {
        return new ExtensionModelInfo(
            ModelId.of(definition.provider(), definition.id()),
            definition.name(), definition.contextWindow(), definition.maxTokens());
    }
}
