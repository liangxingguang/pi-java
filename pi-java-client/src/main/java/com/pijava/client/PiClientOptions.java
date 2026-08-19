package com.pijava.client;

import java.time.Duration;

/**
 * 客户端选项。
 */
public record PiClientOptions(
    ByteTransport transport,
    int maxFrameLength,
    Duration connectTimeout
) {
    /** 便捷构造：默认帧上限 + 连接超时。 */
    public static PiClientOptions of(ByteTransport transport) {
        return new PiClientOptions(transport,
            com.pijava.protocol.ProtocolVersion.DEFAULT_MAX_FRAME_LENGTH,
            Duration.ofSeconds(10));
    }
}
