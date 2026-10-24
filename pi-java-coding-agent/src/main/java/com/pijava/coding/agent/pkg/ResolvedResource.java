package com.pijava.coding.agent.pkg;

/** pi {@code ResolvedResource}：路径＋enabled 标记＋来源元数据。 */
public record ResolvedResource(String path, boolean enabled, PathMetadata metadata) {}
