package com.pijava.telemetry;

import java.util.Map;
import java.util.function.Function;

/**
 * A telemetry context capable of starting child spans and recording
 * counters/timings.
 *
 * <p>Implementations may be no-op, logging-only, or full OpenTelemetry
 * exporters. The callback style ensures spans are always closed.</p>
 *
 * <p><b>Port note — extensions over pi, and two structural differences.</b>
 * This interface is a port of pi's {@code packages/telemetry} contract, but pi's
 * {@code TelemetryContext} declares <em>exactly one</em> method
 * ({@code startSpan(options, callback)}).  Everything else here — {@link #openSpan},
 * {@link #recordEvent}, {@link #pushCurrent}/{@link #popCurrent},
 * {@link #recordsPayloads}, {@link #with} and the two metric methods — is a
 * pi-java addition, as are the {@link JsonlFileTelemetry} and
 * {@link OtelTelemetryContext} adapters (pi ships only a no-op adapter plus a
 * test-only in-memory one).  Two differences are <em>structural</em> and cannot
 * be closed: the callback here is synchronous ({@link Function}) where pi returns
 * a {@code Promise}, and a span here is {@link AutoCloseable} where pi's has no
 * {@code end()} because {@code startSpan} owns settlement (adjudication:
 * {@code 原 docs/31 §8.28}, upheld by docs/23 §7.3).  pi's span-side
 * {@code addEvent}/{@code setAttributes}/{@code setStatus} are ported on
 * {@link TelemetrySpan} (docs/23 §2).</p>
 */
@FunctionalInterface
public interface TelemetryContext {

    /**
     * Start a new span, invoke the callback with it, and close the span
     * when the callback returns (or throws) — #92（docs/25 §11.2）按实现现状：
     *
     * <ul>
     *   <li><b>回调作用域绑定</b>：{@link JsonlFileTelemetry} 在回调期间把跨度
     *       {@link #pushCurrent} 为本线程的当前跨度（{@link #recordEvent} 的归属），
     *       回调退出在 finally 里<b>对称解绑</b>（pop 只在栈顶是本跨度时生效，
     *       内外嵌套互不拆台）；</li>
     *   <li><b>异常 ⇒ 自动 error 态</b>：回调抛出时跨度记 error
     *       （{@code JsonlSpan} 只在无显式状态时派生，pi memory.ts:96；
     *       OTel 适配器 {@code recordException}＋{@code StatusCode.ERROR}），
     *       异常原样重抛；</li>
     *   <li>跨度<b>必然关闭</b>（try/finally 或 try-with-resources），
     *       返回值即回调返回值。</li>
     * </ul>
     *
     * @param <T>     the result type
     * @param options span name and attributes
     * @param body    work to perform within the span
     * @return the value returned by {@code body}
     */
    <T> T startSpan(SpanOptions options, Function<? super TelemetrySpan, ? extends T> body);

    /**
     * Open a span <b>without</b> a callback — #92：与 {@link #startSpan} 的差别
     * 是<b>不绑定、不自动关闭</b>：调用方自持柄并负责 {@code close()}（close 即
     * 结束跨度）。用于必须跨 action/线程边界的跨度（如 {@code harness.run} 跨整个
     * 驱动、{@code tool.execute} 跨工具线程）；绑定给 {@link #recordEvent} 用要
     * 显式 {@link #pushCurrent}。
     */
    default TelemetrySpan openSpan(SpanOptions options) {
        var holder = new Object() { TelemetrySpan span; };
        startSpan(options, span -> {
            holder.span = span;
            return null;
        });
        return holder.span;
    }

    /** Increment a counter metric. Default no-op. */
    default void incrementCounter(String name, long delta) { }

    /** Record a timing metric in milliseconds. Default no-op. */
    default void recordTiming(String name, long durationMs) { }

    /** Return a child context with an additional dimension. Default returns {@code this}. */
    default TelemetryContext with(String key, String value) {
        return this;
    }

    /**
     * Record a structured event line bound to the current span (see {@link
     * #pushCurrent}).  Payload capture (e.g. {@code --trace-payloads}) is an
     * opt-in: exporters that do not record payloads ignore this. Default no-op.
     *
     * @param name    event name (e.g. "llm.payload.request")
     * @param payload structured payload (may contain nested maps/lists)
     */
    default void recordEvent(String name, Map<String, Object> payload) { }

    /**
     * Whether this context actually records event lines.  Callers use it to
     * skip building an expensive payload when recording is disabled (the
     * default). Default {@code false}.
     */
    default boolean recordsPayloads() {
        return false;
    }

    /**
     * Bind a span (opened on this or another thread) as the current span for
     * {@link #recordEvent} <b>on this thread</b> — #92：线程归属是绑定栈的
     * 本质（{@code JsonlFileTelemetry} 用 {@code ThreadLocal} 栈；别线程的绑定
     * 互不可见，跨线程用跨度必须各自 push）。必须与 {@link #popCurrent} 在
     * finally 里配对（{@code startSpan} 的实现就是这么做的）。Default no-op.
     *
     * @param span the span to bind
     */
    default void pushCurrent(TelemetrySpan span) { }

    /**
     * Remove a span previously bound with {@link #pushCurrent}.  Only pops
     * if it is the current top — 不平衡对不会误拆外层绑定（嵌套跨度的
     * finally 乱序时外层仍在）。Default no-op.
     *
     * @param span the span to unbind
     */
    default void popCurrent(TelemetrySpan span) { }
}
