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
            var family = preferred.get();
            // 批 3a：pi defaultModelPerProvider 防回归切片 —— 裸 provider 启动选的是
            // 常量表默认模型（DefaultModelIds），不是分片首条（google 首条是 deep-research
            // 预览模型）。默认 id 缺席或不满足能力集时回退既有「首条候选」。
            var defaultId = DefaultModelIds.forProvider(family)
                .map(id -> ModelId.of(family, id))
                .flatMap(catalog::find)
                .filter(info -> info.capabilities().containsAll(required));
            if (defaultId.isPresent()) {
                return defaultId.get().id();
            }
            for (var c : candidates) {
                if (c.provider().equalsIgnoreCase(family)) {
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
     * （package 3b，原 docs/31 §8.20）—— 目录里没有这个模型（自定义 id）⇒ 返回
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
     * {@code overflow.ts:171}；package 3c，原 docs/31 §8.21）。目录侧字段是
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
     * 模型是否支持推理/思考（pi {@code Model.reasoning}，{@code types.ts:1120}；
     * Java 事实源是 {@link ModelInfo#capabilities()} 的
     * {@link ModelCapability#THINKING}，与 {@code ModelThinkingLevels} 同门）。
     * 未编目（自定义 id）⇒ {@code false}。
     */
    public boolean supportsThinking(ModelId<?> id) {
        if (id == null) {
            return false;
        }
        return catalog.listModels().stream()
            .filter(info -> id.equals(info.id()))
            .findFirst()
            .map(info -> info.capabilities().contains(ModelCapability.THINKING))
            .orElse(false);
    }

    /**
     * Resolve a CLI model pattern to a concrete model.
     *
     * <p>批 3b 起匹配核委托 {@link ModelPatterns}（pi model-resolver.ts）：精确引用
     * （{@code provider/id}→provider 限定→裸 id，唯一性裁决）→ id/显示名子串 →
     * alias 优先、dated 降序；合法 {@code ":level"} 后缀照切，<b>非法后缀留在
     * 自定义 id 里</b>（CLI 严格模式，pi 测试「invalid thinking suffix on custom
     * model is treated as part of model id」）。裸 provider 名仍走 Java 既有的
     * 默认模型支路（pi 无此支路，{@link #defaultOrFirst}）。</p>
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
        var available = catalog.listModels();

        // Java 既有支路（pi resolveCliModel 无）：整串是裸 provider 名（可带一个
        // 合法 :level 后缀，如 "google:high"）→ 该 provider 的默认模型/首条。
        // 必须排在子串核之前：裸 "anthropic" 会子串命中 openrouter/anthropic/* 模型。
        var withoutLevel = stripValidLevelSuffix(trimmed);
        if (withoutLevel.indexOf('/') < 0) {
            var byProvider = available.stream()
                .filter(m -> m.id().provider().equalsIgnoreCase(withoutLevel))
                .toList();
            if (!byProvider.isEmpty()) {
                return defaultOrFirst(withoutLevel, byProvider);
            }
        }

        // Java 既有支路（pi 无）："provider/" → provider 默认模型；provider 下无模型
        // 时抛错（保留既有形状）。同样排在子串核之前。
        var firstSlash = withoutLevel.indexOf('/');
        if (firstSlash >= 0 && withoutLevel.substring(firstSlash + 1).isEmpty()) {
            var providerRef = withoutLevel.substring(0, firstSlash);
            var byProvider = available.stream()
                .filter(m -> providerRef.equalsIgnoreCase(m.id().provider()))
                .toList();
            if (byProvider.isEmpty()) {
                throw new IllegalStateException("No models found for provider: " + providerRef);
            }
            return defaultOrFirst(providerRef, byProvider);
        }

        // pi 匹配核（CLI 严格模式：全串先试，保护 openrouter :batch/:exacto 冒号 id；
        // 非法 :后缀不切，整串留作下面的自定义 id 兜底）。
        var parsed = ModelPatterns.parse(trimmed, available, true);
        if (parsed.model().isPresent()) {
            return parsed.model().get().id();
        }

        // Not in the builtin catalog: accept an arbitrary model ID so users
        // can use any model their provider's API supports. The catalog only
        // provides listing/pricing/context metadata for known models.
        // pi resolveCliModel:567-580 —— 兜底前只剥<b>合法</b> level 后缀，非法后缀
        // 留在 id 里（"x:random" 自定义 id 即 "x:random"）。
        var fallbackPattern = stripValidLevelSuffix(trimmed);
        String effectiveProvider;
        String fallbackId;
        var slash = fallbackPattern.indexOf('/');
        if (slash > 0) {
            effectiveProvider = fallbackPattern.substring(0, slash);
            fallbackId = fallbackPattern.substring(slash + 1);
        } else {
            effectiveProvider = defaultProvider != null && !defaultProvider.isBlank()
                ? defaultProvider : "google";
            fallbackId = fallbackPattern;
        }
        // Aligned with pi's resolveCliModel: warn that we're using a
        // custom (uncatalogued) model id rather than a known entry.
        System.err.println("Model \"" + fallbackId
            + "\" not found for provider \"" + effectiveProvider
            + "\". Using custom model id.");
        return ModelId.of(effectiveProvider, fallbackId);
    }

    /** 尾缀是 7 档合法思考档（且冒号不在首位）时剥掉；否则原样返回。 */
    private static String stripValidLevelSuffix(String pattern) {
        var colon = pattern.lastIndexOf(':');
        if (colon > 0 && ModelPatterns.isValidThinkingLevel(pattern.substring(colon + 1))) {
            return pattern.substring(0, colon);
        }
        return pattern;
    }

    /**
     * pi {@code defaultModelPerProvider}（model-resolver.ts:16-58）：默认表命中且该 id
     * 在目录中 ⇒ 返回它；否则取 provider 首条（表外 provider 如 ollama 的既有行为）。
     */
    private ModelId<?> defaultOrFirst(String provider, List<com.pijava.ai.catalog.ModelInfo> byProvider) {
        var defaultId = DefaultModelIds.forProvider(provider);
        if (defaultId.isPresent()) {
            var hit = catalog.find(ModelId.of(provider, defaultId.get()));
            if (hit.isPresent()) {
                return hit.get().id();
            }
        }
        return byProvider.getFirst().id();
    }
}
