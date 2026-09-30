package com.pijava.ai.protocol;

import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 B103：responses 车道 {@link SessionAffinityHeaders} 的头集钉子（纯函数）。
 */
class SessionAffinityHeadersTest {

    private static final String SID = "0192ab63-session-id";
    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1";
    private static final String FIREWORKS_URL = "https://api.fireworks.ai/inference";

    @Test
    void responsesThreeFormats() {
        assertThat(SessionAffinityHeaders.responses(SID,
            com.pijava.ai.catalog.SessionAffinityFormat.OPENROUTER,
            "p", FIREWORKS_URL)).containsExactlyEntriesOf(Map.of("x-session-id", SID));
        assertThat(SessionAffinityHeaders.responses(SID,
            com.pijava.ai.catalog.SessionAffinityFormat.OPENAI,
            "p", FIREWORKS_URL))
            .containsOnlyKeys("session_id", "x-client-request-id");
        assertThat(SessionAffinityHeaders.responses(SID,
            com.pijava.ai.catalog.SessionAffinityFormat.OPENAI_NOSESSION,
            "p", FIREWORKS_URL))
            .containsOnlyKeys("x-client-request-id");
    }

    @Test
    void responsesDetectsOpenRouter() {
        assertThat(SessionAffinityHeaders.responses(SID, null, "openrouter", OPENROUTER_URL))
            .containsOnlyKeys("x-session-id");
    }

    @Test
    void responsesBlankSessionEmitsNothing() {
        assertThat(SessionAffinityHeaders.responses("  ",
            com.pijava.ai.catalog.SessionAffinityFormat.OPENAI,
            "p", FIREWORKS_URL)).isEmpty();
    }
}
