package com.pijava.ai.provider;

import java.util.List;

import com.pijava.ai.auth.OAuthCredentialStore;
import com.pijava.ai.catalog.ModelInfo;

/**
 * 从旧 Radius OAuth 凭证的 {@code gatewayConfig} 字段导入账号目录
 * （pi {@code getRadiusModels}，{@code providers/radius-config.ts:62-99}）。
 *
 * <p>pi 的旧实现把目录写进 OAuth 凭证；现行 pi 只读不写。Java 的
 * {@link com.pijava.ai.auth.OAuthCredential} 不加字段（加字段会在后续刷新时把旧字段
 * 写回存储、改变文件形状），而是由 {@link OAuthCredentialStore#resolveRawEntry(String)}
 * 在锁内返回原始节点，这里只读 {@code gatewayConfig}。</p>
 */
public final class RadiusLegacyCatalog {

    private RadiusLegacyCatalog() {}

    /** 旧凭证里的账号目录；没有该字段、形状非法或没有可用模型时为空。 */
    public static List<ModelInfo> load(String providerId, OAuthCredentialStore store) {
        if (store == null) {
            return List.of();
        }
        return store.resolveRawEntry(providerId)
            .map(node -> node.path("gatewayConfig"))
            .filter(RadiusGatewayConfig::isValidConfigNode)
            .map(node -> RadiusGatewayConfig.fromNode(providerId, node).models())
            .orElse(List.of());
    }
}
