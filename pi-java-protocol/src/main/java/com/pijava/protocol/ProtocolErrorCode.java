package com.pijava.protocol;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 协议错误码（对齐 pi {@code ProtocolErrorCodeSchema}）。纯常量闭集 → enum。
 */
public enum ProtocolErrorCode {
    VERSION, BUSY, SESSION_LOCKED, NOT_FOUND,
    INVALID_REQUEST, NOT_IMPLEMENTED, INTERNAL_ERROR;

    /** wire 值：snake_case。 */
    @JsonValue
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
