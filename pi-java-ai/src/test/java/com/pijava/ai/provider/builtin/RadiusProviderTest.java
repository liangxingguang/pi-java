package com.pijava.ai.provider.builtin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.pijava.ai.auth.OAuthCredentialStore;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.ModelsPublication;
import com.pijava.ai.catalog.ModelsStoreEntry;
import com.pijava.ai.catalog.RefreshModelsContext;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.RadiusGatewayClient;
import com.pijava.ai.provider.RadiusGatewayConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RadiusProviderTest {

    @TempDir
    Path tmp;

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
    void defaultProviderStartsWithThePublicBaseline() {
        var provider = new RadiusProvider();

        assertThat(provider.getModels()).hasSize(28);
        assertThat(provider.providerConfig().apiKeyEnvVar()).isEqualTo("RADIUS_API_KEY");
        assertThat(provider.providerConfig().defaultBaseUrl()).isEqualTo(RadiusProvider.DEFAULT_GATEWAY);
    }

    @Test
    void importsTheLegacyGatewayConfigWhenNothingIsStored() {
        var rig = RadiusProviderTestRig.withStore(legacyStore("""
            {"radius":{"accessToken":"a","refreshToken":"r","expiresAtEpochSec":1800000000,
              "gatewayConfig":{"baseUrl":"https://legacy.radius.test/v1","models":[
                {"id":"legacy-model","name":"Legacy","reasoning":false,"input":["text"],
                 "cost":{"input":1,"output":2,"cacheRead":0,"cacheWrite":0},
                 "contextWindow":1000,"maxTokens":100}]}}}
            """));
        var provider = rig.provider;

        provider.refreshModels(rig.offlineWithoutStoredContext());

        assertThat(provider.getModels()).extracting(m -> m.id().modelName())
            .containsExactly("legacy-model");
        assertThat(rig.persisted).extracting(m -> m.id().modelName())
            .containsExactly("legacy-model");
    }

    @Test
    void storedCatalogWinsOverTheLegacyConfig() {
        var rig = RadiusProviderTestRig.withCachedStoreAndOnline(
            List.of(model("stored")), List.of(), legacyStore("""
                {"radius":{"accessToken":"a","refreshToken":"r","expiresAtEpochSec":1800000000,
                  "gatewayConfig":{"baseUrl":"https://legacy.radius.test/v1","models":[
                    {"id":"legacy-model","name":"Legacy","reasoning":false,"input":["text"],
                     "cost":{"input":1,"output":2,"cacheRead":0,"cacheWrite":0},
                     "contextWindow":1000,"maxTokens":100}]}}}
                """));
        var provider = rig.provider;

        provider.refreshModels(rig.offlineContext());

        assertThat(provider.getModels()).extracting(m -> m.id().modelName())
            .containsExactly("stored");
        assertThat(rig.persisted).isNull();
    }

    @Test
    void anApiKeyAccountHasNoLegacyGatewayConfig() {
        var rig = RadiusProviderTestRig.withStore(legacyStore("""
            {"radius":{"accessToken":"a","refreshToken":"r","expiresAtEpochSec":1800000000}}
            """));
        var provider = rig.provider;

        provider.refreshModels(rig.offlineWithoutStoredContext());

        assertThat(provider.getModels()).hasSize(28);
        assertThat(rig.persisted).isNull();
    }

    private OAuthCredentialStore legacyStore(String json) {
        try {
            var path = tmp.resolve("auth-oauth.json");
            Files.writeString(path, json);
            return new OAuthCredentialStore(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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
            this(cached, onlineModels, null, "http://radius.test");
        }

        private RadiusProviderTestRig(List<ModelInfo> cached, List<ModelInfo> onlineModels,
                                      OAuthCredentialStore oauthStore) {
            this(cached, onlineModels, oauthStore, "http://radius.test");
        }

        private RadiusProviderTestRig(List<ModelInfo> cached, List<ModelInfo> onlineModels,
                                      OAuthCredentialStore oauthStore, String gatewayUrl) {
            stored = ModelsStoreEntry.of(cached);
            online = new RadiusGatewayConfig("http://radius.test", onlineModels);
            var client = new RadiusGatewayClient(gatewayUrl, null) {
                @Override
                public RadiusGatewayConfig load(String bearer) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    return online;
                }
            };
            provider = new RadiusProvider(client, () -> Optional.of("token"), oauthStore);
        }

        private static RadiusProviderTestRig withCachedAndOnline(
                List<ModelInfo> cached, List<ModelInfo> online) {
            return new RadiusProviderTestRig(cached, online);
        }

        private static RadiusProviderTestRig withStore(OAuthCredentialStore store) {
            return new RadiusProviderTestRig(
                List.of(), List.of(), store, RadiusProvider.DEFAULT_GATEWAY);
        }

        private static RadiusProviderTestRig withCachedStoreAndOnline(
                List<ModelInfo> cached, List<ModelInfo> online, OAuthCredentialStore store) {
            return new RadiusProviderTestRig(cached, online, store);
        }

        private RefreshModelsContext offlineContext() {
            return RefreshModelsContext.offline(Optional.of(stored), this::publish);
        }

        private RefreshModelsContext offlineWithoutStoredContext() {
            return RefreshModelsContext.offline(Optional.empty(), this::publish);
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
