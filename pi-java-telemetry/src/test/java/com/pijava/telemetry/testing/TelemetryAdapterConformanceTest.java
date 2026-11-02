package com.pijava.telemetry.testing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryAdapterConformanceTest {

    @Test
    void exposesTenApplicableCasesInSixGroups() {
        var cases = TelemetryAdapterConformance.createTelemetryAdapterConformance(() -> {
            throw new AssertionError("case list inspection must not create a fixture");
        });

        assertThat(cases).hasSize(10);
        assertThat(cases.stream().map(TelemetryAdapterConformanceCase::group).distinct())
            .containsExactlyInAnyOrder(
                "capabilities", "metrics", "callback lifecycle", "status", "recording", "parentage");
        assertThat(cases).anySatisfy(testCase -> {
            assertThat(testCase.group()).isEqualTo("capabilities");
            assertThat(testCase.name()).isEqualTo("carries adapter capabilities through contexts and spans");
        });
        assertThat(cases).anySatisfy(testCase ->
            assertThat(testCase.name()).isEqualTo("records ordered measurements and optional histogram exemplars"));
        assertThat(cases).anySatisfy(testCase ->
            assertThat(testCase.name()).isEqualTo("copies instrument metadata and measurement attributes"));
    }
}
