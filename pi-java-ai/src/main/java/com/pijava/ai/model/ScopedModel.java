package com.pijava.ai.model;

import java.util.Objects;
import java.util.Optional;

import com.pijava.ai.catalog.ModelInfo;

/**
 * 一条 scope 命中（pi {@code ScopedModel}，model-resolver.ts:60-64）。
 *
 * @param model         命中的目录模型
 * @param thinkingLevel 模式里显式给出的思考档（{@code "provider/pattern:high"} 的 high）；
 *                      缺席 ≙ 「轮转到此模型时继承当前档」
 */
public record ScopedModel(ModelInfo model, Optional<String> thinkingLevel) {

    public ScopedModel {
        Objects.requireNonNull(model, "model");
        thinkingLevel = thinkingLevel == null ? Optional.empty() : thinkingLevel;
    }

    /** 无显式思考档的命中。 */
    public static ScopedModel of(ModelInfo model) {
        return new ScopedModel(model, Optional.empty());
    }

    /** 带显式思考档的命中。 */
    public static ScopedModel of(ModelInfo model, String thinkingLevel) {
        return new ScopedModel(model, Optional.ofNullable(thinkingLevel));
    }
}
