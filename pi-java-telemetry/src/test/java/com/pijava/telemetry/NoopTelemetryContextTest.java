package com.pijava.telemetry;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoopTelemetryContextTest {

    @Test
    void startSpanReturnsCallbackResult() {
        var ctx = NoopTelemetryContext.INSTANCE;
        var result = ctx.startSpan(
            new SpanOptions("test.span", Map.of()),
            span -> "ok"
        );
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void nestedSpansReturnCallbackResults() {
        var ctx = NoopTelemetryContext.INSTANCE;
        var result = ctx.startSpan(
            new SpanOptions("outer"),
            outer -> outer.startSpan(
                new SpanOptions("inner"),
                inner -> "nested"
            )
        );
        assertThat(result).isEqualTo("nested");
    }

    @Test
    void spanCloseIsIdempotent() {
        var ctx = NoopTelemetryContext.INSTANCE;
        ctx.startSpan(new SpanOptions("test"), span -> {
            span.close();
            span.close(); // second close is a no-op
            return null;
        });
    }
}
