package com.pijava.coding.agent.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A5（docs/25）：{@link PromptRouter} 门逻辑 —— pi {@code prompt()}
 * （{@code agent-session.ts:1939-1979}）的压缩门/流中门逐字移植。
 */
class PromptRouterTest {

    private final PromptRouter router = new PromptRouter();

    @Test
    void throwsWhileCompactingRegardlessOfBehavior() {
        for (var behavior : new StreamingBehavior[] {null, StreamingBehavior.STEER,
                StreamingBehavior.FOLLOW_UP}) {
            assertThatThrownBy(() -> router.route(true, false, behavior))
                .isInstanceOf(IllegalStateException.class)
                // 字面量断言（不用常量自比＝没牙），文本逐字对齐 pi。
                .hasMessage("Cannot submit a prompt while compaction is in progress. "
                    + "Wait for compaction to finish and retry.");
        }
    }

    @Test
    void throwsWhenRunningWithoutBehavior() {
        assertThatThrownBy(() -> router.route(false, true, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Agent is already processing. Specify streamingBehavior "
                + "('steer' or 'followUp') to queue the message.");
    }

    @Test
    void queuesSteerWhenRunningWithSteer() {
        assertThat(router.route(false, true, StreamingBehavior.STEER))
            .isEqualTo(PromptRouter.Verdict.QUEUE_STEER);
    }

    @Test
    void queuesFollowUpWhenRunningWithFollowUp() {
        assertThat(router.route(false, true, StreamingBehavior.FOLLOW_UP))
            .isEqualTo(PromptRouter.Verdict.QUEUE_FOLLOW_UP);
    }

    @Test
    void startsWhenIdle() {
        assertThat(router.route(false, false, null))
            .isEqualTo(PromptRouter.Verdict.START);
        assertThat(router.route(false, false, StreamingBehavior.STEER))
            .as("空闲时显式 steer：pi 的 prompt() 不起排队分支，照常起 run")
            .isEqualTo(PromptRouter.Verdict.START);
        assertThat(router.route(false, false, StreamingBehavior.FOLLOW_UP))
            .isEqualTo(PromptRouter.Verdict.START);
    }
}
