package com.pijava.protocol;

/**
 * 会话元数据（对齐 pi {@code SessionMetadataSchema}）。时间戳为 epoch 毫秒。
 */
public record SessionMetadata(
    String id,
    long createdAt,
    Long updatedAt,
    String parentSessionId,
    String sessionName,
    String cwd
) {}
