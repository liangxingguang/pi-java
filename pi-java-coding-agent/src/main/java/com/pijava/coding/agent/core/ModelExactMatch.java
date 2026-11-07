package com.pijava.coding.agent.core;

import java.util.List;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelReferenceResolver;

/**
 * pi {@code findExactModelReferenceMatch}（model-resolver.ts:179-190）对位：全小写
 * 精确引用唯一命中 ⇒ 中；多命中/零命中 ⇒ null。
 *
 * <p>L5 W6（docs/25 §7）：策略本体在 {@link ModelReferenceResolver}——本类是
 * {@code thinkingSuffix=none, fuzzy=false} 的薄包装（pi 的同一薄包装），不再自带
 * 一套匹配实现。{@code /model} 命令消费它。</p>
 */
public final class ModelExactMatch {

    private ModelExactMatch() {}

    /**
     * 精确引用匹配。
     *
     * @param ref       引用（{@code provider/id}、裸 id 或显示名；空白 ⇒ null）
     * @param available 候选模型
     * @return 唯一命中，否则 {@code null}
     */
    public static ModelInfo findExact(String ref, List<ModelInfo> available) {
        return ModelReferenceResolver.findExactMatch(ref, available).orElse(null);
    }
}
