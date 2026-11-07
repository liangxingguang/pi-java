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
     * <p>L5 W6b（docs/25 §7；pi {@code resolveCliModel}）：解析策略整条收敛到
     * {@link CliModelResolution}——错误文案逐字、歧义显式、裸未知 id 报错而非
     * 兜底成默认 provider 的自定义模型（2026-11-05 裁决）。</p>
     *
     * <p>保留的 Java 独有便利臂（pi 无，既有 D）：整串是裸 provider 名（
     * {@code "anthropic"}／{@code "google:high"}）或 {@code "provider/"} 时取该
     * provider 的默认模型。该支路排在解析核之前，避免裸 {@code anthropic} 以
     * 子串命中 {@code openrouter/anthropic/*}。</p>
     *
     * @param pattern model pattern or ID (may be null → default provider)
     * @param defaultProvider fallback provider when the pattern is absent or unqualified
     * @return the resolved model ID
     * @throws IllegalStateException if the pattern cannot be resolved
     */
    public ModelId<?> resolve(String pattern, String defaultProvider) {
        return resolve(pattern, defaultProvider, provider -> false, false);
    }

    /**
     * 带可用性判据的 CLI 解析（pi {@code resolveCliModel} 的 {@code usable} 参）。
     *
     * @param pattern         模型引用（null/空白 ⇒ 默认 provider 的能力集解析）
     * @param defaultProvider provider 缺席时的回落（裸 provider 便利臂与默认能力集解析）
     * @param usable          provider 是否已有配置凭据（pi {@code hasConfiguredAuth}）
     * @param inlineApiKey    {@code --api-key} 是否显式给出（可用性恒 false）
     * @return 解析出的模型 id
     * @throws IllegalStateException 解析失败（文案即 CLI 错误文案）
     */
    public ModelId<?> resolve(String pattern, String defaultProvider,
                              java.util.function.Predicate<String> usable,
                              boolean inlineApiKey) {
        return resolve(pattern, defaultProvider, usable, inlineApiKey, provider -> false);
    }

    /**
     * 全参形：额外接受「运行时已知 provider」判据（pi 的 models 列表天然含已注册
     * provider 的模型；Java 的静态目录不含，故显式 {@code --provider} 指向运行时
     * 注册的 provider 时仍走自定义 id）。
     *
     * @param pattern       模型引用
     * @param defaultProvider 回落 provider
     * @param usable        provider 是否已有配置凭据
     * @param inlineApiKey  {@code --api-key} 是否给出
     * @param knownProvider provider 是否真实存在（运行时注册表）
     * @return 解析出的模型 id
     * @throws IllegalStateException 解析失败（文案即 CLI 错误文案）
     */
    public ModelId<?> resolve(String pattern, String defaultProvider,
                              java.util.function.Predicate<String> usable,
                              boolean inlineApiKey,
                              java.util.function.Predicate<String> knownProvider) {
        return resolve(pattern, defaultProvider, null, usable, inlineApiKey, knownProvider);
    }

    /**
     * 全参形（含显式 {@code --provider} 约束）。
     *
     * @param pattern        模型引用
     * @param defaultProvider 回落 provider（无显式约束时用于裸 provider 便利臂）
     * @param cliProvider    显式 {@code --provider}（null ≙ 未给）
     * @param usable         provider 是否已有配置凭据
     * @param inlineApiKey   {@code --api-key} 是否给出
     * @param knownProvider  provider 是否真实存在（运行时注册表）
     * @return 解析出的模型 id
     * @throws IllegalStateException 解析失败（文案即 CLI 错误文案）
     */
    public ModelId<?> resolve(String pattern, String defaultProvider, String cliProvider,
                              java.util.function.Predicate<String> usable,
                              boolean inlineApiKey,
                              java.util.function.Predicate<String> knownProvider) {
        if (pattern == null || pattern.isBlank()) {
            var preferred = Optional.ofNullable(defaultProvider)
                .filter(p -> !p.isBlank())
                .or(() -> Optional.of("google"));
            return resolve(Set.of(ModelCapability.TEXT), preferred);
        }
        var trimmed = pattern.trim();
        var available = catalog.listModels();

        // Java 既有便利臂（pi resolveCliModel 无）：整串是裸 provider 名（可带一个
        // 合法 :level 后缀，如 "google:high"）→ 该 provider 的默认模型/首条。
        var withoutLevel = stripValidLevelSuffix(trimmed);
        if (withoutLevel.indexOf('/') < 0) {
            var byProvider = available.stream()
                .filter(m -> m.id().provider().equalsIgnoreCase(withoutLevel))
                .toList();
            if (!byProvider.isEmpty()) {
                return defaultOrFirst(withoutLevel, byProvider);
            }
        }
        // Java 既有便利臂（pi 无）："provider/" → provider 默认模型。
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

        var result = CliModelResolution.resolve(new CliModelResolution.Request(
            cliProvider, trimmed, false, inlineApiKey, available,
            usable == null ? provider -> false : usable,
            knownProvider == null ? provider -> false : knownProvider));
        if (result.error() != null) {
            throw new IllegalStateException(result.error());
        }
        if (result.customId() != null) {
            if (result.warning() != null) {
                System.err.println(result.warning());
            }
            return result.customId();
        }
        return result.model().id();
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
