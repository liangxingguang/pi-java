package com.pijava.ai.model;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.catalog.ModelInfo;

/**
 * pi {@code packages/coding-agent/src/core/model-resolver.ts} 的<b>薄壳</b>。
 *
 * <p>L5 W6a（docs/25 §7）：真正的解析策略已收敛到 {@link ModelReferenceResolver}
 * ——候选先收集后选 provider、可用性只破平、歧义显式报出。本类只保留既有调用点所需
 * 的三个入口，全部委托新核，避免两套语义并存。</p>
 */
final class ModelPatterns {

    /** pi VALID_THINKING_LEVELS（cli/args.ts:61-65），逐字、<b>大小写敏感</b>。 */
    static final Set<String> THINKING_LEVELS =
        Set.of("off", "minimal", "low", "medium", "high", "xhigh", "max");

    private ModelPatterns() {}

    static boolean isValidThinkingLevel(String s) {
        return s != null && THINKING_LEVELS.contains(s);
    }

    /** pi {@code findExactModelReferenceMatch}：{@code none}＋非 fuzzy 的薄包装。 */
    static Optional<ModelInfo> findExactReference(String reference, List<ModelInfo> models) {
        return ModelReferenceResolver.findExactMatch(reference, models);
    }

    /**
     * pi {@code isAlias}（model-resolver.ts:65-74）：{@code -latest} 尾缀算 alias；
     * 否则以 {@code -YYYYMMDD} 结尾的是 dated，其余皆 alias。
     */
    static boolean isAlias(String id) {
        if (id.endsWith("-latest")) {
            return true;
        }
        return !id.matches(".*-\\d{8}$");
    }

    /**
     * 兼容壳（存量调用点）：精确引用优先，其次子串/显示名。
     *
     * @deprecated 新代码请直接用 {@link ModelReferenceResolver#resolve}；本方法只为
     *     {@code DefaultModelResolver} 与既有测试保留。
     */
    @Deprecated(forRemoval = false)
    static Optional<ModelInfo> tryMatch(String pattern, List<ModelInfo> models) {
        var result = ModelReferenceResolver.resolve(ModelReferenceResolver.Request.lenient(pattern),
            models, provider -> true);
        return result instanceof ModelReferenceResolver.Result.Resolved resolved
            ? Optional.of(resolved.model()) : Optional.empty();
    }

    /** 解析结果（pi {@code ParsedModelResult} 的 java 形）。 */
    record ParsedPattern(Optional<ModelInfo> model,
                         Optional<String> level,
                         Optional<String> warning) {
        static ParsedPattern none() {
            return new ParsedPattern(Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /**
     * 兼容壳：{@code strict=true} ⇒ pi {@code strict} 尾缀策略（非法后缀留作 id 一部分，
     * 结果 empty）；{@code false} ⇒ {@code lenient}（警告＋前缀递归）。
     */
    static ParsedPattern parse(String pattern, List<ModelInfo> models, boolean strict) {
        var request = strict
            ? ModelReferenceResolver.Request.strict(pattern, null)
            : ModelReferenceResolver.Request.lenient(pattern);
        var result = ModelReferenceResolver.resolve(request, models, provider -> true);
        return switch (result) {
            case ModelReferenceResolver.Result.Resolved resolved -> new ParsedPattern(
                Optional.of(resolved.model()),
                Optional.ofNullable(resolved.thinkingLevel()),
                Optional.ofNullable(resolved.warning()));
            case ModelReferenceResolver.Result.Ambiguous ambiguous -> new ParsedPattern(
                // 旧壳无歧义表达；取首候选（pi 的 strict 路径不会在此分裂）。
                ambiguous.candidates().stream().findFirst(),
                Optional.ofNullable(ambiguous.thinkingLevel()),
                Optional.ofNullable(ambiguous.warning()));
            case ModelReferenceResolver.Result.NotFound notFound -> ParsedPattern.none();
        };
    }
}
