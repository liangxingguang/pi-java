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

    /** pi fuzzy.ts:245-262 inner {@code matchQuery} verbatim. */
    private static Result matchQuery(String normalizedQuery, String textLower) {
        if (normalizedQuery.isEmpty()) {
            return new Result(true, 0);
        }
        if (normalizedQuery.length() > textLower.length()) {
            return NO_MATCH;
        }
        int queryIndex = 0;
        double score = 0;
        int lastMatchIndex = -1;
        int consecutiveMatches = 0;
        while (queryIndex < normalizedQuery.length()) {
            int i = textLower.indexOf(normalizedQuery.charAt(queryIndex), lastMatchIndex + 1);
            if (i == -1) {
                break;
            }
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
            queryIndex++;
        }
        if (queryIndex < normalizedQuery.length()) {
            return NO_MATCH;
        }
        if (normalizedQuery.equals(textLower)) {
            score -= 100;
        }
        return new Result(true, score);
    }

    /** pi fuzzy.ts word-boundary class {@code [\s\-_./:]}. */
    private static boolean isBoundaryChar(char c) {
        return Character.isWhitespace(c) || c == '-' || c == '_' || c == '.' || c == '/' || c == ':';
    }
}
