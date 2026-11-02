package com.pijava.coding.agent.core;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.provider.builtin.ModelData;
import com.pijava.ai.provider.builtin.ProviderCatalog;

/**
 * 远程目录包装的装配点（pi {@code model-runtime.ts:229-240}）。从
 * {@code DefaultProviders} 抽出，让装配逻辑与其它 provider 组装分开维护。
 */
final class CatalogProviderWiring {

    /**
     * 不包远程目录的内置 provider：pi 排除 radius（它以账号/公共基线为准）；
     * 本仓另外排除图片 provider —— pi 的图片目录在独立的 {@code images-models.ts}，
     * 不对应 {@code /api/models/providers/<id>}。
     */
    private static final Set<String> REMOTE_CATALOG_EXCLUDED =
        Set.of("openrouter-images", "radius");

    private CatalogProviderWiring() {}

    /**
     * 给内置 provider 包上 pi.dev 远程目录。第三方 ServiceLoader provider 与
     * models.json 自定义 provider 不包：pi 的 {@code withRemoteCatalog} 只套在
     * 生成的内置目录上。
     */
    static void wrapRemoteCatalogs(ProviderRegistry registry, String catalogBaseUrl) {
        var builtinIds = ProviderCatalog.all().stream()
            .map(Provider::name)
            .collect(Collectors.toSet());
        // 整批共用一个 HttpClient（否则一次刷新会拉起 N 个选择器线程）。
        var http = RemoteCatalogProvider.defaultHttpClient();
        var generatedAt = Optional.of(ModelData.generatedAt());
        for (var provider : registry.listAll()) {
            if (!builtinIds.contains(provider.name())
                    || REMOTE_CATALOG_EXCLUDED.contains(provider.name())) {
                continue;
            }
            registry.register(RemoteCatalogProvider.wrap(
                provider, catalogBaseUrl, generatedAt, http));
        }
    }
}
