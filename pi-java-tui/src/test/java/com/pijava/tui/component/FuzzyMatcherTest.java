package com.pijava.tui.component;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FuzzyMatcher ranking: lower score = better, exact prefix matches first.
 * Regression guard for the P6-24 sort-direction fix (was reversed).
 */
class FuzzyMatcherTest {

    @Test
    void prefixMatchRanksBeforeSubstringMatches() {
        var ranked = FuzzyMatcher.rank("a", List.of("beta", "gamma", "alpha"));
        // alpha is an exact prefix match (0), gamma contains "a" at 1,
        // beta at 3 — ascending score order puts alpha first.
        assertThat(ranked).isEqualTo(List.of("alpha", "gamma", "beta"));
    }

    @Test
    void blankQueryKeepsOriginalOrder() {
        assertThat(FuzzyMatcher.rank("", List.of("z", "a", "m")))
            .isEqualTo(List.of("z", "a", "m"));
    }

    @Test
    void nonMatchingCandidatesAreDropped() {
        assertThat(FuzzyMatcher.rank("xy", List.of("alpha", "xylophone")))
            .isEqualTo(List.of("xylophone"));
    }
}
