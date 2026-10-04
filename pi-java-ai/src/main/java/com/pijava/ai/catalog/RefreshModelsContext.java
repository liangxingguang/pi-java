package com.pijava.ai.catalog;

import java.util.Optional;

/**
 * 一次 {@code Provider.refreshModels} 调用的上下文（pi
 * {@code RefreshModelsContext}，原 docs/70 §1.2/§4.1）。
 *
 * <p>{@code stored} 是**本次刷新相开始前**的 provider 级目录快照；{@code publish}
 * 是世代检查后的发布端口；{@code allowNetwork=false} 表示离线相（只从缓存恢复
 * overlay，不发网络请求）。</p>
 *
 * <p>与 pi 的形状差异（判定见 原 docs/70 §12 的裁决记录）：
 * <ul>
 *   <li>pi 的 {@code credential} 不移植 —— pi 自己的 {@code withRemoteCatalog}
 *       从不读它，本仓也没有任何「要凭据才能拉目录」的 provider ⇒ 零读者；
 *   <li>pi 的 {@code signal: AbortSignal} 不移植 —— 原 docs/70 R2 裁决不支持中途取消，
 *       4s attempt timeout 已封顶（HTTP 层）。</li>
 * </ul></p>
 */
public record RefreshModelsContext(
    Optional<ModelsStoreEntry> stored,
    CatalogPublisherPort publish,
    boolean allowNetwork,
    boolean force
) {

    public RefreshModelsContext {
        stored = stored == null ? Optional.empty() : stored;
    }

    /** 离线相：只恢复缓存 overlay，永不联网。 */
    public static RefreshModelsContext offline(
            Optional<ModelsStoreEntry> stored, CatalogPublisherPort publish) {
        return new RefreshModelsContext(stored, publish, false, false);
    }

    /** 联网相：走完整分支（TTL 守卫、条件 GET、…）。 */
    public static RefreshModelsContext online(
            Optional<ModelsStoreEntry> stored, CatalogPublisherPort publish, boolean force) {
        return new RefreshModelsContext(stored, publish, true, force);
    }
}
