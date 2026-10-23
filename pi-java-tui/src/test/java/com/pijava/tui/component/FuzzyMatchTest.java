package com.pijava.tui.component;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * FuzzyMatch (pi fuzzy.ts verbatim port): subsequence scoring, word-boundary and
 * consecutive bonuses, gap/position penalties, full-equality bonus, swap arm.
 * Expected scores are hand-computed from pi's scoring rules (docs/25 §1.4).
 */
class FuzzyMatchTest {

    @Test
    void emptyQueryMatchesEverythingWithZeroScore() {
        assertThat(FuzzyMatch.fuzzyMatch("", "abc")).isEqualTo(new FuzzyMatch.Result(true, 0));
    }

    @Test
    void queryLongerThanTextNeverMatches() {
        assertThat(FuzzyMatch.fuzzyMatch("abcd", "abc").matches()).isFalse();
        assertThat(FuzzyMatch.fuzzyMatch("abcd", "abc").score()).isEqualTo(0);
    }

    @Test
    void nonSubsequenceDoesNotMatch() {
        assertThat(FuzzyMatch.fuzzyMatch("z", "abc").matches()).isFalse();
        assertThat(FuzzyMatch.fuzzyMatch("acb", "abc").matches()).isFalse();
    }

    @Test
    void exactMatchEarnsFullEqualityBonus() {
        // a@0: -5 (consec) -10 (boundary) = -15; b@1: -10 +0.1; c@2: -15 +0.2;
        // exact: -100 ⇒ -139.7
        var r = FuzzyMatch.fuzzyMatch("abc", "abc");
        assertThat(r.matches()).isTrue();
        assertThat(r.score()).isCloseTo(-139.7, within(1e-9));
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertThat(FuzzyMatch.fuzzyMatch("ABC", "abc").score())
            .isCloseTo(FuzzyMatch.fuzzyMatch("abc", "abc").score(), within(1e-9));
    }

    @Test
    void subsequenceMatchAcrossGapsPaysGapPenalty() {
        // a@0: -15; b@2: gap +2, +0.2; c@4: gap +2, +0.4 ⇒ -10.4
        var r = FuzzyMatch.fuzzyMatch("abc", "aXbXc");
        assertThat(r.matches()).isTrue();
        assertThat(r.score()).isCloseTo(-10.4, within(1e-9));
    }

    @Test
    void wordBoundaryBonusApplies() {
        // "b" after a space: -10 boundary + 0.2 position ⇒ -9.8
        var boundary = FuzzyMatch.fuzzyMatch("b", "a b");
        assertThat(boundary.score()).isCloseTo(-9.8, within(1e-9));
        // "b" after a letter: no boundary bonus, +0.1 position ⇒ 0.1
        var noBoundary = FuzzyMatch.fuzzyMatch("b", "ab");
        assertThat(noBoundary.score()).isCloseTo(0.1, within(1e-9));
        assertThat(boundary.score()).isLessThan(noBoundary.score());
    }

    @Test
    void consecutiveBonusGrowsWithRunLength() {
        // a@1: +0.1; b@2: consec×1 -5 +0.2; c@3: consec×2 -10 +0.3 ⇒ -14.4
        var run = FuzzyMatch.fuzzyMatch("abc", "xabc");
        assertThat(run.score()).isCloseTo(-14.4, within(1e-9));
        // split run scores worse than the consecutive run
        var split = FuzzyMatch.fuzzyMatch("ab", "xaXb");
        assertThat(split.score()).isCloseTo(2.4, within(1e-9));
        assertThat(FuzzyMatch.fuzzyMatch("ab", "xab").score()).isLessThan(split.score());
    }

    @Test
    void lettersDigitsSwapArmMatchesWithPlusFivePenalty() {
        // primary "abc123" vs "123abc" fails; swapped "123abc" is exact:
        // 6-char exact = -213.5, +5 swap penalty ⇒ -208.5
        var r = FuzzyMatch.fuzzyMatch("abc123", "123abc");
        assertThat(r.matches()).isTrue();
        assertThat(r.score()).isCloseTo(-208.5, within(1e-9));
    }

    @Test
    void digitsLettersSwapArmMatches() {
        var r = FuzzyMatch.fuzzyMatch("123abc", "abc123");
        assertThat(r.matches()).isTrue();
        assertThat(r.score()).isCloseTo(-208.5, within(1e-9));
    }

    @Test
    void swapArmDoesNotFireWhenPrimaryMatches() {
        // exact 6-char match: -213.5 (no +5 penalty — swap only fires on primary failure)
        assertThat(FuzzyMatch.fuzzyMatch("abc123", "abc123").score())
            .isCloseTo(-213.5, within(1e-9));
    }
}
