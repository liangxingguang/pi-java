package com.pijava.ai.utils;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/19 #50：请求级 provider env 的解析顺序（scoped → process）。
 */
class ProviderEnvTest {

    @Test
    void scopedValueWinsOverProcessEnv() {
        // PATH 一定在进程环境里，用作「scoped 优先」的对照。
        var processPath = System.getenv("PATH");
        assertThat(processPath).isNotNull();

        assertThat(ProviderEnv.value("PATH", Map.of("PATH", "/scoped"))).isEqualTo("/scoped");
    }

    @Test
    void emptyScopedValueFallsBackToProcessEnv() {
        var processPath = System.getenv("PATH");

        assertThat(ProviderEnv.value("PATH", Map.of("PATH", ""))).isEqualTo(processPath);
    }

    @Test
    void blankScopedValueIsNotTreatedAsAbsent() {
        // pi 用 `||`：空串为假，空白串为真。
        assertThat(ProviderEnv.value("PATH", Map.of("PATH", "  "))).isEqualTo("  ");
    }

    @Test
    void nullOverrideReadsProcessEnv() {
        assertThat(ProviderEnv.value("PATH", null)).isEqualTo(System.getenv("PATH"));
    }

    @Test
    void missingOnBothSidesReturnsNull() {
        var name = "PI_JAVA_DOCS19_MISSING_ENV";
        assertThat(System.getenv(name)).isNull();

        assertThat(ProviderEnv.value(name, null)).isNull();
        assertThat(ProviderEnv.value(name, Map.of())).isNull();
    }
}
