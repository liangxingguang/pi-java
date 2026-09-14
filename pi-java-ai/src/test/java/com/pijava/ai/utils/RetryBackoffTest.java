package com.pijava.ai.utils;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * pi {@code retryDelayMs}（retry.ts:111-115）的移植哨兵（package 3d，
 * {@code docs/31 §8.22}）：2 的幂阶梯、{@code Number.isSafeInteger} 护栏、
 * 60 秒默认封顶与自定义封顶。
 */
class RetryBackoffTest {

    @Test
    void exponentialLadderUnderDefaultCap() {
        assertThat(RetryBackoff.delayMs(2_000, null, 1)).isEqualTo(2_000);
        assertThat(RetryBackoff.delayMs(2_000, null, 2)).isEqualTo(4_000);
        assertThat(RetryBackoff.delayMs(2_000, null, 4)).isEqualTo(16_000);
    }

    @Test
    void defaultCapIsSixtySeconds() {
        assertThat(RetryBackoff.delayMs(2_000, null, 6)).isEqualTo(60_000);
        assertThat(RetryBackoff.delayMs(2_000, null, 20)).isEqualTo(60_000);
    }

    @Test
    void customCapOverridesDefault() {
        assertThat(RetryBackoff.delayMs(2_000, 5_000L, 10)).isEqualTo(5_000);
        // pi 的 ?? 只兜 null/undefined；0 是有效值 ⇒ 恒 0
        assertThat(RetryBackoff.delayMs(2_000, 0L, 1)).isEqualTo(0);
    }

    @Test
    void unsafeProductsFoldToMaxSafeInteger() {
        // base * 2^(attempt-1) 早已越过 2^53-1 ⇒ 乘积折到 MAX_SAFE，
        // 封顶若更宽松（此处给一个超默认的 cap）就露出该上界本身。
        long maxSafe = 9_007_199_254_740_991L;
        assertThat(RetryBackoff.delayMs(1L << 40, maxSafe, 100)).isEqualTo(maxSafe);
        // 默认 60 秒封顶之下，护栏值不可见
        assertThat(RetryBackoff.delayMs(1L << 40, null, 100)).isEqualTo(60_000);
    }

    @Test
    void attemptZeroOrBelowUsesBaseDelay() {
        // pi 的 Math.max(0, attempt-1)：非正序号 ⇒ 2^0
        assertThat(RetryBackoff.delayMs(2_000, null, 0)).isEqualTo(2_000);
        assertThat(RetryBackoff.delayMs(2_000, null, -3)).isEqualTo(2_000);
    }
}
