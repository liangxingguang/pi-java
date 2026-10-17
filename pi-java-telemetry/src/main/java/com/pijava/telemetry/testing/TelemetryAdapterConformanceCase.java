package com.pijava.telemetry.testing;

/**
 * pi {@code testing/types.ts:14-18}：runner 无关的一致性用例，可注册进任何
 * 测试框架（JUnit 侧用 {@code @TestFactory} → {@code DynamicTest}）。
 *
 * @param group 用例组（pi 的 {@code describe} 组名）
 * @param name  用例名
 * @param run   用例体；断言失败抛 {@link AssertionError}，环境失败抛原异常
 */
public record TelemetryAdapterConformanceCase(String group, String name, ThrowingRunnable run) {

    /** A case body that may fail with any exception. */
    @FunctionalInterface
    public interface ThrowingRunnable {

        /** Run the case. */
        void run() throws Exception;
    }
}
