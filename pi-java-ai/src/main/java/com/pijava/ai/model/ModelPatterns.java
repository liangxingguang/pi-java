package com.pijava.ai.model;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.catalog.ModelInfo;

/**
 * pi {@code packages/coding-agent/src/core/model-resolver.ts} 的无状态匹配核。
 *
 * <p>包可见：{@link DefaultModelResolver}（CLI 严格模式）、{@link ModelScopeResolver}
 * （scope 宽容模式）与本包测试共用，不对外暴露。</p>
 */
final class ModelPatterns {

    /** pi VALID_THINKING_LEVELS（cli/args.ts:61-65），逐字、<b>大小写敏感</b>。 */
    static final Set<String> THINKING_LEVELS =
        Set.of("off", "minimal", "low", "medium", "high", "xhigh", "max");

    private ModelPatterns() {}

    static boolean isValidThinkingLevel(String s) {
        return s != null && THINKING_LEVELS.contains(s);
    }

    /**
     * pi {@code findExactModelReferenceMatch}（model-resolver.ts:88-122）。
     * 三段短路：规范 {@code provider/id} → provider 限定 → 裸 id；忽略大小写；
     * 每段<b>唯一</b>才命中，多于一条（如裸 id 跨 provider 重复）⇒ empty。
     */
    static Optional<ModelInfo> findExactReference(String reference, List<ModelInfo> models) {
        var trimmed = reference.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        var normalized = trimmed.toLowerCase(Locale.ROOT);

        var canonical = models.stream()
            .filter(m -> (m.id().provider() + "/" + m.id().modelName())
                .toLowerCase(Locale.ROOT).equals(normalized))
            .toList();
        if (canonical.size() == 1) {
            return Optional.of(canonical.getFirst());
        }
        if (canonical.size() > 1) {
            return Optional.empty();
        }

        var slash = trimmed.indexOf('/');
        if (slash != -1) {
            var provider = trimmed.substring(0, slash).trim();
            var modelId = trimmed.substring(slash + 1).trim();
            if (!provider.isEmpty() && !modelId.isEmpty()) {
                var qualified = models.stream()
                    .filter(m -> m.id().provider().equalsIgnoreCase(provider)
                        && m.id().modelName().equalsIgnoreCase(modelId))
                    .toList();
                if (qualified.size() == 1) {
                    return Optional.of(qualified.getFirst());
                }
                if (qualified.size() > 1) {
                    return Optional.empty();
                }
            }
        }

        var bare = models.stream()
            .filter(m -> m.id().modelName().toLowerCase(Locale.ROOT).equals(normalized))
            .toList();
        return bare.size() == 1 ? Optional.of(bare.getFirst()) : Optional.empty();
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
     * pi {@code tryMatchModel}（model-resolver.ts:130-159）：精确不中 ⇒ id 或
     * <b>显示名</b>（{@link ModelInfo#displayName()}）子串（忽略大小写）；alias 组
     * 整体优先于 dated 组，组内按裸 id <b>降序</b>，同 id 保目录序。
     *
     * <p>排序裁决（docs/12 §4②）：JS {@code localeCompare} 随运行环境 locale 变化，
     * 用码点序降序（{@link String#compareTo}）＋稳定排序兜底；dated 场景码点序即
     * 时间序，与 pi 等价。</p>
     */
    static Optional<ModelInfo> tryMatch(String pattern, List<ModelInfo> models) {
        var exact = findExactReference(pattern, models);
        if (exact.isPresent()) {
            return exact;
        }
        var needle = pattern.toLowerCase(Locale.ROOT);
        var matches = models.stream()
            .filter(m -> m.id().modelName().toLowerCase(Locale.ROOT).contains(needle)
                || (m.displayName() != null
                    && m.displayName().toLowerCase(Locale.ROOT).contains(needle)))
            .toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        var aliases = matches.stream().filter(m -> isAlias(m.id().modelName())).toList();
        var chosen = !aliases.isEmpty() ? aliases
            : matches.stream().filter(m -> !isAlias(m.id().modelName())).toList();
        return chosen.stream()
            .sorted(Comparator.comparing((ModelInfo m) -> m.id().modelName()).reversed())
            .findFirst();
    }

    /** parse 结果（pi {@code ParsedModelResult}，model-resolver.ts:161-166）。 */
    record ParsedPattern(Optional<ModelInfo> model,
                         Optional<String> level,
                         Optional<String> warning) {
        static ParsedPattern none() {
            return new ParsedPattern(Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /**
     * pi {@code parseModelPattern}（model-resolver.ts:204-254）：全串先试（保护
     * openrouter {@code :batch}/{@code :exacto} 冒号 id），不中才切<b>最后一个</b>
     * 冒号；后缀须是 7 档合法级别，否则：
     * <ul>
     *   <li>scope 宽容模式（{@code strict=false}）：警告＋前缀递归（级别回退默认）；</li>
     *   <li>CLI 严格模式（{@code strict=true}）：非法后缀整串留作 id 的一部分，empty。</li>
     * </ul>
     * 内层带警告时外层级别丢弃（pi :236-238）。
     */
    static ParsedPattern parse(String pattern, List<ModelInfo> models, boolean strict) {
        var exact = tryMatch(pattern, models);
        if (exact.isPresent()) {
            return new ParsedPattern(exact, Optional.empty(), Optional.empty());
        }
        var colon = pattern.lastIndexOf(':');
        if (colon == -1) {
            return ParsedPattern.none();
        }
        var prefix = pattern.substring(0, colon);
        var suffix = pattern.substring(colon + 1);

        if (isValidThinkingLevel(suffix)) {
            var inner = parse(prefix, models, strict);
            if (inner.model().isPresent()) {
                return new ParsedPattern(
                    inner.model(),
                    inner.warning().isPresent() ? Optional.empty() : Optional.of(suffix),
                    inner.warning());
            }
            return inner;
        }
        if (strict) {
            return ParsedPattern.none();
        }
        var inner = parse(prefix, models, false);
        if (inner.model().isPresent()) {
            return new ParsedPattern(inner.model(), Optional.empty(), Optional.of(
                "Invalid thinking level \"" + suffix + "\" in pattern \"" + pattern
                    + "\". Using default instead."));
        }
        return inner;
    }
}
