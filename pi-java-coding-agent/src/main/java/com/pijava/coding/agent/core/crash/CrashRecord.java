package com.pijava.coding.agent.core.crash;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 崩溃记录（pi {@code core/crash-log.ts} 的 {@code CrashRecord} interface 对位）。
 *
 * <p>JSON 边界形状逐字对齐：{@code notified} 缺席即未通知（pi 的可选键），
 * 类级 NON_NULL ⇒ 缺席键不出现在 crashes.json 里。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CrashRecord(
    // @JsonProperty：Json mapper 关了 getter 可见性，record 访问器会被当作
    // getter 忽略（同 Settings.Retry 先例）⇒ 显式钉住组件的 JSON 属性。
    @JsonProperty("timestamp") String timestamp,
    @JsonProperty("version") String version,
    @JsonProperty("kind") Kind kind,
    @JsonProperty("message") String message,
    @JsonProperty("stack") String stack,
    @JsonProperty("sessionFile") String sessionFile,
    @JsonProperty("cwd") String cwd,
    @JsonProperty("notified") Boolean notified
) {

    /** pi {@code CrashRecord["kind"]} 闭集两值（纯常量 ⇒ enum，wire 形 snake_case）。 */
    public enum Kind {
        /** 未捕获异常（uncaughtException 钩）。 */
        UNCAUGHT_EXCEPTION("uncaught_exception"),
        /** 致命运行时错误（handleFatalRuntimeError）。 */
        FATAL_ERROR("fatal_error");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        /** JSON 序列化形（snake_case）。 */
        @JsonValue
        public String wire() {
            return wire;
        }

        /** JSON 反序列化（snake_case）；未知值 ⇒ {@code null}（崩溃读取路径不容失败）。 */
        @JsonCreator
        public static Kind fromWire(String value) {
            for (var kind : values()) {
                if (kind.wire.equals(value)) {
                    return kind;
                }
            }
            return null;
        }
    }
}
