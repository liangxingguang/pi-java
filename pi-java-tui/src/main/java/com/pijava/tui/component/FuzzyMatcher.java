package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Selector ranking — pi {@code pi-tui/src/fuzzy.ts} {@code fuzzyFilter}
 * ({@code :121-159}) 的文本级对位（docs/63，L7 W3／C17）。
 *
 * <p>与 pi 同构：query 按 {@code [\s/]+} 切 token（{@code "/"} 也切），<b>每个
 * token 都必须命中</b>，总分取各 token 的 {@link FuzzyMatch} 分数之和；排序只按总分，
 * tie-break 依赖 {@link List#sort} 的稳定性＝输入原序（pi 同）。</p>
 *
 * <p>本类此前是"子串位置"打分（前缀 0、否则 {@code indexOf} 下标），与
 * {@link FuzzyMatch} 两套引擎并存；docs/63 起收敛为一个引擎，调用点不变。</p>
 */
public final class FuzzyMatcher {

    private FuzzyMatcher() {}

    /**
     * Rank candidates by pi fuzzy score (lower = better, stable on ties).
     *
     * @param query      search text (blank returns candidates in order)
     * @param candidates candidate labels
     * @return matched candidates sorted by score
     */
    public static List<String> rank(String query, List<String> candidates) {
        return rank(query, candidates, candidate -> candidate);
    }

    /** Generic variant over typed items. */
    public static <T> List<T> rank(String query, List<T> items, Function<T, String> label) {
        if (query == null || query.isBlank()) {
            return List.copyOf(items);
        }
        var tokens = tokens(query);
        var scored = new ArrayList<ScoredItem<T>>(items.size());
        for (var item : items) {
            var text = label.apply(item);
            double total = 0;
            boolean matched = true;
            for (var token : tokens) {
                var result = FuzzyMatch.fuzzyMatch(token, text);
                if (!result.matches()) {
                    matched = false;
                    break;
                }
                total += result.score();
            }
            if (matched) {
                scored.add(new ScoredItem<>(item, total));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredItem::score));
        return scored.stream().map(ScoredItem::item).toList();
    }

    /** pi {@code fuzzyFilter:126-129}：按空白与 {@code "/"} 切 token。 */
    static List<String> tokens(String query) {
        var trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return List.of(trimmed.split("[\\s/]+"));
    }

    private record ScoredItem<T>(T item, double score) {}
}
