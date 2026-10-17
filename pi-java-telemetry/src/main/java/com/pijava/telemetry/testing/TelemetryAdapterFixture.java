package com.pijava.telemetry.testing;

import java.util.List;

import com.pijava.telemetry.RecordedTelemetrySpan;
import com.pijava.telemetry.TelemetryContext;

/**
 * pi {@code testing/types.ts:5-9}：一个一致性用例独占的 adapter 实例与
 * <b>归一化</b>快照读取器（hex spanId、JSON 数字类型、行序 endSequence 等
 * 都在 {@link #getSpans()} 里归一成与参考实现可比较的形状）。
 */
public interface TelemetryAdapterFixture extends AutoCloseable {

    /** The adapter instance under test. */
    TelemetryContext context();

    /** Normalized span snapshots in span-start order. */
    List<RecordedTelemetrySpan> getSpans() throws Exception;

    /** Release adapter resources. Default no-op. */
    @Override
    default void close() throws Exception { }
}
