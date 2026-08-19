package com.pijava.ai.catalog;

import java.time.Instant;

/**
 * 远程目录刷新结果。
 */
public record CatalogRefreshResult(
    boolean changed,
    int modelCount,
    String etag,
    Instant refreshedAt
) {}
