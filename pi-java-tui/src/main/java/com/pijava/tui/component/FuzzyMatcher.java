package com.pijava.tui.component;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Fuzzy matching for selectors, aligned with pi's {@code fuzzy.ts}
 * (Phase 3 design §8.2).
 */
public final class FuzzyMatcher {

    private FuzzyMatcher() {}

    /**
     * Rank candidates by substring match score (descending).
     *
     * @param query      search text (blank returns candidates in order)
     * @param candidates candidate labels
     * @return matched candidates sorted by score
     */
    public static List<String> rank(String query, List<String> candidates) {
        if (query == null || query.isBlank()) {
            return List.copyOf(candidates);
        }
        var lower = query.toLowerCase();
        return candidates.stream()
            .map(candidate -> new Scored(candidate, score(lower, candidate.toLowerCase())))
            .filter(s -> s.score >= 0)
            .sorted(Comparator.comparingInt((Scored s) -> s.score))
            .map(s -> s.candidate)
            .toList();
    }

    /** Generic variant over typed items. */
    public static <T> List<T> rank(String query, List<T> items, Function<T, String> label) {
        if (query == null || query.isBlank()) {
            return List.copyOf(items);
        }
        var lower = query.toLowerCase();
        return items.stream()
            .map(item -> new ScoredItem<>(item, score(lower, label.apply(item).toLowerCase())))
            .filter(s -> s.score >= 0)
            .sorted(Comparator.comparingInt((ScoredItem<T> s) -> s.score))
            .map(s -> s.item)
            .toList();
    }

    /** -1 when no match, otherwise lower = better (0 = exact prefix match). */
    private static int score(String query, String candidate) {
        if (candidate.startsWith(query)) {
            return 0;
        }
        var index = candidate.indexOf(query);
        if (index < 0) {
            return -1;
        }
        return index;
    }

    private record Scored(String candidate, int score) {}

    private record ScoredItem<T>(T item, int score) {}
}
