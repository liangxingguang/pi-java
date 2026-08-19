package com.pijava.protocol;

/**
 * 协议错误载荷（对齐 pi {@code ProtocolErrorSchema}）。
 */
public record ProtocolError(
    ProtocolErrorCode code,
    String message
) {
    /** 便捷构造。 */
    public static ProtocolError of(ProtocolErrorCode code, String message) {
        return new ProtocolError(code, message);
    }
}
