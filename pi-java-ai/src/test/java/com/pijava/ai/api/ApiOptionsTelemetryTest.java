package com.pijava.ai.api;

import java.time.Duration;
import java.util.Map;

import com.pijava.telemetry.InMemoryTelemetryContext;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.TelemetrySpan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/13 §4.2：{@code ApiOptions} 的第七个组件 {@code telemetry}。
 *
 * <p>存量五参／六参构造器与 {@code defaults()} 必须继续把 telemetry 置空，
 * 使本包之前的调用点零改签。七参构造器才携带显式父跨度。</p>
 */
class ApiOptionsTelemetryTest {

    private static TelemetrySpan sentinel() {
        return new InMemoryTelemetryContext().openSpan(new SpanOptions("sentinel"));
    }

    @Test
    void fiveArgConstructorCarriesNoTelemetry() {
        var options = new ApiOptions("", "k", Duration.ofSeconds(1), 0, Map.of());

        assertThat(options.telemetry()).isNull();
        assertThat(options.authKind()).isEqualTo(AuthKind.API_KEY);
    }

    @Test
    void sixArgConstructorCarriesNoTelemetry() {
        var options = new ApiOptions(
            "", "k", Duration.ofSeconds(1), 0, Map.of(), AuthKind.BEARER);

        assertThat(options.telemetry()).isNull();
        assertThat(options.authKind()).isEqualTo(AuthKind.BEARER);
    }

    @Test
    void sevenArgConstructorCarriesTelemetry() {
        var span = sentinel();
        var options = new ApiOptions(
            "", "k", Duration.ofSeconds(1), 0, Map.of(), AuthKind.OAUTH, span);

        assertThat(options.telemetry()).isSameAs(span);
        assertThat(options.authKind()).isEqualTo(AuthKind.OAUTH);
    }

    @Test
    void defaultsCarryNoTelemetry() {
        assertThat(ApiOptions.defaults().telemetry()).isNull();
    }
}
