package com.pijava.ai.catalog;

import java.util.Optional;

/**
 * 按 provider ID 键控的持久化模型目录（对齐 pi {@code ModelsStore}，补 Phase 1
 * P1-11 缺口）。
 *
 * <p>从 Phase 1 遗留：{@code 04-implementation-plan.md} P1-11 声称交付
 * {@code ModelsStore}，实际不存在。本接口即该缺口的补全，{@link FileModelsStore}
 * 为其文件系统实现（取代原设计自创的 {@code CatalogCache}）。</p>
 */
public interface ModelsStore {

    /** 读取指定 provider 的缓存条目。 */
    Optional<ModelsStoreEntry> read(String providerId, ModelsStoreOperationOptions options);

    /** 写入/覆盖缓存条目。 */
    void write(String providerId, ModelsStoreEntry entry, ModelsStoreOperationOptions options);

    /** 删除缓存条目。 */
    void delete(String providerId, ModelsStoreOperationOptions options);

    /** 无取消信号的读取。 */
    default Optional<ModelsStoreEntry> read(String providerId) {
        return read(providerId, ModelsStoreOperationOptions.NONE);
    }

    /** 无取消信号的写入。 */
    default void write(String providerId, ModelsStoreEntry entry) {
        write(providerId, entry, ModelsStoreOperationOptions.NONE);
    }

    /** 无取消信号的删除。 */
    default void delete(String providerId) {
        delete(providerId, ModelsStoreOperationOptions.NONE);
    }
}
