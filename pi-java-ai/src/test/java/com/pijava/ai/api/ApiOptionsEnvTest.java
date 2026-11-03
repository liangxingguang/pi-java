package com.pijava.ai.api;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.pijava.ai.identity.ClientIdentity;
import com.pijava.ai.identity.AttributionSetting;
import com.pijava.ai.identity.UserAgentMode;
import com.pijava.telemetry.InMemoryTelemetryContext;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.TelemetrySpan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/19 #50：{@code ApiOptions} 的第九个组件 {@code env}。
 */
class ApiOptionsEnvTest {

    private static final Map<String, String> ENV = Map.of("REGION", "eu");
    private static final TelemetrySpan SENTINEL =
        new InMemoryTelemetryContext().openSpan(new SpanOptions("sentinel"));
    private static final ClientIdentity IDENTITY = new ClientIdentity(
        UserAgentMode.SHORT, AttributionSetting.OFF, "1.0.0");

    @Test
    void nineArgConstructorCarriesEnv() {
        var options = new ApiOptions("", "k", Duration.ofSeconds(1), 0, Map.of(),
            AuthKind.API_KEY, SENTINEL, IDENTITY, ENV);

        assertThat(options.env()).containsEntry("REGION", "eu");
        assertThat(options.telemetry()).isSameAs(SENTINEL);
        assertThat(options.identity()).isSameAs(IDENTITY);
    }

    @Test
    void envIsDefensivelyCopied() {
        var mutable = new LinkedHashMap<String, String>();
        mutable.put("REGION", "eu");
        var options = new ApiOptions("", "k", Duration.ofSeconds(1), 0, Map.of(),
            AuthKind.API_KEY, null, null, mutable);

        mutable.put("REGION", "us");

        assertThat(options.env()).containsEntry("REGION", "eu");
    }

    @Test
    void convenienceConstructorsHaveNoEnv() {
        assertThat(new ApiOptions("", "k", Duration.ofSeconds(1), 0, Map.of()).env()).isNull();
        assertThat(new ApiOptions("", "k", Duration.ofSeconds(1), 0, Map.of(),
            AuthKind.BEARER).env()).isNull();
        assertThat(new ApiOptions("", "k", Duration.ofSeconds(1), 0, Map.of(),
            AuthKind.BEARER, SENTINEL).env()).isNull();
        assertThat(new ApiOptions("", "k", Duration.ofSeconds(1), 0, Map.of(),
            AuthKind.BEARER, SENTINEL, IDENTITY).env()).isNull();
        assertThat(ApiOptions.defaults().env()).isNull();
    }

    @Test
    void withIdentityKeepsEnv() {
        var options = new ApiOptions("", "k", Duration.ofSeconds(1), 0, Map.of(),
            AuthKind.API_KEY, SENTINEL, null, ENV);

        var withIdentity = ApiOptions.withIdentity(options, IDENTITY);

        assertThat(withIdentity.env()).isEqualTo(ENV);
        assertThat(withIdentity.identity()).isSameAs(IDENTITY);
        assertThat(withIdentity.telemetry()).isSameAs(SENTINEL);
    }

    @Test
    void withEnvKeepsIdentityAndTelemetry() {
        var options = new ApiOptions("", "k", Duration.ofSeconds(1), 0, Map.of(),
            AuthKind.API_KEY, SENTINEL, IDENTITY, null);

        var withEnv = ApiOptions.withEnv(options, ENV);

        assertThat(withEnv.env()).isEqualTo(ENV);
        assertThat(withEnv.identity()).isSameAs(IDENTITY);
        assertThat(withEnv.telemetry()).isSameAs(SENTINEL);
    }
}
