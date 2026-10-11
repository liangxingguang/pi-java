package com.pijava.ai.protocol;

import java.util.Map;

import com.pijava.ai.catalog.SessionAffinityFormat;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B103（docs/52）：{@link SessionAffinityHeaders} 三车道形状的纯函数钉子，期望值逐字
 * 对齐 pi {@code openai-completions.ts:776-786}、{@code openai-responses.ts:277-286}、
 * {@code anthropic-messages.ts:1038-1043}。
 */
class SessionAffinityHeadersTest {

    private static final String SID = "0192ab63-session-affinity-key";

    // ── completions：openai 三头 / nosession 两头 / openrouter 一头 ──

    @Test
    void completionsOpenaiSendsThreeHeaders() {
        assertThat(SessionAffinityHeaders.completions(SID, true,
            SessionAffinityFormat.OPENAI, "fireworks-completions", "http://localhost/v1"))
            .containsExactlyInAnyOrderEntriesOf(Map.of(
                "session_id", SID,
                "x-client-request-id", SID,
                "x-session-affinity", SID));
    }

    @Test
    void completionsOpenaiNosessionSendsTwoHeaders() {
        assertThat(SessionAffinityHeaders.completions(SID, true,
            SessionAffinityFormat.OPENAI_NOSESSION, "x", "http://y"))
            .containsOnlyKeys("x-client-request-id", "x-session-affinity");
    }

    @Test
    void completionsOpenrouterSendsSingleSessionIdHeader() {
        assertThat(SessionAffinityHeaders.completions(SID, true,
            SessionAffinityFormat.OPENROUTER, "x", "http://y"))
            .containsExactlyInAnyOrderEntriesOf(Map.of("x-session-id", SID));
    }

    @Test
    void completionsDetectsFormatWhenExplicitIsNull() {
        // pi detectCompat :1680-1681：openrouter 端点 ⇒ openrouter；其余 ⇒ openai。
        assertThat(SessionAffinityHeaders.completions(SID, true,
            null, "openrouter", "https://openrouter.ai/api/v1"))
            .containsOnlyKeys("x-session-id");
        assertThat(SessionAffinityHeaders.completions(SID, true,
            null, "deepseek", "https://api.deepseek.com"))
            .containsOnlyKeys("session_id", "x-client-request-id", "x-session-affinity");
    }

    @Test
    void completionsGateFalseOrBlankIdYieldsNothing() {
        assertThat(SessionAffinityHeaders.completions(SID, false,
            SessionAffinityFormat.OPENAI, "x", "http://y")).isEmpty();
        assertThat(SessionAffinityHeaders.completions("  ", true,
            SessionAffinityFormat.OPENAI, "x", "http://y")).isEmpty();
    }

    // ── anthropic：单头，只有 openrouter / 非 openrouter 之别 ──

    @Test
    void anthropicNonOpenrouterSendsSessionAffinity() {
        // 显式 openai/openai-nosession 在 anthropic 也落 x-session-affinity（pi 只比 === "openrouter"）。
        assertThat(SessionAffinityHeaders.anthropic(SID, true,
            null, "fireworks-anthropic", "http://localhost/v1"))
            .containsExactlyInAnyOrderEntriesOf(Map.of("x-session-affinity", SID));
        assertThat(SessionAffinityHeaders.anthropic(SID, true,
            SessionAffinityFormat.OPENAI, "x", "http://y"))
            .containsOnlyKeys("x-session-affinity");
    }

    @Test
    void anthropicOpenrouterSendsSessionIdByProviderOrUrlOrExplicitFormat() {
        assertThat(SessionAffinityHeaders.anthropic(SID, true,
            null, "openrouter", "https://openrouter.ai/api"))
            .containsOnlyKeys("x-session-id");
        assertThat(SessionAffinityHeaders.anthropic(SID, true,
            null, "relay", "https://foo.openrouter.ai/bar"))
            .containsOnlyKeys("x-session-id");
        assertThat(SessionAffinityHeaders.anthropic(SID, true,
            SessionAffinityFormat.OPENROUTER, "x", "http://y"))
            .containsOnlyKeys("x-session-id");
    }

    @Test
    void anthropicGateFalseYieldsNothing() {
        assertThat(SessionAffinityHeaders.anthropic(SID, false,
            null, "openrouter", "https://openrouter.ai/api")).isEmpty();
    }

    // ── responses：无 send 门、不发 x-session-affinity（回归既有形状）──

    @Test
    void responsesShapesUnchanged() {
        assertThat(SessionAffinityHeaders.responses(SID,
            SessionAffinityFormat.OPENAI, "x", "http://y"))
            .containsOnlyKeys("session_id", "x-client-request-id");
        assertThat(SessionAffinityHeaders.responses(SID,
            SessionAffinityFormat.OPENAI_NOSESSION, "x", "http://y"))
            .containsOnlyKeys("x-client-request-id");
        assertThat(SessionAffinityHeaders.responses(SID,
            SessionAffinityFormat.OPENROUTER, "x", "http://y"))
            .containsOnlyKeys("x-session-id");
        assertThat(SessionAffinityHeaders.responses("",
            SessionAffinityFormat.OPENAI, "x", "http://y")).isEmpty();
    }
}
