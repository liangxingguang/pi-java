package com.pijava.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * <b>docs/71 G1</b>：提示词路径造出的用户消息必须带时间戳。
 *
 * <p>pi 的用户消息在每个构造点都给 {@code timestamp: Date.now()}
 * （{@code agent.ts:422}）；本仓此前 {@code UserMessage} 连字段都没有。</p>
 */
class HarnessUtilsUserTimestampTest {

    @Test
    void promptPathStampsTheUserMessage() {
        var message = HarnessUtils.buildUserMessage("hi", List.of());

        assertThat(message.timestamp()).isNotNull();
        assertThat(message.content()).hasSize(1);
    }
}
