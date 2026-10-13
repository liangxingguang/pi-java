package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Optional;

import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.ModelScopeResolver;
import com.pijava.ai.model.ScopedModel;
import com.pijava.coding.agent.cli.Args;

/**
 * 启动期 scope 装配（pi {@code main.ts:813-816} 的 patterns 来源＋
 * {@code resolveModelScope} 打印面＋{@code buildSessionOptions:499-530} 初始模型选择）。
 *
 * <p>从 {@link AgentSession} 抽出以控制文件行数（CLAUDE.md ≤500 行）。</p>
 */
final class ModelScoping {

    private ModelScoping() {}

    /** 解析后的 scope（命中保目录序、去重）。 */
    record Scope(List<ScopedModel> models) {
        Scope {
            models = List.copyOf(models);
        }

        boolean isEmpty() {
            return models.isEmpty();
        }
    }

    static final Scope EMPTY = new Scope(List.of());

    /**
     * patterns 源＝{@code --models}（非空优先）否则 settings 的 enabled models
     * （pi {@code parsed.models ?? settingsManager.getEnabledModels()}）；空表＝不 scope。
     * 诊断在此处打 stderr（pi {@code console.warn(chalk.yellow("Warning: " + msg))}，
     * 无 TTY 着色）。
     */
    static Scope resolve(Args args, SettingsManager settings, ModelCatalog catalog) {
        var fromArgs = args.models();
        var patterns = fromArgs != null && !fromArgs.isEmpty()
            ? fromArgs
            : settings.accessors().getEnabledModels();
        if (patterns == null || patterns.isEmpty()) {
            return EMPTY;
        }
        var result = new ModelScopeResolver(catalog).resolve(patterns);
        for (var diagnostic : result.diagnostics()) {
            System.err.println("Warning: " + diagnostic.message());
        }
        return new Scope(result.scopedModels());
    }

    /**
     * 初始模型（pi main.ts:499-516）：saved 默认（provider/id 精确在目录且在 scope 内）
     * 优先——返回 scope 条目以携带其显式级别；否则取 scoped[0]；scope 空＝empty。
     */
    static Optional<ScopedModel> initial(Scope scope, String defaultProvider,
                                          String defaultModel, ModelCatalog catalog) {
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        if (defaultProvider != null && !defaultProvider.isBlank()
                && defaultModel != null && !defaultModel.isBlank()) {
            var savedId = ModelId.of(defaultProvider, defaultModel);
            if (catalog.find(savedId).isPresent()) {
                var inScope = scope.models().stream()
                    .filter(sm -> sm.model().id().equals(savedId))
                    .findFirst();
                if (inScope.isPresent()) {
                    return inScope;
                }
            }
        }
        return Optional.of(scope.models().getFirst());
    }
}
