package com.pijava.ai.model;

import java.util.List;

/**
 * scope 解析结果（pi {@code ResolveModelScopeResult}，model-resolver.ts:271-274）。
 *
 * @param scopedModels 命中模型（保目录序、按 provider/id 去重）
 * @param diagnostics  警告（按模式处理顺序）
 */
public record ModelScopeResult(List<ScopedModel> scopedModels,
                               List<ModelScopeDiagnostic> diagnostics) {

    public ModelScopeResult {
        scopedModels = List.copyOf(scopedModels);
        diagnostics = List.copyOf(diagnostics);
    }
}
