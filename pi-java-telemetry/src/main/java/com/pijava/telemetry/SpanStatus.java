package com.pijava.telemetry;

/**
 * pi {@code telemetry/src/index.ts:12} 的跨度状态：
 * {@code {status:"ok"} | {status:"error", error?:{name,message}}}。
 *
 * <p>{@link Error} 的两个组件同 null ≙ pi 的 {@code error} 键缺席——
 * {@code automaticErrorStatus} 对非 Error 拒绝值（或检查本身失败）的产出
 * （{@code memory.ts:78-87}）。</p>
 */
public sealed interface SpanStatus {

    /** pi {@code {status:"ok"}}。 */
    record Ok() implements SpanStatus { }

    /** pi {@code {status:"error", error?...}}；两组件同 null ≙ 无详情。 */
    record Error(String name, String message) implements SpanStatus {

        /** pi 的 {@code error} 对象两键必填 ⇒ 同 null 或同非 null。 */
        public Error {
            if ((name == null) != (message == null)) {
                throw new IllegalArgumentException(
                    "name and message must be both null or both non-null");
            }
        }
    }
}
