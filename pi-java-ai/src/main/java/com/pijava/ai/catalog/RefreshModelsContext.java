package com.pijava.ai.catalog;

import java.util.Optional;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.auth.RecordedCredential;

/**
 * 一次 {@code Provider.refreshModels} 调用的上下文（pi
 * {@code RefreshModelsContext}，原 docs/70 §1.2/§4.1）。
 *
 * <p>{@code stored} 是**本次刷新相开始前**的 provider 级目录快照；{@code publish}
 * 是世代检查后的发布端口；{@code allowNetwork=false} 表示离线相（只从缓存恢复
 * overlay，不发网络请求）。</p>
 *
 * <p>#16 起补齐 pi 的 {@code credential}/{@code baseUrl}/{@code signal}：
 * OpenRouter 的 {@code /models/user} 需要凭据与端点；信号由批次创建，
 * 当前没有生产调用方触发（HTTP 超时兜底），见设计稿裁决 7。</p>
 */
public record RefreshModelsContext(
    Optional<ModelsStoreEntry> stored,
    CatalogPublisherPort publish,
    boolean allowNetwork,
    boolean force,
    Optional<RecordedCredential> credential,
    String baseUrl,
    AbortSignal signal
) {

    public RefreshModelsContext {
        stored = stored == null ? Optional.empty() : stored;
        credential = credential == null ? Optional.empty() : credential;
        signal = signal == null ? AbortSignal.create() : signal;
    }

    /** 离线相：只恢复缓存 overlay，永不联网；不解析凭据（避免 OAuth 刷新副作用）。 */
    public static RefreshModelsContext offline(
            Optional<ModelsStoreEntry> stored, CatalogPublisherPort publish) {
        return new RefreshModelsContext(stored, publish, false, false,
            Optional.empty(), null, AbortSignal.create());
    }

    /** 联网相：走完整分支（TTL 守卫、条件 GET、…）。 */
    public static RefreshModelsContext online(
            Optional<ModelsStoreEntry> stored, CatalogPublisherPort publish, boolean force,
            Optional<RecordedCredential> credential, String baseUrl, AbortSignal signal) {
        return new RefreshModelsContext(stored, publish, true, force,
            credential, baseUrl, signal);
    }

    /** 旧三参形态：不携带凭据/端点，信号新建。 */
    public static RefreshModelsContext online(
            Optional<ModelsStoreEntry> stored, CatalogPublisherPort publish, boolean force) {
        return online(stored, publish, force, Optional.empty(), null, AbortSignal.create());
    }
}
