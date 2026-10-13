package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Optional;

import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.ScopedModel;

/**
 * pi {@code AgentSession.cycleModel/_cycleScopedModel/_cycleAvailableModel}
 * （agent-session.ts:2519-2560）的正向轮转核。纯函数，不碰会话状态。
 *
 * <p>scope 非空：只在仍存在于目录的 scope 模型间轮转（models.json 刷新后消失的
 * 剔除）；≤1 ＝不动；当前模型不在表内 ⇒ 选下标 0；正向 {@code (i+1)%len}。
 * scope 空：在全目录序里轮转（pi {@code _cycleAvailableModel}）。</p>
 *
 * <p>docs/12 §4⑤：不做 auth 可用性过滤（Java 全产品无解析期 auth 门）。</p>
 */
final class ScopedModelCycler {

    private ScopedModelCycler() {}

    /** 下一个模型（与 current 相同表示「不动」）。 */
    static ModelId<?> next(ModelId<?> current, List<ScopedModel> scope, ModelCatalog catalog) {
        if (scope != null && !scope.isEmpty()) {
            var pool = scope.stream()
                .filter(sm -> catalog.find(sm.model().id()).isPresent())
                .toList();
            if (pool.size() <= 1) {
                return current;
            }
            int index = -1;
            for (int i = 0; i < pool.size(); i++) {
                if (pool.get(i).model().id().equals(current)) {
                    index = i;
                    break;
                }
            }
            if (index == -1) {
                index = 0;
            }
            return pool.get((index + 1) % pool.size()).model().id();
        }

        var all = catalog.listModels();
        if (all.isEmpty()) {
            return current;
        }
        int index = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(current)) {
                index = i;
                break;
            }
        }
        if (index == -1) {
            index = 0;   // pi：currentIndex===-1 → 0（自定义 id 首次轮转落到首条）。
        }
        return all.get((index + 1) % all.size()).id();
    }

    /** 切换到 {@code switchedTo} 后应显式套用的思考档（无＝继承当前档，pi undefined 语义）。 */
    static Optional<String> levelFor(ModelId<?> switchedTo, List<ScopedModel> scope) {
        if (scope == null) {
            return Optional.empty();
        }
        return scope.stream()
            .filter(sm -> sm.model().id().equals(switchedTo))
            .findFirst()
            .flatMap(ScopedModel::thinkingLevel);
    }
}
