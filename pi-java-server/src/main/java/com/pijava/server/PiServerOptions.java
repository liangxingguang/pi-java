package com.pijava.server;

import java.time.Duration;
import java.util.List;

/**
 * 服务端选项（对齐 pi {@code PiServerOptions}）。
 */
public record PiServerOptions(
    List<PiServerListener> listeners,
    int maxFrameLength,
    Duration handshakeTimeout,
    String serverId
) {
    /** Compact constructor that defensively copies the listener list. */
    public PiServerOptions {
        listeners = List.copyOf(listeners);
    }

    /** 便捷构造：单个监听器 + 默认帧上限。 */
    public static PiServerOptions of(PiServerListener listener, String serverId) {
        return new PiServerOptions(List.of(listener),
            com.pijava.protocol.ProtocolVersion.DEFAULT_MAX_FRAME_LENGTH,
            Duration.ofSeconds(10), serverId);
    }
}
