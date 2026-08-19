package com.pijava.ai.catalog;

import java.time.Instant;
import java.util.List;

/**
 * 模型目录缓存条目（对齐 pi {@code ModelsStoreEntry}）。
 *
 * <p>持久化时经 {@link CatalogModel} DTO 序列化（{@code ModelInfo} 不可直接
 * Jackson round-trip）。ETag 为不透明校验值，含引号原样存储、原样回填
 * {@code If-None-Match}。</p>
 */
public record ModelsStoreEntry(
    List<ModelInfo> models,
    /** 远端 Last-Modified 头（可能为 null） */
    Instant lastModified,
    /** 上次完成远端检查的时间 */
    Instant checkedAt,
    /** 远端 ETag，原样存储（含引号） */
    String etag
) {
    /** 无远端元数据的缓存条目（本地构造）。 */
    public static ModelsStoreEntry of(List<ModelInfo> models) {
        return new ModelsStoreEntry(List.copyOf(models), null, null, null);
    }
}
