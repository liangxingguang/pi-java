package com.pijava.ai.provider.faux;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.TranscriptContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/20 §4：usage 估算与 prompt-cache 模拟，逐条对位 pi
 * {@code providers/faux.ts:246-287}。
 */
class FauxUsageEstimateTest {

    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final String SESSION = "sessionId";

    private static Message system(String text) {
        return new Message.SystemMessage(text, NOW, Map.of(), List.of(), List.of());
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)), NOW);
    }

    private static AssistantMessage assistant(String text) {
        return new AssistantMessage(
            "m", List.of(new ContentBlock.TextContent(text)), null, StopReason.STOP,
            "faux", "faux", "faux-1", null, null, null, NOW, null, null, null);
    }

    private static ApiOptions options(Map<String, Object> extra) {
        return ApiOptions.withExtra(ApiOptions.defaults(), extra);
    }

    @Test
    void withoutASessionTheWholePromptCountsAsInput() {
        var transcript = new TranscriptContext(List.of(system("sys"), user("hello")));
        var estimated = new FauxUsageEstimator().estimate(assistant("bye"), transcript,
            ApiOptions.defaults());

        // "system:sys"(10) + "user:hello"(10) joined with "\n\n" = 22 chars -> 6 tokens
        assertThat(estimated.usage().inputTokens()).isEqualTo(6);
        assertThat(estimated.usage().outputTokens()).isEqualTo(1);
        assertThat(estimated.usage().usage().input()).isEqualTo(6);
        assertThat(estimated.usage().usage().output()).isEqualTo(1);
        assertThat(estimated.usage().usage().cacheRead()).isZero();
        assertThat(estimated.usage().usage().cacheWrite()).isZero();
        assertThat(estimated.usage().usage().totalTokens()).isEqualTo(7);
    }

    @Test
    void theFirstRequestInASessionOnlyWritesTheCache() {
        var estimator = new FauxUsageEstimator();
        var transcript = new TranscriptContext(List.of(system("sys"), user("hello")));

        var estimated = estimator.estimate(assistant("bye"), transcript,
            options(Map.of(SESSION, "s1")));

        assertThat(estimated.usage().usage().input()).isZero();
        assertThat(estimated.usage().usage().cacheWrite()).isEqualTo(6);
        assertThat(estimated.usage().usage().cacheRead()).isZero();
        assertThat(estimated.usage().usage().totalTokens()).isEqualTo(7);
    }

    @Test
    void anExtendedPromptReadsTheCommonPrefixAndWritesTheRest() {
        var estimator = new FauxUsageEstimator();
        var opts = options(Map.of(SESSION, "s1"));

        estimator.estimate(assistant("bye"),
            new TranscriptContext(List.of(system("sys"), user("hello"))), opts);

        var estimated = estimator.estimate(assistant("bye"),
            new TranscriptContext(List.of(system("sys"), user("hello"), user("again"))), opts);

        // 34 chars -> 9 prompt tokens; the first two messages (22 chars -> 6) are cached
        assertThat(estimated.usage().usage().input()).isZero();
        assertThat(estimated.usage().usage().cacheRead()).isEqualTo(6);
        assertThat(estimated.usage().usage().cacheWrite()).isEqualTo(3);
        assertThat(estimated.usage().usage().totalTokens()).isEqualTo(10);
    }

    @Test
    void theFirstDifferingMessageLimitsTheCommonPrefix() {
        var estimator = new FauxUsageEstimator();
        var opts = options(Map.of(SESSION, "s1"));

        estimator.estimate(assistant("bye"),
            new TranscriptContext(List.of(user("a"))), opts);

        var estimated = estimator.estimate(assistant("bye"),
            new TranscriptContext(List.of(user("b"))), opts);

        // "user:a" and "user:b" share the 5-char "user:" prefix -> ceil(5/4)=2
        assertThat(estimated.usage().usage().cacheRead()).isEqualTo(2);
        assertThat(estimated.usage().usage().cacheWrite()).isZero();
        assertThat(estimated.usage().usage().input()).isZero();
    }

    @Test
    void cacheRetentionNoneTurnsTheSimulationOff() {
        var estimator = new FauxUsageEstimator();
        var opts = options(Map.of(SESSION, "s1", "cacheRetention", "none"));

        estimator.estimate(assistant("bye"),
            new TranscriptContext(List.of(system("sys"), user("hello"))), opts);

        var estimated = estimator.estimate(assistant("bye"),
            new TranscriptContext(List.of(system("sys"), user("hello"), user("again"))), opts);

        assertThat(estimated.usage().usage().input()).isEqualTo(9);
        assertThat(estimated.usage().usage().cacheRead()).isZero();
        assertThat(estimated.usage().usage().cacheWrite()).isZero();
        assertThat(estimated.usage().usage().totalTokens()).isEqualTo(10);
    }

    @Test
    void sessionsDoNotShareTheirPromptCaches() {
        var estimator = new FauxUsageEstimator();
        var transcript = new TranscriptContext(List.of(system("sys"), user("hello")));

        estimator.estimate(assistant("bye"), transcript, options(Map.of(SESSION, "s1")));
        var second = estimator.estimate(assistant("bye"), transcript,
            options(Map.of(SESSION, "s2")));

        assertThat(second.usage().usage().input()).isZero();
        assertThat(second.usage().usage().cacheRead()).isZero();
        assertThat(second.usage().usage().cacheWrite()).isEqualTo(6);
    }

    @Test
    void imagesRenderAsStablePlaceholders() {
        var transcript = new TranscriptContext(List.of(new Message.UserMessage(
            List.of(new ContentBlock.ImageContent("image/png", "AAAA")), NOW)));

        var estimated = new FauxUsageEstimator().estimate(assistant(""), transcript,
            ApiOptions.defaults());

        // "user:[image:image/png:4]" = 24 chars -> 6 tokens
        assertThat(estimated.usage().inputTokens()).isEqualTo(6);
        assertThat(estimated.usage().outputTokens()).isZero();
        assertThat(estimated.usage().usage().totalTokens()).isEqualTo(6);
    }

    @Test
    void toolDeclarationsUseThePiToolShapeNotTheJavaDefinition() {
        var declared = new ToolDefinition("read", "Read a file", Map.of("type", "object"));
        var transcript = new TranscriptContext(List.of(new Message.SystemMessage(
            List.of(), NOW, Map.of(), List.of(declared), List.of())));

        var estimated = new FauxUsageEstimator().estimate(assistant(""), transcript,
            ApiOptions.defaults());

        // "system:tool+:" + {"name":"read","description":"Read a file",
        // "parameters":{"type":"object"}} = 87 chars -> 22 tokens
        assertThat(estimated.usage().inputTokens()).isEqualTo(22);
    }
}
