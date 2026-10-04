package com.pijava.ai.provider;

import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.ModelCatalog;

/**
 * D-P1（{@code 原 docs/65}）：包装一个内置 provider，仅用 models.json
 * 合并后的 catalog 替换其模型目录，其余全部委托（R4：不改内置 provider 单例）。
 */
public final class OverrideProvider implements Provider {

    private final Provider delegate;
    private final ModelCatalog catalog;

    /** 包装一个 provider，仅替换其模型目录（D-P1）。 */
    public OverrideProvider(Provider delegate, ModelCatalog mergedCatalog) {
        this.delegate = delegate;
        this.catalog = mergedCatalog;
    }

    @Override public String name() { return delegate.name(); }
    @Override public String displayName() { return delegate.displayName(); }
    @Override public Set<Class<? extends ProviderApi>> supportedApis() {
        return delegate.supportedApis();
    }
    @Override public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        return delegate.createApi(apiType, options);
    }
    @Override public ModelCatalog builtinModels() { return catalog; }
    @Override public Set<Protocol> supportedProtocols() {
        return delegate.supportedProtocols();
    }
}
