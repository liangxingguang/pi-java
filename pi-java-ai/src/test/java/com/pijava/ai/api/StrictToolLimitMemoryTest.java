package com.pijava.ai.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pi {@code test/strict-tool-limits.test.ts} 的 Java 对位：会话记忆、容量淘汰、
 * 清理语义。
 */
class StrictToolLimitMemoryTest {

    @AfterEach
    void cleanup() {
        StrictToolLimitMemory.clear(null);
    }

    /** 记住到清理为止；无会话号不记账（pi 的 {@code undefined} 分支）。 */
    @Test
    void remembersMarkedSessionsUntilCleanup() {
        StrictToolLimitMemory.mark("a");
        StrictToolLimitMemory.mark("b");
        StrictToolLimitMemory.mark(null);

        assertThat(StrictToolLimitMemory.exceeded("a")).isTrue();
        assertThat(StrictToolLimitMemory.exceeded("c")).isFalse();
        assertThat(StrictToolLimitMemory.exceeded(null)).isFalse();

        StrictToolLimitMemory.clear("a");
        assertThat(StrictToolLimitMemory.exceeded("a")).isFalse();
        assertThat(StrictToolLimitMemory.exceeded("b")).isTrue();

        StrictToolLimitMemory.clear(null);
        assertThat(StrictToolLimitMemory.exceeded("b")).isFalse();
    }

    /** pi {@code strict-tool-limits.ts:48-51}：经会话资源清理表释放，不必手工 {@code clear}。 */
    @Test
    void sessionResourcesCleanupReleasesTheMemory() {
        StrictToolLimitMemory.mark("a");
        assertThat(StrictToolLimitMemory.exceeded("a")).isTrue();

        SessionResources.cleanup("a");

        assertThat(StrictToolLimitMemory.exceeded("a")).isFalse();
    }

    /** 容量上限：第 10,001 个会话挤掉最旧（pi 的 {@code drops the oldest session}）。 */
    @Test
    void dropsOldestSessionBeyondCapacity() {
        for (int index = 0; index <= StrictToolLimitMemory.MAX_SESSIONS; index++) {
            StrictToolLimitMemory.mark("session-" + index);
        }

        assertThat(StrictToolLimitMemory.exceeded("session-0")).isFalse();
        assertThat(StrictToolLimitMemory.exceeded("session-1")).isTrue();
        assertThat(StrictToolLimitMemory.exceeded(
            "session-" + StrictToolLimitMemory.MAX_SESSIONS)).isTrue();
    }
}
