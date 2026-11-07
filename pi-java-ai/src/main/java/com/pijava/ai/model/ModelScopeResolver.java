package com.pijava.ai.model;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;

/**
 * pi {@code resolveModelScopeFromModels}（model-resolver.ts:332-405）的 Java 面。
 *
 * <p>逐模式处理：含 {@code *?[} 的走 glob（先剥合法 {@code :level} 后缀；<b>精确引用
 * 先于 glob</b>，故 id 带方括号的 {@code custom/bracketed-model[1m]} 仍走精确），其余走
 * {@link ModelReferenceResolver} 宽容模式；{@code provider/id} 全限定与裸 id 双车道 glob；
 * 输出保目录序、按 provider/id 去重；不抛错，只产 {@link ModelScopeDiagnostic}。</p>
 *
 * <p>L5 W6a（docs/25 §7）：歧义 ⇒ <b>加入所有候选</b>＋一条 {@code ambiguous} 警告
 * （pi :344-353），不再落成 no-match。</p>
 */
public final class ModelScopeResolver {

    private final List<ModelInfo> models;

    public ModelScopeResolver(ModelCatalog catalog) {
        this.models = List.copyOf(catalog.listModels());
    }

    private ModelScopeResolver(List<ModelInfo> models) {
        this.models = List.copyOf(models);
    }

    /** 测试/聚合用：显式模型集（调用方负责保序）。 */
    public static ModelScopeResult resolve(List<String> patterns, List<ModelInfo> models) {
        return new ModelScopeResolver(models).resolve(patterns);
    }

    /** 解析一组 {@code --models}/settings 模式。 */
    public ModelScopeResult resolve(List<String> patterns) {
        var scoped = new ArrayList<ScopedModel>();
        var diagnostics = new ArrayList<ModelScopeDiagnostic>();

        for (var pattern : patterns) {
            if (ModelGlob.isGlob(pattern)) {
                resolveGlob(pattern, scoped, diagnostics);
            } else {
                resolvePlain(pattern, scoped, diagnostics);
            }
        }
        return new ModelScopeResult(scoped, diagnostics);
    }

    private void resolveGlob(String pattern, List<ScopedModel> scoped,
                             List<ModelScopeDiagnostic> diagnostics) {
        var colon = pattern.lastIndexOf(':');
        var globPattern = pattern;
        String level = null;
        if (colon != -1) {
            var suffix = pattern.substring(colon + 1);
            if (ModelPatterns.isValidThinkingLevel(suffix)) {
                level = suffix;
                globPattern = pattern.substring(0, colon);
            }
        }

        // 精确引用先于 glob（pi :362-370）：id 带 '[' 的 bracketed 模型不在此被当字符类。
        var exact = ModelReferenceResolver.resolve(
            ModelReferenceResolver.Request.exact(globPattern), models, provider -> true);
        if (exact instanceof ModelReferenceResolver.Result.Resolved resolved) {
            addIfAbsent(scoped, resolved.model(), level);
            return;
        }
        if (exact instanceof ModelReferenceResolver.Result.Ambiguous ambiguous) {
            diagnostics.add(ModelScopeDiagnostic.ambiguous(pattern,
                ambiguous.candidates().stream().map(m -> m.id().provider()).toList()));
            for (var model : ambiguous.candidates()) {
                addIfAbsent(scoped, model, level);
            }
            return;
        }

        final var glob = globPattern;
        var matching = models.stream().filter(m -> {
            var fullId = m.id().provider() + "/" + m.id().modelName();
            return ModelGlob.matches(fullId, glob)
                || ModelGlob.matches(m.id().modelName(), glob);
        }).toList();

        if (matching.isEmpty()) {
            diagnostics.add(ModelScopeDiagnostic.noMatch(pattern));
            return;
        }
        for (var model : matching) {
            addIfAbsent(scoped, model, level);
        }
    }

    private void resolvePlain(String pattern, List<ScopedModel> scoped,
                              List<ModelScopeDiagnostic> diagnostics) {
        // pi :378-398：lenient＋fuzzy；warning 逐字进 invalid-thinking-level 码。
        var result = ModelReferenceResolver.resolve(
            ModelReferenceResolver.Request.lenient(pattern), models, provider -> true);
        if (result instanceof ModelReferenceResolver.Result.NotFound) {
            diagnostics.add(ModelScopeDiagnostic.noMatch(pattern));
            return;
        }
        if (result instanceof ModelReferenceResolver.Result.Resolved resolved) {
            if (resolved.warning() != null) {
                diagnostics.add(new ModelScopeDiagnostic(
                    ModelScopeDiagnostic.Code.INVALID_THINKING_LEVEL, resolved.warning(), pattern));
            }
            addIfAbsent(scoped, resolved.model(), resolved.thinkingLevel());
            return;
        }
        var ambiguous = (ModelReferenceResolver.Result.Ambiguous) result;
        if (ambiguous.warning() != null) {
            diagnostics.add(new ModelScopeDiagnostic(
                ModelScopeDiagnostic.Code.INVALID_THINKING_LEVEL, ambiguous.warning(), pattern));
        }
        diagnostics.add(ModelScopeDiagnostic.ambiguous(pattern,
            ambiguous.candidates().stream().map(m -> m.id().provider()).toList()));
        for (var model : ambiguous.candidates()) {
            addIfAbsent(scoped, model, ambiguous.thinkingLevel());
        }
    }

    /** 保序去重（pi modelsAreEqual＝provider/id 相等）。 */
    private static void addIfAbsent(List<ScopedModel> scoped, ModelInfo model, String level) {
        var duplicate = scoped.stream().anyMatch(sm -> sm.model().id().equals(model.id()));
        if (!duplicate) {
            scoped.add(ScopedModel.of(model, level));
        }
    }
}
