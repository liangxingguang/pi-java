package com.pijava.telemetry;

import java.util.Map;
import java.util.Set;

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

    @Test
    void openSpanReturnsSpanAndSupportsAttributes() {
        var ctx = NoopTelemetryContext.INSTANCE;
        var span = ctx.openSpan(new SpanOptions("test.open"));
        span.addAttribute("key", "value");
        span.close();
        span.close(); // idempotent
    }

    @Test
    void openSpanNestsUnderCurrentSpan() {
        var ctx = NoopTelemetryContext.INSTANCE;
        ctx.startSpan(new SpanOptions("outer"), outer -> {
            var inner = outer.openSpan(new SpanOptions("inner"));
            inner.addAttribute("k", 42);
            inner.close();
            return null;
        });
    }

    /**
     * pi {@code telemetry.test.ts:187-197}：不检查、不保留遥测载荷——
     * 炸弹 Map（任何读取都抛）喂给跨度三方法也不抛。
     */
    @Test
    void doesNotInspectOrRetainPayloads() {
        var ctx = NoopTelemetryContext.INSTANCE;
        Map<String, Object> bomb = new java.util.AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                throw new IllegalStateException("read");
            }
        };
        ctx.startSpan(new SpanOptions("operation"), span -> {
            span.addEvent("event", bomb);
            span.setAttributes(bomb);
            span.setStatus(new SpanStatus.Ok());
            return null;
        });
    }
}
