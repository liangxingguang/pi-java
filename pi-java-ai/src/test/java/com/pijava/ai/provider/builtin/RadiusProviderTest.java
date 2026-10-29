package com.pijava.ai.provider.builtin;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.ModelsPublication;
import com.pijava.ai.catalog.ModelsStoreEntry;
import com.pijava.ai.catalog.RefreshModelsContext;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.RadiusGatewayClient;
import com.pijava.ai.provider.RadiusGatewayConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RadiusProviderTest {

    @Test
    void refreshedEmptyCatalogReplacesInsteadOfUnion() {
        var rig = RadiusProviderTestRig.withCachedAndOnline(
            List.of(model("old")), List.of());
        var provider = rig.provider;
        provider.refreshModels(rig.offlineContext());
        assertThat(provider.getModels()).extracting(m -> m.id().modelName()).containsExactly("old");

        provider.refreshModels(rig.onlineContext());

        assertThat(provider.getModels()).isEmpty();
        assertThat(rig.persisted).isEmpty();
    }

    @Test
    void networkFailureKeepsRestoredCatalog() {
        var rig = RadiusProviderTestRig.withCachedAndOnline(List.of(model("cached")), List.of());
        rig.failure = new IOException("offline");
        var provider = rig.provider;
        provider.refreshModels(rig.offlineContext());

        assertThatThrownBy(() -> provider.refreshModels(rig.onlineContext()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("offline");
        assertThat(provider.getModels()).extracting(m -> m.id().modelName()).containsExactly("cached");
    }

    @Test
    void defaultProviderStartsWithEmptyBaseline() {
        var provider = new RadiusProvider();

        assertThat(provider.getModels()).isEmpty();
        assertThat(provider.providerConfig().apiKeyEnvVar()).isEqualTo("RADIUS_API_KEY");
        assertThat(provider.providerConfig().defaultBaseUrl()).isEqualTo(RadiusProvider.DEFAULT_GATEWAY);
    }

    private static ModelInfo model(String id) {
        return ModelInfo.minimal(ModelId.of("radius", id));
    }

    /** Test-only fixture: production refreshModels runs against a fake gateway below it. */
    private static final class RadiusProviderTestRig {
        private final RadiusProvider provider;
        private final ModelsStoreEntry stored;
        private final RadiusGatewayConfig online;
        private List<ModelInfo> persisted;
        private IOException failure;

        private RadiusProviderTestRig(List<ModelInfo> cached, List<ModelInfo> onlineModels) {
            stored = ModelsStoreEntry.of(cached);
            online = new RadiusGatewayConfig("http://radius.test", onlineModels);
            var client = new RadiusGatewayClient("http://radius.test", null) {
                @Override
                public RadiusGatewayConfig load(String bearer) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    return online;
                }
            };
            provider = new RadiusProvider(client, () -> Optional.of("token"));
        }

        private static RadiusProviderTestRig withCachedAndOnline(
                List<ModelInfo> cached, List<ModelInfo> online) {
            return new RadiusProviderTestRig(cached, online);
        }

        private RefreshModelsContext offlineContext() {
            return RefreshModelsContext.offline(Optional.of(stored), this::publish);
        }

        private RefreshModelsContext onlineContext() {
            return RefreshModelsContext.online(Optional.of(stored), this::publish, true);
        }

        private boolean publish(ModelsPublication publication) {
            publication.persist().ifPresent(entry -> persisted = entry.models());
            publication.update().run();
            return true;
        }
    }
}
