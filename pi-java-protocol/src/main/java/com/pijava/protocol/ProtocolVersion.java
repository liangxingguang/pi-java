package com.pijava.protocol;

/**
 * 协议版本常量（对齐 pi {@code PROTOCOL_VERSION}）。
 */
public final class ProtocolVersion {

    /** 当前协议版本，在 hello 握手里协商（不在帧头）。 */
    public static final int PROTOCOL_VERSION = 1;

    /** 帧载荷上限（16MB），超限抛 {@link FrameException}。 */
    public static final int DEFAULT_MAX_FRAME_LENGTH = 16 * 1024 * 1024;

    private ProtocolVersion() {
    }
}
