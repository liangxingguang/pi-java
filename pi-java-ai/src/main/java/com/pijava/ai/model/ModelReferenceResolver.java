package com.pijava.ai.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import com.pijava.ai.catalog.ModelInfo;

/**
 * L5 W6a（docs/25 §7；pi {@code core/model-resolver.ts:83-260} 逐字移植）：模型引用
 * 解析的<b>单一策略</b>——先收集候选再选 provider，故目录顺序永不决定结果，歧义
 * 显式报出而不是回落到更弱的匹配。
 *
 * <ul>
 *   <li>{@code provider/rest} 只是<b>提示</b>：全串也可能是别家的模型 id（网关 id
 *       {@code zai/glm-5}、{@code xiaomi/mimo-v2.6-pro}）；</li>
 *   <li>命名 provider 可凭据使用 ⇒ 它赢；否则唯一可用的其它 provider 赢；都不可用
 *       但有命名候选 ⇒ 命名候选（供 {@code --api-key} 首配）；</li>
 *   <li>多 provider 且无法裁决 ⇒ {@link Result.Ambiguous}（携带候选与可用数）；</li>
 *   <li>{@code :thinking} 后缀三态：{@code none} 不切、{@code strict} 只切合法级别、
 *       {@code lenient} 非法后缀丢弃并警告。</li>
 * </ul>
 *
 * <p>本类不依赖 coding-agent：可用性以 {@link Predicate} 注入（pi 的 {@code usable}）。</p>
 */
public final class ModelReferenceResolver {

    /** pi {@code thinkingSuffix} 三态（model-resolver.ts:96-104）。 */
    public enum ThinkingSuffix {
        /** 尾缀就是 id 的一部分。 */
        NONE,
        /** 只解析合法级别；其它尾缀留在 id 里。 */
        STRICT,
        /** 同 strict，但非法尾缀丢弃并产警告。 */
        LENIENT
    }

    /**
     * 一次解析请求。
     *
     * @param text          裸 id 或 {@code provider/id}，可带 {@code :<thinking>}
     * @param provider      只考虑该 provider（{@code null} ＝不限）
     * @param thinkingSuffix 尾缀策略
     * @param fuzzy         精确不中时是否按 id/显示名子串匹配
     */
    public record Request(String text, String provider, ThinkingSuffix thinkingSuffix,
                          boolean fuzzy) {

        /** scope 宽容形（{@code lenient}＋{@code fuzzy}）。 */
        public static Request lenient(String text) {
            return new Request(text, null, ThinkingSuffix.LENIENT, true);
        }

        /** CLI 严格形（{@code strict}＋{@code fuzzy}）。 */
        public static Request strict(String text, String provider) {
            return new Request(text, provider, ThinkingSuffix.STRICT, true);
        }

        /** 精确引用形（{@code none}＋非 fuzzy——pi {@code findExactModelReferenceMatch}）。 */
        public static Request exact(String text) {
            return new Request(text, null, ThinkingSuffix.NONE, false);
        }
    }

    /** 解析结果（pi {@code ModelReferenceResult} 三态）。 */
    public sealed interface Result {

        /** 命中。 */
        record Resolved(ModelInfo model, String thinkingLevel, String warning) implements Result {}

        /** 跨 provider 歧义。 */
        record Ambiguous(List<ModelInfo> candidates, int usableCount, String thinkingLevel,
                         String warning) implements Result {}

        /** 未命中；{@code target} 非空 ⇒ 显式 provider 提示可用于建自定义 id。 */
        record NotFound(Optional<Target> target) implements Result {}

        /** 空实现便捷。 */
        static Result notFound() {
            return new NotFound(Optional.empty());
        }
    }

    /**
     * 自定义 id 的落点（pi {@code ModelReferenceTarget}）。
     *
     * @param provider       目标 provider
     * @param providerModels 该 provider 的模型（首条为锚，供默认模型）
     * @param modelText      前缀之后按用户输入的原文（含尾缀）
     * @param modelId        {@code modelText} 去掉合法思考后缀后的 id
     * @param thinkingLevel  解析出的级别（无 ⇒ {@code null}）
     */
    public record Target(String provider, List<ModelInfo> providerModels, String modelText,
                         String modelId, String thinkingLevel) {}

    private ModelReferenceResolver() {
    }

    /**
     * 解析（pi {@code resolveModelReference}，:149-233）。
     *
     * @param request 请求
     * @param models  候选模型（目录序无关）
     * @param usable  provider 可用性判据（pi {@code usable}）
     */
    public static Result resolve(Request request, List<ModelInfo> models,
                                 Predicate<String> usable) {
        var text = request.text() == null ? "" : request.text().trim();
        if (text.isEmpty()) {
            return Result.notFound();
        }
        var constraint = request.provider() == null ? null
            : request.provider().toLowerCase(Locale.ROOT);
        var pool = constraint == null ? models
            : models.stream()
                .filter(model -> model.id().provider().toLowerCase(Locale.ROOT).equals(constraint))
                .toList();
        var result = resolveText(text, request, pool, usable);
        if (!(result instanceof Result.NotFound)) {
            return result;
        }
        // provider 前缀只给出目标 provider，供调用方建自定义 id。
        var split = splitProviderPrefix(text, pool);
        var anchor = constraint != null ? pool.stream().findFirst().orElse(null)
            : split == null ? null : pool.stream()
                .filter(model -> model.id().provider().equals(split.provider()))
                .findFirst().orElse(null);
        if (anchor == null) {
            return result;
        }
        var modelText = split == null ? text : split.modelText();
        var suffix = request.thinkingSuffix() == ThinkingSuffix.NONE
            ? null : splitThinkingSuffix(modelText);
        var providerModels = new ArrayList<ModelInfo>();
        providerModels.add(anchor);
        for (var model : pool) {
            if (model.id().provider().equals(anchor.id().provider()) && model != anchor) {
                providerModels.add(model);
            }
        }
        return new Result.NotFound(Optional.of(new Target(anchor.id().provider(),
            List.copyOf(providerModels), modelText,
            suffix == null ? modelText : suffix.base(),
            suffix == null ? null : suffix.level())));
    }

    /** pi {@code findExactModelReferenceMatch}（:181-190）：{@code none}+非 fuzzy 的薄包装。 */
    public static Optional<ModelInfo> findExactMatch(String reference, List<ModelInfo> models) {
        var result = resolve(Request.exact(reference == null ? "" : reference), models,
            provider -> true);
        return result instanceof Result.Resolved resolved
            ? Optional.of(resolved.model()) : Optional.empty();
    }

    // ── 内核 ─────────────────────────────────────────────────────

    private static Result resolveText(String text, Request request, List<ModelInfo> pool,
                                      Predicate<String> usable) {
        var reference = selectReference(text, pool, request.fuzzy(), usable);
        if (reference != null) {
            return reference;
        }
        if (request.fuzzy()) {
            var fuzzy = selectFuzzy(fuzzyMatches(text, pool), usable);
            if (fuzzy != null) {
                return fuzzy;
            }
        }
        if (request.thinkingSuffix() == ThinkingSuffix.NONE) {
            return Result.notFound();
        }
        var thinking = splitThinkingSuffix(text);
        if (thinking != null) {
            var inner = resolveText(thinking.base(), request, pool, usable);
            if (inner instanceof Result.NotFound) {
                return inner;
            }
            // 内层带警告时不采用外层级别（pi :236-238）。
            return withLevel(inner, warningOf(inner) != null ? null : thinking.level());
        }
        if (request.thinkingSuffix() == ThinkingSuffix.STRICT) {
            return Result.notFound();
        }
        var colon = text.lastIndexOf(':');
        if (colon == -1) {
            return Result.notFound();
        }
        var suffix = text.substring(colon + 1);
        var inner = resolveText(text.substring(0, colon), request, pool, usable);
        if (inner instanceof Result.NotFound) {
            return inner;
        }
        return withWarning(withLevel(inner, null),
            "Invalid thinking level \"" + suffix + "\" in pattern \"" + text
                + "\". Using default instead.");
    }

    /**
     * pi {@code selectReference}（:236-283）：{@code provider/rest} 只是提示——命名
     * provider 可用时它赢；否则唯一可用的裸匹配赢；都不可用但有命名候选 ⇒ 命名候选。
     */
    private static Result selectReference(String text, List<ModelInfo> pool, boolean fuzzy,
                                          Predicate<String> usable) {
        var lowerText = text.toLowerCase(Locale.ROOT);
        var raw = pool.stream()
            .filter(model -> model.id().modelName().toLowerCase(Locale.ROOT).equals(lowerText))
            .toList();
        var split = splitProviderPrefix(text, pool);
        ModelInfo named = null;
        if (split != null) {
            var providerModels = pool.stream()
                .filter(model -> model.id().provider().equals(split.provider())).toList();
            var lowerModelText = split.modelText().toLowerCase(Locale.ROOT);
            // provider 自己的字面 id（OpenRouter 的 openrouter/free）压过它的模糊匹配。
            named = providerModels.stream()
                .filter(model -> model.id().modelName().toLowerCase(Locale.ROOT).equals(lowerModelText))
                .findFirst()
                .or(() -> raw.stream()
                    .filter(model -> model.id().provider().equals(split.provider())).findFirst())
                .orElseGet(() -> fuzzy
                    ? bestFuzzyMatch(fuzzyMatches(split.modelText(), providerModels)) : null);
        }
        var namedModel = named;
        var others = raw.stream().filter(model -> !sameModel(model, namedModel)).toList();
        var candidates = new ArrayList<ModelInfo>();
        if (namedModel != null) {
            candidates.add(namedModel);
        }
        candidates.addAll(others);
        if (candidates.isEmpty()) {
            return null;
        }
        if (distinctProviders(candidates) == 1) {
            return new Result.Resolved(candidates.getFirst(), null, null);
        }
        if (namedModel != null && usable.test(namedModel.id().provider())) {
            return new Result.Resolved(namedModel, null, null);
        }
        var usableOthers = others.stream()
            .filter(model -> usable.test(model.id().provider())).toList();
        if (usableOthers.size() == 1) {
            return new Result.Resolved(usableOthers.getFirst(), null, null);
        }
        if (usableOthers.isEmpty() && namedModel != null) {
            return new Result.Resolved(namedModel, null, null);
        }
        return new Result.Ambiguous(List.copyOf(candidates), usableOthers.size(), null, null);
    }

    /** pi {@code selectFuzzy}（:291-315）：多 provider 时优先可用者，同 id 并列仍歧义。 */
    private static Result selectFuzzy(List<ModelInfo> matches, Predicate<String> usable) {
        if (matches.isEmpty()) {
            return null;
        }
        var multipleProviders = distinctProviders(matches) > 1;
        var usableMatches = multipleProviders
            ? matches.stream().filter(model -> usable.test(model.id().provider())).toList()
            : List.<ModelInfo>of();
        var pool = usableMatches.isEmpty() ? matches : usableMatches;
        var best = bestFuzzyMatch(pool);
        if (best == null) {
            return null;
        }
        // 同一 id 在多家 provider 上 ⇒ 否则由目录序决定。
        var tied = pool.stream().filter(model -> model.id().modelName().equals(best.id().modelName()))
            .toList();
        if (distinctProviders(tied) == 1) {
            return new Result.Resolved(best, null, null);
        }
        return new Result.Ambiguous(List.copyOf(tied),
            usableMatches.isEmpty() ? 0 : tied.size(), null, null);
    }

    /** pi {@code fuzzyMatches}（:285-290）：id 或显示名子串（忽略大小写）。 */
    private static List<ModelInfo> fuzzyMatches(String text, List<ModelInfo> models) {
        var needle = text.toLowerCase(Locale.ROOT);
        return models.stream().filter(model ->
            model.id().modelName().toLowerCase(Locale.ROOT).contains(needle)
                || (model.displayName() != null
                    && model.displayName().toLowerCase(Locale.ROOT).contains(needle))).toList();
    }

    /** pi {@code bestFuzzyMatch}/{@code isAlias}（:317-333）：alias 优先，再按 id 降序。 */
    private static ModelInfo bestFuzzyMatch(List<ModelInfo> matches) {
        if (matches.isEmpty()) {
            return null;
        }
        var aliases = matches.stream().filter(model -> ModelPatterns.isAlias(model.id().modelName()))
            .toList();
        var chosen = aliases.isEmpty() ? matches : aliases;
        return chosen.stream()
            .sorted((a, b) -> b.id().modelName().compareTo(a.id().modelName()))
            .findFirst().orElse(null);
    }

    private record ThinkingSplit(String base, String level) {}

    /** pi {@code splitThinkingSuffix}（:118-125）：最后一个冒号后是合法级别才切。 */
    private static ThinkingSplit splitThinkingSuffix(String text) {
        var colon = text.lastIndexOf(':');
        if (colon == -1) {
            return null;
        }
        var suffix = text.substring(colon + 1);
        return ModelPatterns.isValidThinkingLevel(suffix)
            ? new ThinkingSplit(text.substring(0, colon), suffix) : null;
    }

    private record ProviderPrefix(String provider, String modelText) {}

    /**
     * pi {@code splitProviderPrefix}（:127-146）：provider id 自身可能含 {@code /}
     * （URL 派生的 id），故按已知 provider 名字长优先匹配。
     */
    private static ProviderPrefix splitProviderPrefix(String text, List<ModelInfo> models) {
        var lowerText = text.toLowerCase(Locale.ROOT);
        ProviderPrefix best = null;
        for (var model : models) {
            var provider = model.id().provider();
            var prefix = provider.trim().toLowerCase(Locale.ROOT);
            if (prefix.isEmpty() || (best != null && best.provider().length() >= provider.length())) {
                continue;
            }
            var slashIndex = lowerText.indexOf('/', prefix.length());
            if (slashIndex == -1
                    || !lowerText.substring(0, slashIndex).trim().equals(prefix)) {
                continue;
            }
            var modelText = text.substring(slashIndex + 1).trim();
            if (!modelText.isEmpty()) {
                best = new ProviderPrefix(provider, modelText);
            }
        }
        return best;
    }

    /** 结果改级别（{@code null} ⇒ 清空）。 */
    private static Result withLevel(Result result, String level) {
        return switch (result) {
            case Result.Resolved resolved ->
                new Result.Resolved(resolved.model(), level, resolved.warning());
            case Result.Ambiguous ambiguous ->
                new Result.Ambiguous(ambiguous.candidates(), ambiguous.usableCount(), level,
                    ambiguous.warning());
            case Result.NotFound notFound -> notFound;
        };
    }

    /** 结果加警告（保留原 {@code inner} 的其它字段）。 */
    private static Result withWarning(Result result, String warning) {
        return switch (result) {
            case Result.Resolved resolved ->
                new Result.Resolved(resolved.model(), resolved.thinkingLevel(), warning);
            case Result.Ambiguous ambiguous ->
                new Result.Ambiguous(ambiguous.candidates(), ambiguous.usableCount(),
                    ambiguous.thinkingLevel(), warning);
            case Result.NotFound notFound -> notFound;
        };
    }

    private static String warningOf(Result result) {
        return switch (result) {
            case Result.Resolved resolved -> resolved.warning();
            case Result.Ambiguous ambiguous -> ambiguous.warning();
            case Result.NotFound notFound -> null;
        };
    }

    private static int distinctProviders(List<ModelInfo> models) {
        Set<String> providers = new LinkedHashSet<>();
        for (var model : models) {
            providers.add(model.id().provider());
        }
        return providers.size();
    }

    private static boolean sameModel(ModelInfo left, ModelInfo right) {
        return right != null && left.id().equals(right.id());
    }
}
