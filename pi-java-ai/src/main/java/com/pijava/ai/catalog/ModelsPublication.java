package com.pijava.ai.catalog;

import java.util.Optional;

/**
 * 一次目录发布（pi {@code ModelsPublication}，原 docs/70 §1.2/§4.1）。
 *
 * <p>pi 的 {@code persist} 是三态：{@code undefined} = 存储不动、
 * {@code null} = 删除、{@code entry} = 写入。Java 用
 * {@code Optional<ModelsStoreEntry>}（缺席 = 不动）＋ {@code delete}
 * （true = 删除）表达，两者互斥（构造器兜住）。</p>
 *
 * <p>{@code update} 是**同步的内存更新**：只在持久化落定、且世代检查通过
 * 之后跑一次（pi {@code models.ts:340-377}）。</p>
 */
public record ModelsPublication(
    Optional<ModelsStoreEntry> persist,
    boolean delete,
    Runnable update
) {

    public ModelsPublication {
        if (delete && persist.isPresent()) {
            throw new IllegalArgumentException(
                "publication cannot both persist an entry and delete it");
        }
        persist = persist == null ? Optional.empty() : persist;
        update = update == null ? () -> { } : update;
    }

    /** 只更新内存 overlay，存储不动（离线恢复那一相用）。 */
    public static ModelsPublication updateOnly(Runnable update) {
        return new ModelsPublication(Optional.empty(), false, update);
    }

    /** 写入 entry，不改内存 overlay（304/404/501/transient 分支用）。 */
    public static ModelsPublication persist(ModelsStoreEntry entry) {
        return new ModelsPublication(Optional.of(entry), false, () -> { });
    }

    /** 写入 entry 并立即更新内存 overlay（200 分支用）。 */
    public static ModelsPublication persistAndUpdate(ModelsStoreEntry entry, Runnable update) {
        return new ModelsPublication(Optional.of(entry), false, update);
    }

    /**
     * 删除该 provider 的缓存条目（pi 的 {@code persist: null}）。
     *
     * <p>⚠️ 本仓今天没有生产者：{@code RemoteCatalogProvider} 的四条分支都只写
     * 不删。保留它是 ported 协议完整性（{@code CatalogRefreshCoordinator} 有对应
     * 分支），证据见 原 docs/70 §1.4。</p>
     */
    public static ModelsPublication remove() {
        return new ModelsPublication(Optional.empty(), true, () -> { });
    }
}
