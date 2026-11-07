package com.pijava.ai.model;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import com.pijava.ai.catalog.ModelInfo;

/**
 * L5 W6b（docs/25 §7；pi {@code core/model-resolver.ts:450-530}
 * {@code resolveCliModel} 逐字）：CLI 单模型解析——错误文案逐字，不再兜底到默认
 * provider 的自定义 id（模型可见行为变化，2026-11-05 已裁决按 pi 语义执行）。
 *
 * <ul>
 *   <li>{@code --provider} 未知 ⇒ {@code Unknown provider "<p>". Use --list-models ...}；</li>
 *   <li>歧义 ⇒ {@code Model "<m>" is ambiguous across providers: <provider/id 排序列>.
 *       <authHint> Use --provider or provider/model.}，authHint 三臂；</li>
 *   <li>无 target（没有任何 provider 提示）⇒ {@code Model "<m>" not found.
 *       Use --list-models ...}；</li>
 *   <li>有 target ⇒ 自定义 id 警告 {@code Model "<id>" not found for provider "<p>".
 *       Using custom model id.}；{@code --thinking} 显式时后缀留在 id 里。</li>
 * </ul>
 */
public final class CliModelResolution {

    /**
     * 一次 CLI 解析请求。
     *
     * @param cliProvider     {@code --provider}（null/空白 ≙ 未给）
     * @param cliModel        {@code --model}（null/空白 ⇒ 无解析）
     * @param cliThinking     {@code --thinking} 是否显式给出（决定后缀归属）
     * @param inlineApiKey    {@code --api-key} 是否给出（可用性恒 false）
     * @param models          全部模型（pi {@code getModels()}，不看凭据）
     * @param configuredAuth  provider 是否已有配置凭据（pi {@code hasConfiguredAuth}）
     * @param knownProvider   provider 是否真实存在（pi 的 models 列表天然含全部已注册
     *                        provider 的模型；Java 的静态目录不含运行时注册的 provider，
     *                        故显式 {@code --provider} 用它兜住「provider 真实存在但
     *                        模型未编目」这一 Java 独有形态）
     */
    public record Request(String cliProvider, String cliModel, boolean cliThinking,
                          boolean inlineApiKey, List<ModelInfo> models,
                          Predicate<String> configuredAuth, Predicate<String> knownProvider) {

        /** 无运行时 provider 表（目录即全部）的旧形。 */
        public Request(String cliProvider, String cliModel, boolean cliThinking,
                       boolean inlineApiKey, List<ModelInfo> models,
                       Predicate<String> configuredAuth) {
            this(cliProvider, cliModel, cliThinking, inlineApiKey, models, configuredAuth,
                provider -> false);
        }
    }

    /**
     * 解析结果（pi {@code ResolveCliModelResult}）。
     *
     * @param model         命中或自定义落点（错误时为 {@code null}）
     * @param thinkingLevel 解析出的思考级别（可空）
     * @param warning       自定义 id 等警告（可空）
     * @param error         错误文案（可空；非空 ⇒ {@code model == null}）
     * @param customId      自定义 id 臂产出的 id（{@code null} ≙ 目录内命中）
     */
    public record Result(ModelInfo model, String thinkingLevel, String warning, String error,
                         ModelId<?> customId) {

        static Result none() {
            return new Result(null, null, null, null, null);
        }

        static Result error(String message) {
            return new Result(null, null, null, message, null);
        }
    }

    private CliModelResolution() {
    }

    /** {@code provider/rest} ⇒ {@code rest}；无该前缀 ⇒ 原串。 */
    private static String stripProviderPrefix(String text, String provider) {
        var prefix = provider + "/";
        return text.startsWith(prefix) ? text.substring(prefix.length()) : text;
    }

    /** 最后一个冒号后是合法思考档 ⇒ 拆分（否则 {@code null}）。 */
    private static ThinkingSplit thinkingSuffixOf(String text) {
        var colon = text.lastIndexOf(':');
        if (colon == -1) {
            return null;
        }
        var suffix = text.substring(colon + 1);
        return ModelReferenceResolver.isValidThinkingLevel(suffix)
            ? new ThinkingSplit(text.substring(0, colon), suffix) : null;
    }

    private record ThinkingSplit(String base, String level) {}

    /** pi {@code resolveCliModel}（:450-530 逐字）。 */
    public static Result resolve(Request request) {
        if (request.cliModel() == null || request.cliModel().isBlank()) {
            return Result.none();
        }
        var models = request.models();
        if (models.isEmpty()) {
            return Result.error(
                "No models available. Check your installation or add models to models.json.");
        }
        final String provider;
        if (request.cliProvider() != null && !request.cliProvider().isBlank()) {
            var wanted = request.cliProvider().toLowerCase(Locale.ROOT);
            var inCatalog = models.stream()
                .map(model -> model.id().provider())
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst().orElse(null);
            // Java 方言：运行时注册的 provider 可能不在静态目录里（pi 的 models 列表
            // 天然含它们）⇒ 已知 provider 表也参与「provider 是否存在」判定。
            provider = inCatalog != null ? inCatalog
                : request.knownProvider().test(request.cliProvider())
                    ? request.cliProvider() : null;
            if (provider == null) {
                return Result.error("Unknown provider \"" + request.cliProvider()
                    + "\". Use --list-models to see available providers/models.");
            }
        } else {
            provider = null;
        }
        // 模型串自带 {@code provider/id} 形状且前缀 provider 既不在目录也不是已知
        // provider ⇒ 该前缀就是目标 provider（虚拟模型／用户自建 provider，D227）。
        // 三条件缺一不可：<b>精确匹配不中</b>（{@code anthropic/claude-fable-5:batch}
        // 是目录里某条的裸 id）、<b>前缀未知</b>（{@code openrouter/x} 仍按目录解析）、
        // 且该前缀不是已知 provider。pi 的 {@code --provider} 只限定目录条目，不会
        // 把 {@code jev/auto} 变成 {@code --provider} 所指 provider 下的模型。
        final String customProviderRef;
        var customSlash = request.cliModel().indexOf('/');
        if (customSlash > 0 && customSlash < request.cliModel().length() - 1
                && ModelReferenceResolver.resolve(
                    ModelReferenceResolver.Request.exact(request.cliModel()), models,
                    candidate -> true) instanceof ModelReferenceResolver.Result.NotFound) {
            var prefix = request.cliModel().substring(0, customSlash);
            var inCatalog = models.stream()
                .anyMatch(model -> model.id().provider().equalsIgnoreCase(prefix));
            customProviderRef = inCatalog || request.knownProvider().test(prefix)
                ? null : prefix;
        } else {
            customProviderRef = null;
        }
        if (customProviderRef != null) {
            var customModel = request.cliModel().substring(customSlash + 1);
            return new Result(null, null,
                "Model \"" + customModel + "\" not found for provider \"" + customProviderRef
                    + "\". Using custom model id.",
                null, ModelId.of(customProviderRef, customModel));
        }

        var result = ModelReferenceResolver.resolve(
            ModelReferenceResolver.Request.strict(request.cliModel(), provider), models,
            // inline key 会发给被选中的 provider ⇒ 已存凭据不得替它选。
            request.inlineApiKey() ? candidate -> false : request.configuredAuth());

        // Java 方言（pi 的 models 列表天然含已注册 provider 的模型）：显式 --provider
        // 且该 provider 真实存在、只是模型未编目 ⇒ 按自定义 id 处理。pi 在此处的
        // 等价形态是 resolveModelReference 的 not-found.target 非空。
        if (provider != null && result instanceof ModelReferenceResolver.Result.NotFound
                && models.stream().noneMatch(model -> model.id().provider().equals(provider))
                && request.knownProvider().test(provider)) {
            var modelText = stripProviderPrefix(request.cliModel(), provider);
            var suffix = thinkingSuffixOf(modelText);
            var modelId = request.cliThinking() ? modelText
                : suffix == null ? modelText : suffix.base();
            return new Result(null, request.cliThinking() ? null
                : suffix == null ? null : suffix.level(),
                "Model \"" + modelId + "\" not found for provider \"" + provider
                    + "\". Using custom model id.",
                null, ModelId.of(provider, modelId));
        }

        if (result instanceof ModelReferenceResolver.Result.Resolved resolved) {
            return new Result(resolved.model(), resolved.thinkingLevel(), resolved.warning(),
                null, null);
        }
        if (result instanceof ModelReferenceResolver.Result.Ambiguous ambiguous) {
            var matches = ambiguous.candidates().stream()
                .map(model -> model.id().provider() + "/" + model.id().modelName())
                .sorted().reduce((a, b) -> a + ", " + b).orElse("");
            var authHint = request.inlineApiKey()
                ? "--api-key does not choose a provider."
                : ambiguous.usableCount() == 0
                    ? "No matching provider is authenticated."
                    : "More than one matching provider is authenticated.";
            return Result.error("Model \"" + request.cliModel()
                + "\" is ambiguous across providers: " + matches + ". " + authHint
                + " Use --provider or provider/model.");
        }
        var notFound = (ModelReferenceResolver.Result.NotFound) result;
        if (notFound.target().isEmpty()) {
            return Result.error("Model \"" + request.cliModel()
                + "\" not found. Use --list-models to see available models.");
        }
        var target = notFound.target().get();
        // --thinking 显式时后缀留在 id 里（pi :512-518）。
        var modelId = request.cliThinking() ? target.modelText() : target.modelId();
        var fallbackThinking = request.cliThinking() ? null : target.thinkingLevel();
        return new Result(target.providerModels().getFirst(), fallbackThinking,
            "Model \"" + modelId + "\" not found for provider \"" + target.provider()
                + "\". Using custom model id.",
            null, ModelId.of(target.provider(), modelId));
    }
}
