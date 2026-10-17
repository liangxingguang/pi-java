package com.pijava.telemetry.testing;

/**
 * pi {@code testing/types.ts:12}：为一个一致性用例创建隔离的 adapter fixture。
 */
@FunctionalInterface
public interface TelemetryAdapterFixtureFactory {

    /** Create a fresh fixture owned by one conformance case. */
    TelemetryAdapterFixture create() throws Exception;
}
