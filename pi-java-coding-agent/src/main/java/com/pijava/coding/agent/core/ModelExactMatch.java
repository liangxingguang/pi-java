package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Locale;

import com.pijava.ai.catalog.ModelInfo;

/**
 * pi {@code findExactModelReferenceMatch}（model-resolver.ts:88-130）逐字对位：
 * 全小写比较——{@code provider/id} 全形唯一命中 ⇒ 中（多命中 ⇒ null）；含
 * {@code /} 拆 provider+id 双侧精确唯一 ⇒ 中；裸 id 唯一 ⇒ 中；多命中/零命中
 * ⇒ null。
 */
public final class ModelExactMatch {

    private ModelExactMatch() {}

    /** pi 逐段（见类注）；{@code ref} 空白 ⇒ null。 */
    public static ModelInfo findExact(String ref, List<ModelInfo> available) {
        var trimmed = ref.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        var normalized = trimmed.toLowerCase(Locale.ROOT);

        var canonical = available.stream()
            .filter(m -> (m.id().provider() + "/" + m.id().modelName())
                .toLowerCase(Locale.ROOT).equals(normalized))
            .toList();
        if (canonical.size() == 1) {
            return canonical.getFirst();
        }
        if (canonical.size() > 1) {
            return null;
        }

        int slash = trimmed.indexOf('/');
        if (slash >= 0) {
            var provider = trimmed.substring(0, slash).trim();
            var modelId = trimmed.substring(slash + 1).trim();
            if (!provider.isEmpty() && !modelId.isEmpty()) {
                var providerMatches = available.stream()
                    .filter(m -> m.id().provider().equalsIgnoreCase(provider)
                        && m.id().modelName().equalsIgnoreCase(modelId))
                    .toList();
                if (providerMatches.size() == 1) {
                    return providerMatches.getFirst();
                }
                if (providerMatches.size() > 1) {
                    return null;
                }
            }
        }

        var idMatches = available.stream()
            .filter(m -> m.id().modelName().toLowerCase(Locale.ROOT).equals(normalized))
            .toList();
        return idMatches.size() == 1 ? idMatches.getFirst() : null;
    }
}
