package com.pijava.ai.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.CatalogPublisherPort;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.ModelsPublication;
import com.pijava.ai.catalog.RefreshModelsContext;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/70 §4.1：{@link Provider} 的动态协议 default —— 静态 provider 的
 * {@code getModels()} 就是 {@code builtinModels().listModels()}，且
 * {@code refreshModels()} 是**结构上的 no-op**（不 publish、不抛）。
 *
 * <p>这两条 default 是远程目录包装（{@code RemoteCatalogProvider} 覆写它们）
 * 的零破坏前提：任何既有 Provider 实现不改一行即满足新 SPI。</p>
 */
class ProviderDynamicProtocolTest {

    private static final ModelInfo MODEL = new ModelInfo(
        ModelId.of("static-test", "m1"),
        "M1",
        Set.of(ModelCapability.TEXT),
        1_000, 100, false,
        new PricingInfo(1.0, 2.0));

    /** 最小静态 provider：只实现四个必需方法，动态协议全走 default。 */
    private static Provider staticProvider(ModelCatalog catalog) {
        return new Provider() {
            @Override public String name() { return "static-test"; }
            @Override public String displayName() { return "Static Test"; }
            @Override public Set<Class<? extends ProviderApi>> supportedApis() {
                return Set.of();
            }
            @Override public <T extends ProviderApi> T createApi(
                    Class<T> apiType, ApiOptions options) {
                throw new UnsupportedOperationException();
            }
            @Override public ModelCatalog builtinModels() { return catalog; }
        };
    }

    @Test
    void defaultGetModelsEqualsBuiltinModels() {
        var provider = staticProvider(BuiltinCatalog.of(List.of(MODEL)));

        assertThat(provider.getModels()).containsExactly(MODEL);
    }

    @Test
    void defaultRefreshModelsIsNoOpAndPublishesNothing() {
        var provider = staticProvider(BuiltinCatalog.of(List.of(MODEL)));
        var publications = new ArrayList<ModelsPublication>();
        CatalogPublisherPort port = publication -> {
            publications.add(publication);
            return true;
        };

        provider.refreshModels(RefreshModelsContext.online(
            Optional.empty(), port, false));

        assertThat(publications).isEmpty();
        assertThat(provider.getModels()).containsExactly(MODEL);
    }
}
