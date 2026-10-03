package com.pijava.ai.provider;

import java.util.List;
import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.RefreshModelsContext;

/**
 * Service Provider Interface (SPI) for LLM providers.
 *
 * <p>Each provider (Anthropic, OpenAI, Google, etc.) implements this
 * interface. Providers are discovered via {@link java.util.ServiceLoader}
 * and registered in the global provider registry.</p>
 *
 * <p>The {@link ProviderApi} sealed hierarchy constrains which API types
 * a provider can expose. Phase 1 only defines {@code ChatApi}; additional
 * modalities arrive in Phase 6.</p>
 */
public interface Provider {

    /** Machine-readable name, e.g. "anthropic", "openai". */
    String name();

    /** Human-readable display name, e.g. "Anthropic". */
    String displayName();

    /** The set of API types this provider can create. */
    Set<Class<? extends ProviderApi>> supportedApis();

    /** Create an API instance of the given type. */
    <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options);

    /** The built-in model catalog for this provider. */
    ModelCatalog builtinModels();

    /**
     * 当前生效的目录（pi {@code Provider.getModels}，docs/70 §1.2）：静态
     * provider 恒等于 {@link #builtinModels()}；动态 provider（远程目录包装）
     * 覆写它返回「静态 ∪ 动态 overlay」。
     *
     * <p>消费者（模型解析、列表）应当读这个而不是 {@code builtinModels()}，
     * 否则远程 overlay 不可见。</p>
     */
    default List<ModelInfo> getModels() {
        return builtinModels().listModels();
    }

    /**
     * 刷新动态目录（pi {@code Provider.refreshModels}，docs/70 §1.2）。
     *
     * <p>默认是**结构上的 no-op**：静态 provider 没有可刷新的东西。动态 provider
     * 覆写它，按 {@link RefreshModelsContext} 的分支（离线恢复 / TTL / 条件 GET /
     * 304 / 404 / 501 / transient / 200）发布新目录。</p>
     */
    default void refreshModels(RefreshModelsContext context) {
        // static provider: nothing to refresh
    }

    /**
     * Protocols this provider can serve.
     *
     * <p>{@link ConfigurableProvider} overrides this from
     * {@link ProviderConfig#supportedProtocols()}.</p>
     */
    default Set<Protocol> supportedProtocols() {
        return Set.of();
    }
}
