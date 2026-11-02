package com.pijava.ai.provider.builtin;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.auth.Credentials;
import com.pijava.ai.auth.OAuthCredentialStore;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.ModelsPublication;
import com.pijava.ai.catalog.ModelsStoreEntry;
import com.pijava.ai.catalog.RefreshModelsContext;
import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;
import com.pijava.ai.provider.RadiusGatewayClient;
import com.pijava.ai.provider.RadiusLegacyCatalog;
import com.pijava.ai.protocol.PiMessagesApi;

/** Radius pi-messages gateway with a persisted, dynamically replaced account catalog. */
public final class RadiusProvider extends ConfigurableProvider {

    public static final String DEFAULT_GATEWAY = "https://radius.pi.dev";

    private final ProviderConfig config;
    private final RadiusGatewayClient gateway;
    private final Supplier<Optional<String>> credential;
    /** 旧 OAuth 凭证里的 gatewayConfig 来源；{@code null} ⇒ 不导入 legacy。 */
    private final OAuthCredentialStore oauthStore;
    private volatile List<ModelInfo> dynamicModels;

    /** Production provider using the default gateway and shared credential chain. */
    public RadiusProvider() {
        this(new RadiusGatewayClient(DEFAULT_GATEWAY,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()),
            () -> Credentials.resolveApiKey("radius"),
            new OAuthCredentialStore());
    }

    /** Dependency-injection seam for tests that do not exercise the legacy import. */
    RadiusProvider(RadiusGatewayClient gateway, Supplier<Optional<String>> credential) {
        this(gateway, credential, null);
    }

    /** Full dependency-injection seam (legacy gatewayConfig import included). */
    RadiusProvider(RadiusGatewayClient gateway, Supplier<Optional<String>> credential,
                   OAuthCredentialStore oauthStore) {
        this.gateway = gateway;
        this.credential = credential;
        this.oauthStore = oauthStore;
        this.config = ProviderConfig.single(
            "radius", "Radius", DEFAULT_GATEWAY, "RADIUS_API_KEY", Protocol.PI_MESSAGES,
            baselineFor(gateway.gateway()));
    }

    /**
     * 默认网关随包携带公共基线；自定义网关的账号目录未知，基线为空。
     * 一旦账号目录到达，它整体替换基线（组织禁用的模型不会从基线复活）。
     */
    private static ModelCatalog baselineFor(String gateway) {
        return RadiusGatewayClient.normalizeGateway(gateway)
                .equals(RadiusGatewayClient.normalizeGateway(DEFAULT_GATEWAY))
            ? BuiltinModelCatalogs.catalog("radius")
            : ModelCatalog.empty();
    }

    @Override
    protected ProviderConfig config() {
        return config;
    }

    @Override
    protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
        return new PiMessagesApi(options, "RADIUS_API_KEY");
    }

    @Override
    public List<ModelInfo> getModels() {
        var current = dynamicModels;
        return current == null ? config.builtinModels().listModels() : current;
    }

    @Override
    public void refreshModels(RefreshModelsContext context) {
        if (context.stored().isPresent()) {
            var restored = context.stored().get().models().stream()
                .filter(model -> model.id().provider().equals(name())).toList();
            boolean accepted = context.publish().publish(
                ModelsPublication.updateOnly(() -> dynamicModels = restored));
            if (!accepted) {
                return;
            }
        } else {
            // 没有新存储时，先看旧 OAuth 凭证里是否留着 gatewayConfig（只发生一次）。
            var legacy = RadiusLegacyCatalog.load(name(), oauthStore);
            if (!legacy.isEmpty()) {
                var legacyEntry = new ModelsStoreEntry(legacy, null, Instant.now(), null);
                boolean accepted = context.publish().publish(
                    ModelsPublication.persistAndUpdate(legacyEntry,
                        () -> dynamicModels = legacy));
                if (!accepted) {
                    return;
                }
            }
        }
        if (!context.allowNetwork()) {
            return;
        }
        try {
            var apiKey = context.credential().map(c -> c.value())
                .or(() -> credential.get())
                .orElse(null);
            var loaded = gateway.load(apiKey);
            var refreshed = loaded.models();
            var entry = new ModelsStoreEntry(refreshed, null, Instant.now(), null);
            context.publish().publish(ModelsPublication.persistAndUpdate(
                entry, () -> dynamicModels = refreshed));
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
