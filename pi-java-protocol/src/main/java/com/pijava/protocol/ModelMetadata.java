package com.pijava.protocol;

import java.util.List;

/**
 * 模型元数据（对齐 pi {@code ModelMetadataSchema} 的可用子集）。
 */
public record ModelMetadata(
    String provider,
    String id,
    String name,
    String api,
    boolean reasoning,
    List<String> input,
    long contextWindow,
    long maxTokens,
    double inputPrice,
    double outputPrice,
    List<ProtocolThinkingLevel> supportedThinkingLevels,
    boolean authenticated
) {}
