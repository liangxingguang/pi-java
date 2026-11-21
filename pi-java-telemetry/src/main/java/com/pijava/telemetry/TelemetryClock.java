package com.pijava.telemetry;

/**
 * pi {@code memory.ts:96-122} 的 {@code telemetryNow()} 移植：单调时钟锚到墙钟，
 * 返回按微秒取整的 epoch 毫秒。
 *
 * <p>语义：{@code anchor + monotonic} 与 {@code System.currentTimeMillis()} 相差超过
 * 10ms 时重新锚定（跟随墙钟跳变），否则同一进程内的时间保持单调与亚毫秒间距；返回值
 * 按微秒（0.001ms）取整。pi 的每个适配器与 Chord 的 SQLite 存储都用它给跨度起点打点，
 * 因此各自记录的时间可相互比较。</p>
 *
 * <p>java 偏差：pi 用 {@code performance.now() + performance.timeOrigin}；此处用
 * {@code System.nanoTime()} 作单调源，首次锚定取 {@code currentTimeMillis()} 的现值。</p>
 */
public final class TelemetryClock {

    private static final double TOLERANCE_MS = 10.0;
    private static final double NANOS_PER_MS = 1_000_000.0;
    private static final double MICROS_PER_MS = 1000.0;

    /** {@code performance.now()} 零点对应的墙钟毫秒；跟随跳变后更新（{@code memory.ts:109}）。 */
    private static double anchor = System.currentTimeMillis() - monotonicMs();

    private TelemetryClock() {
    }

    /**
     * 当前墙钟毫秒，按微秒取整（{@code memory.ts:116-122}）。
     *
     * @return epoch 毫秒，精度 0.001ms
     */
    public static double now() {
        double result;
        synchronized (TelemetryClock.class) {
            double clock = monotonicMs();
            double wall = System.currentTimeMillis();
            if (Math.abs(anchor + clock - wall) > TOLERANCE_MS) {
                anchor = wall - clock;
            }
            result = anchor + clock;
        }
        return Math.round(result * MICROS_PER_MS) / MICROS_PER_MS;
    }

    private static double monotonicMs() {
        return System.nanoTime() / NANOS_PER_MS;
    }
}
