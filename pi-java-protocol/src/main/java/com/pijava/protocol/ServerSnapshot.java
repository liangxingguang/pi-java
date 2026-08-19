package com.pijava.protocol;

import java.util.List;

/**
 * 服务端快照（对齐 pi {@code ServerSnapshotSchema}）—— hello 握手与
 * ServerSnapshotEvent 携带。
 */
public record ServerSnapshot(
    String serverId,
    int protocolVersion,
    long revision,
    List<SessionMetadata> sessions,
    List<ModelMetadata> models
) {
    /** Compact constructor that defensively copies the lists. */
    public ServerSnapshot {
        sessions = List.copyOf(sessions);
        models = List.copyOf(models);
    }
}
