package com.pijava.ai.model;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.catalog.ModelCatalog;

/**
 * Default model resolver: selects the first model that satisfies all required
 * capabilities, preferring the given provider family if specified.
 */
public final class DefaultModelResolver implements ModelResolver {

    private final ModelCatalog catalog;

    /**
     * Create a resolver backed by the given catalog.
     *
     * @param catalog the model catalog to resolve against
     */
    public DefaultModelResolver(ModelCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public ModelId<?> resolve(Set<ModelCapability> required, Optional<String> preferred) {
        List<ModelId<?>> candidates = catalog.listModels().stream()
            .filter(info -> info.capabilities().containsAll(required))
            .<ModelId<?>>map(info -> info.id())
            .toList();

        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                "No model found with required capabilities: " + required);
        }

        // Prefer the specified provider
        if (preferred.isPresent()) {
            for (var c : candidates) {
                if (c.provider().equalsIgnoreCase(preferred.get())) {
                    return c;
                }
            }
        }

        return candidates.getFirst();
    }

    /**
     * 某模型的<b>上下文窗口</b>（pi {@code Model.contextWindow}；目录侧字段是
     * {@code ModelInfo.maxInputTokens}，其 javadoc 释义即 "maximum context
     * window size in tokens"）。自动压缩的阈值门按<b>当前</b>模型查询此值
     * （package 3b，docs/31 §8.20）—— 目录里没有这个模型（自定义 id）⇒ 返回
     * 0，触发侧的 {@code contextWindow <= 0} 守卫会跳过自动压缩，与 pi 的
     * 守卫语义一致。
     */
    public int contextWindow(ModelId<?> id) {
        if (id == null) {
            return 0;
        }
        return catalog.listModels().stream()
            .filter(info -> id.equals(info.id()))
            .map(com.pijava.ai.catalog.ModelInfo::maxInputTokens)
            .findFirst()
            .orElse(0);
    }

    /**
     * 某模型的<b>输出上限</b>（pi {@code Model.maxTokens}，overflow-recovery 的
     * {@code isRecoverableLength} 操作数 —— 「钳制前的原始意图上限」，
     * {@code overflow.ts:171}；package 3c，docs/31 §8.21）。目录侧字段是
     * {@code ModelInfo.maxOutputTokens}；目录里没有这个模型 ⇒ 返回 0，
     * {@code desiredMaxOutput > 0} 判据随之恒 false ⇒ length 收尾不做
     * compact-and-retry，与裁决④一致。
     */
    public int maxOutputTokens(ModelId<?> id) {
        if (id == null) {
            return 0;
        }
        return catalog.listModels().stream()
            .filter(info -> id.equals(info.id()))
            .map(com.pijava.ai.catalog.ModelInfo::maxOutputTokens)
            .findFirst()
            .orElse(0);
    }

    /**
     * Resolve a CLI model pattern to a concrete model.
     *
     * <p>Phase 3: supports {@code "provider/model"}, bare {@code "model"}, and
     * an optional {@code ":thinking"} suffix (e.g. {@code "claude-sonnet:high"})
     * which is parsed off and ignored here — the thinking level is applied by
     * the CLI layer via {@code --thinking}. A bare provider name resolves to
     * the provider's first listed model.</p>
     *
     * @param pattern model pattern or ID (may be null → default provider)
     * @return the resolved model ID
     * @throws IllegalStateException if the pattern cannot be resolved
     */
    public ModelId<?> resolve(String pattern) {
        return resolve(pattern, null);
    }

    /**
     * Resolve a CLI model pattern to a concrete model, with a fallback provider.
     *
     * @param pattern model pattern or ID (may be null → default provider)
     * @param defaultProvider fallback provider when the pattern is absent or unqualified
     * @return the resolved model ID
     * @throws IllegalStateException if the pattern cannot be resolved
     */
    public ModelId<?> resolve(String pattern, String defaultProvider) {
        if (pattern == null || pattern.isBlank()) {
            var preferred = Optional.ofNullable(defaultProvider)
                .filter(p -> !p.isBlank())
                .or(() -> Optional.of("google"));
            return resolve(Set.of(ModelCapability.TEXT), preferred);
        }
        var trimmed = pattern.trim();
        var colon = trimmed.lastIndexOf(':');
        var base = colon > 0 ? trimmed.substring(0, colon) : trimmed;

        String provider = null;
        String name = base;
        var slash = base.indexOf('/');
        if (slash > 0) {
            provider = base.substring(0, slash);
            name = base.substring(slash + 1);
        }
        final String providerRef = provider;
        final String nameRef = name;

        if (nameRef.isEmpty()) {
            var byProvider = catalog.listModels().stream()
                .filter(m -> providerRef.equalsIgnoreCase(m.id().provider()))
                .toList();
            if (byProvider.isEmpty()) {
                throw new IllegalStateException("No models found for provider: " + providerRef);
            }
            return byProvider.getFirst().id();
        }

        var matches = catalog.listModels().stream()
            .filter(m -> m.id().modelName().equalsIgnoreCase(nameRef))
            .filter(m -> providerRef == null
                || m.id().provider().equalsIgnoreCase(providerRef))
            .toList();
        if (matches.isEmpty() && providerRef == null) {
            // Bare provider name (e.g. "google") — resolve to its first model.
            var byProvider = catalog.listModels().stream()
                .filter(m -> m.id().provider().equalsIgnoreCase(nameRef))
                .toList();
            if (!byProvider.isEmpty()) {
                return byProvider.getFirst().id();
            }
        }
        if (matches.isEmpty()) {
            // Not in the builtin catalog: accept an arbitrary model ID so users
            // can use any model their provider's API supports. The catalog only
            // provides listing/pricing/context metadata for known models.
            var effectiveProvider = providerRef != null
                ? providerRef
                : (defaultProvider != null && !defaultProvider.isBlank()
                    ? defaultProvider : "google");
            // Aligned with pi's resolveCliModel: warn that we're using a
            // custom (uncatalogued) model id rather than a known entry.
            System.err.println("Model \"" + nameRef
                + "\" not found for provider \"" + effectiveProvider
                + "\". Using custom model id.");
            return ModelId.of(effectiveProvider, nameRef);
        }
        return matches.getFirst().id();
    }
}
