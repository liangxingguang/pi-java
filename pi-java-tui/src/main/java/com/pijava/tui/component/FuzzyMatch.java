package com.pijava.tui.component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Subsequence fuzzy matcher — verbatim port of pi {@code pi-tui/src/fuzzy.ts}
 * {@code fuzzyMatch} (anchor ea448f454), written for the session selector
 * (coding-agent batch 14, docs/25 T1).
 *
 * <p>Distinct from {@link FuzzyMatcher}: that is the plain substring ranker used by
 * {@link SelectList} (B78 tracks realigning it with pi); this class is pi's scoring
 * engine itself and serves {@code SessionSelectorSearch}. Lower score = better match
 * (pi convention: bonuses are negative, penalties positive).
 */
public final class FuzzyMatch {

    /** Match outcome; score is only meaningful when {@code matches} is true. */
    public record Result(boolean matches, double score) {}

    private static final Result NO_MATCH = new Result(false, 0);

    // pi fuzzy.ts swap-arm regexes (verbatim shape).
    private static final Pattern ALPHA_NUMERIC = Pattern.compile("^(?<letters>[a-z]+)(?<digits>[0-9]+)$");
    private static final Pattern NUMERIC_ALPHA = Pattern.compile("^(?<digits>[0-9]+)(?<letters>[a-z]+)$");

    private FuzzyMatch() {}

    /** pi fuzzy.ts:242-273 verbatim (subsequence scoring + letter/digit swap arm). */
    public static Result fuzzyMatch(String query, String text) {
        var queryLower = query.toLowerCase(Locale.ROOT);
        var textLower = text.toLowerCase(Locale.ROOT);
        var primaryMatch = matchQuery(queryLower, textLower);
        if (primaryMatch.matches()) {
            return primaryMatch;
        }
        var alphaNumericMatch = ALPHA_NUMERIC.matcher(queryLower);
        var numericAlphaMatch = NUMERIC_ALPHA.matcher(queryLower);
        String swappedQuery;
        if (alphaNumericMatch.matches()) {
            swappedQuery = alphaNumericMatch.group("digits") + alphaNumericMatch.group("letters");
        } else if (numericAlphaMatch.matches()) {
            swappedQuery = numericAlphaMatch.group("letters") + numericAlphaMatch.group("digits");
        } else {
            swappedQuery = "";
        }
        if (swappedQuery.isEmpty()) {
            return primaryMatch;
        }
        var swappedMatch = matchQuery(swappedQuery, textLower);
        if (!swappedMatch.matches()) {
            return primaryMatch;
        }
        return new Result(true, swappedMatch.score() + 5);
    }

    /**
     * pi fuzzy.ts inner {@code matchQuery}：贪心对齐取位后，再枚举 query 的**每一次整段
     * 出现**重打分取最小（{@code :70-79}）——docs/63（L7 W3，C17）。
     *
     * <p>注意这不是 DP：离散匹配时贪心位是唯一候选，只有"query 整段出现在文本里"才多出
     * 候选起点。写 DP 会算出 pi 没有的分数。</p>
     */
    private static Result matchQuery(String normalizedQuery, String textLower) {
        if (normalizedQuery.isEmpty()) {
            return new Result(true, 0);
        }
        if (normalizedQuery.length() > textLower.length()) {
            return NO_MATCH;
        }
        var greedy = new int[normalizedQuery.length()];
        int lastMatchIndex = -1;
        for (int queryIndex = 0; queryIndex < normalizedQuery.length(); queryIndex++) {
            int i = textLower.indexOf(normalizedQuery.charAt(queryIndex), lastMatchIndex + 1);
            if (i == -1) {
                return NO_MATCH;
            }
            greedy[queryIndex] = i;
            lastMatchIndex = i;
        }
        double best = scorePositions(textLower, greedy);
        for (int start = textLower.indexOf(normalizedQuery);
             start >= 0;
             start = textLower.indexOf(normalizedQuery, start + 1)) {
            var contiguous = new int[normalizedQuery.length()];
            for (int k = 0; k < contiguous.length; k++) {
                contiguous[k] = start + k;
            }
            best = Math.min(best, scorePositions(textLower, contiguous));
        }
        if (normalizedQuery.equals(textLower)) {
            best -= 100;
        }
        return new Result(true, best);
    }

    /** pi fuzzy.ts:12-42 {@code scorePositions}：按给定命中位打分（越低越好）。 */
    private static double scorePositions(String textLower, int[] positions) {
        double score = 0;
        int lastMatchIndex = -1;
        int consecutiveMatches = 0;
        for (int i : positions) {
            boolean isWordBoundary = i == 0 || isBoundaryChar(textLower.charAt(i - 1));
            if (lastMatchIndex == i - 1) {
                consecutiveMatches++;
                score -= consecutiveMatches * 5;
            } else {
                consecutiveMatches = 0;
                if (lastMatchIndex >= 0) {
                    score += (i - lastMatchIndex - 1) * 2;
                }
            }
            if (isWordBoundary) {
                score -= 10;
            }
            score += i * 0.1;
            lastMatchIndex = i;
        }
        return score;
    }

    /** pi fuzzy.ts word-boundary class {@code [\s\-_./:]}. */
    private static boolean isBoundaryChar(char c) {
        return Character.isWhitespace(c) || c == '-' || c == '_' || c == '.' || c == '/' || c == ':';
    }
}
