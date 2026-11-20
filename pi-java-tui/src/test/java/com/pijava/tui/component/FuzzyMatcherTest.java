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

    /** docs/63（pi fuzzy.ts:103-108）：整串完全相等 ⇐ 全等加分 −100。 */
    @Test
    void exactMatchRanksBeforeItsPrefix() {
        assertThat(FuzzyMatcher.rank("cl", List.of("clone", "cl")))
            .isEqualTo(List.of("cl", "clone"));
    }

    /** docs/63（pi fuzzyFilter:126-155）：token 之间 AND，任一不中即丢弃。 */
    @Test
    void everyTokenMustMatch() {
        var ranked = FuzzyMatcher.rank("foo bar", List.of("foo bar", "foo baz", "bar foo"));
        assertThat(ranked).containsExactlyInAnyOrder("foo bar", "bar foo");
    }

    /** docs/63：query 按 {@code [\s/]+} 切分，{@code "/"} 也是分隔符。 */
    @Test
    void slashAlsoSplitsTokens() {
        assertThat(FuzzyMatcher.rank("claude/sonnet", List.of("anthropic/claude-sonnet")))
            .containsExactly("anthropic/claude-sonnet");
    }
}
