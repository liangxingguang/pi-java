package com.pijava.ai.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pi {@code anthropic-messages.ts:264-306}：会话态的读、单次消费、{@code off} 终局、容量与清理。
 */
class AnthropicThreadStateTest {

    private static final AnthropicThreadState.Entry ENTRY =
        new AnthropicThreadState.Entry(3, "hash-a", "msg_1");

    @AfterEach
    void cleanup() {
        AnthropicThreadState.clear(null);
    }

    @Test
    void consumeIsSingleUse() {
        AnthropicThreadState.put("s", ENTRY);

        assertThat(AnthropicThreadState.consume("s")).contains(ENTRY);
        assertThat(AnthropicThreadState.consume("s")).isEmpty();
        assertThat(AnthropicThreadState.get("s")).isEmpty();
    }

    @Test
    void offIsTerminalUntilCleared() {
        AnthropicThreadState.put("s", ENTRY);
        AnthropicThreadState.off("s");

        assertThat(AnthropicThreadState.isOff("s")).isTrue();
        assertThat(AnthropicThreadState.get("s")).isEmpty();
        assertThat(AnthropicThreadState.consume("s")).isEmpty();

        AnthropicThreadState.clear("s");
        assertThat(AnthropicThreadState.isOff("s")).isFalse();
    }

    @Test
    void noSessionIdIsNeverTracked() {
        AnthropicThreadState.put(null, ENTRY);
        AnthropicThreadState.off("");

        assertThat(AnthropicThreadState.get(null)).isEmpty();
        assertThat(AnthropicThreadState.isOff(null)).isFalse();
        assertThat(AnthropicThreadState.size()).isZero();
    }

    @Test
    void dropsOldestSessionBeyondCapacity() {
        for (int index = 0; index <= AnthropicThreadState.MAX_SESSIONS; index++) {
            AnthropicThreadState.put("s" + index, ENTRY);
        }

        assertThat(AnthropicThreadState.get("s0")).isEmpty();
        assertThat(AnthropicThreadState.get("s1")).isPresent();
        assertThat(AnthropicThreadState.size()).isEqualTo(AnthropicThreadState.MAX_SESSIONS);
    }
}
