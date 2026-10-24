package com.pijava.coding.agent.pkg;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 18 T3（docs/29 §3/§7）：npm semver 六件的 semver4j 适配面——valid/
 * compare/gt/rcompare/maxSatisfying（prerelease 排序按 semver §11）。
 */
class SemverTest {

    @Test
    void validAcceptsNpmLaxForms() {
        assertThat(Semvers.valid("1.2.3")).isEqualTo("1.2.3");
        // npm-semver NPM 模式放宽形（pi valid() 同款接受）。
        assertThat(Semvers.valid("v1.2.3")).isNotNull();
        assertThat(Semvers.valid("1.2")).isNotNull();
    }

    @Test
    void validRejectsGarbageAndEmpty() {
        assertThat(Semvers.valid("")).isNull();
        assertThat(Semvers.valid(null)).isNull();
        assertThat(Semvers.valid("not-a-version")).isNull();
    }

    @Test
    void compareOrdersPrereleasePerSemver11() {
        // semver §11 序：alpha < alpha.1 < alpha.beta < beta < beta.2 < beta.11 < rc.1 < 正式。
        var ordered = List.of(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0");
        for (var i = 0; i + 1 < ordered.size(); i++) {
            assertThat(Semvers.compare(ordered.get(i), ordered.get(i + 1)))
                .as("%s < %s", ordered.get(i), ordered.get(i + 1))
                .isNegative();
        }
        assertThat(Semvers.gt("2.0.0", "1.9.9")).isTrue();
        assertThat(Semvers.gt("1.0.0", "1.0.0-rc.1")).isTrue();
    }

    @Test
    void rcompareSortsDescending() {
        assertThat(Semvers.sortRcompare(List.of("1.0.0", "2.0.0", "1.5.0")))
            .containsExactly("2.0.0", "1.5.0", "1.0.0");
    }

    @Test
    void maxSatisfyingPicksHighestInRange() {
        var versions = List.of("1.0.0", "1.2.0", "2.0.0", "2.1.0-beta");

        assertThat(Semvers.maxSatisfying(versions, "^1.0.0")).isEqualTo("1.2.0");
        assertThat(Semvers.maxSatisfying(versions, "^3.0.0")).isNull();
    }
}
