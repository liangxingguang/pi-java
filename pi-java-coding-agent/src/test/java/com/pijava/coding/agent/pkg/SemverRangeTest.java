package com.pijava.coding.agent.pkg;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 18 T3（docs/29 §3/§7）：range satisfies 真值表——精确/{@code ^}/{@code ~}/
 * {@code *}/{@code x}/比较符集/空格 AND/{@code ||} OR。hyphen range 探测结果
 * 决定窄差 D106 的注记（semver4j 达标则改注）。
 */
class SemverRangeTest {

    @Test
    void exactVersionMatchesOnlyItself() {
        assertThat(Semvers.satisfies("1.2.3", "1.2.3")).isTrue();
        assertThat(Semvers.satisfies("1.2.4", "1.2.3")).isFalse();
    }

    @Test
    void caretRange() {
        assertThat(Semvers.satisfies("1.9.0", "^1.2.0")).isTrue();
        assertThat(Semvers.satisfies("2.0.0", "^1.2.0")).isFalse();
        // ^0.x 锁定次版本（npm 语义）。
        assertThat(Semvers.satisfies("0.2.9", "^0.2.0")).isTrue();
        assertThat(Semvers.satisfies("0.3.0", "^0.2.0")).isFalse();
    }

    @Test
    void tildeRange() {
        assertThat(Semvers.satisfies("1.2.9", "~1.2.0")).isTrue();
        assertThat(Semvers.satisfies("1.3.0", "~1.2.0")).isFalse();
    }

    @Test
    void starAndXRanges() {
        assertThat(Semvers.satisfies("9.9.9", "*")).isTrue();
        assertThat(Semvers.satisfies("1.2.7", "1.2.x")).isTrue();
        assertThat(Semvers.satisfies("1.3.0", "1.2.x")).isFalse();
        assertThat(Semvers.satisfies("1.9.0", "1.x")).isTrue();
    }

    @Test
    void comparatorSetsAndSpaceAnd() {
        assertThat(Semvers.satisfies("1.5.0", ">=1.0.0 <2.0.0")).isTrue();
        assertThat(Semvers.satisfies("2.0.0", ">=1.0.0 <2.0.0")).isFalse();
        assertThat(Semvers.satisfies("0.9.0", ">=1.0.0")).isFalse();
    }

    @Test
    void orRanges() {
        assertThat(Semvers.satisfies("1.5.0", "^1.0.0 || ^2.0.0")).isTrue();
        assertThat(Semvers.satisfies("2.5.0", "^1.0.0 || ^2.0.0")).isTrue();
        assertThat(Semvers.satisfies("3.0.0", "^1.0.0 || ^2.0.0")).isFalse();
    }

    @Test
    void hyphenRangeProbe() {
        // D106 探针：semver4j NPM range 若覆盖 hyphen ⇒ 闭环时改注 D106。
        assertThat(Semvers.validRange("1.2.0 - 2.3.0")).isNotNull();
        assertThat(Semvers.satisfies("1.5.0", "1.2.0 - 2.3.0")).isTrue();
        assertThat(Semvers.satisfies("2.4.0", "1.2.0 - 2.3.0")).isFalse();
    }

    @Test
    void garbageRangeIsInvalidAndNeverSatisfied() {
        assertThat(Semvers.validRange("not a version!!!")).isNull();
        assertThat(Semvers.satisfies("1.2.3", "not a version!!!")).isFalse();
    }

    @Test
    void invalidVersionNeverSatisfies() {
        assertThat(Semvers.satisfies("garbage", "*")).isFalse();
        assertThat(Semvers.satisfies(null, "*")).isFalse();
    }
}
