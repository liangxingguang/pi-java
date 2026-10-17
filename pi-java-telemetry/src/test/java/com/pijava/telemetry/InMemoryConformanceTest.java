package com.pijava.telemetry;

import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.pijava.telemetry.testing.TelemetryAdapterConformance;
import com.pijava.telemetry.testing.TelemetryAdapterFixture;

/**
 * 一致性套件挂 {@link InMemoryTelemetryContext}——pi
 * {@code conformance.test.ts:5-21} 的对应挂载（docs/23 §4.3）。
 */
class InMemoryConformanceTest {

    @TestFactory
    Collection<DynamicTest> conformance() {
        return TelemetryAdapterConformance.createTelemetryAdapterConformance(() -> {
                var context = new InMemoryTelemetryContext();
                return new TelemetryAdapterFixture() {
                    @Override
                    public TelemetryContext context() {
                        return context;
                    }

                    @Override
                    public List<RecordedTelemetrySpan> getSpans() {
                        return context.getSpans();
                    }
                };
            })
            .stream()
            .map(testCase -> DynamicTest.dynamicTest(
                testCase.group() + " / " + testCase.name(),
                () -> testCase.run().run()))
            .toList();
    }
}
